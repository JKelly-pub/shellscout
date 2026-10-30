package app.shellscout.vpn

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.shellscout.data.HostDao
import app.shellscout.data.WgConfigDao
import app.shellscout.data.WgConfigEntity
import app.shellscout.security.Vault
import app.shellscout.ssh.Prompt
import app.shellscout.ssh.PromptBroker
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * The tunnel that is up. [manual]: started from the WireGuard screen, so it runs until stopped there.
 * Otherwise a host connection started it, and it stops when the last session using it closes.
 */
data class ActiveTunnel(val configId: Long, val name: String, val manual: Boolean, val sessions: Int)

class TunnelException(message: String) : Exception(message)

/**
 * WireGuard as Android's VPN, through the official userspace backend (wireguard-go). Android allows
 * one VPN at a time, so there is at most one tunnel; starting another asks first. Session tabs
 * [acquire] the tunnel pinned to their host before connecting and [release] it when they close.
 */
class WireGuardManager(
    private val context: Context,
    private val dao: WgConfigDao,
    private val hostDao: HostDao,
    private val vault: Vault,
    private val prompts: PromptBroker,
    private val scope: CoroutineScope,
) {
    // Created on first use: it loads the native library.
    private val backend: GoBackend by lazy { GoBackend(context) }
    private val lock = Mutex()
    /** Session tab ids using the current tunnel. */
    private val users = mutableSetOf<String>()
    @Volatile private var tunnel: AppTunnel? = null

    private val _active = MutableStateFlow<ActiveTunnel?>(null)
    val active: StateFlow<ActiveTunnel?> = _active.asStateFlow()

    val configs: Flow<List<WgConfigEntity>> = dao.observeAll()

    // ------------------------------------------------------------------ configs

    /** Checks wg-quick text as the backend will read it. */
    fun parse(text: String): Config = try {
        Config.parse(BufferedReader(StringReader(text)))
    } catch (e: BadConfigException) {
        throw TunnelException(describe(e))
    } catch (e: IOException) {
        throw TunnelException("Couldn't read the config: ${e.message}")
    }

    suspend fun import(name: String, text: String): Long {
        parse(text)
        return dao.insert(WgConfigEntity(name = name.trim(), encryptedConfig = vault.encryptString(normalise(text))))
    }

    /** A running tunnel keeps its old settings until it is next started. */
    suspend fun update(config: WgConfigEntity, name: String, text: String) {
        parse(text)
        dao.update(config.copy(name = name.trim(), encryptedConfig = vault.encryptString(normalise(text))))
    }

    fun text(config: WgConfigEntity): String = vault.decryptString(config.encryptedConfig)

    /** Stops it if it is up and unpins it from its hosts. */
    suspend fun delete(config: WgConfigEntity) = lock.withLock {
        if (_active.value?.configId == config.id) bringDown()
        hostDao.clearWgConfig(config.id)
        dao.delete(config)
    }

    // ------------------------------------------------------------------ tunnel

    /** From the WireGuard screen: the tunnel then runs until stopped there. */
    suspend fun start(configId: Long) = lock.withLock {
        val current = _active.value
        if (current?.configId == configId) {
            _active.value = current.copy(manual = true)
            return@withLock
        }
        if (current != null) switchFrom(current, configId)
        bringUp(configId, manual = true)
    }

    suspend fun stop() = lock.withLock { bringDown() }

    /** Before session tab [user] connects to a host pinned to [configId]. Throws [TunnelException]. */
    suspend fun acquire(configId: Long, user: String) = lock.withLock {
        val current = _active.value
        if (current?.configId != configId) {
            if (current != null) switchFrom(current, configId)
            bringUp(configId, manual = false)
        }
        users += user
        _active.value = _active.value?.copy(sessions = users.size)
    }

    /** Session tab [user] closed. A tunnel a host started stops with its last session. */
    fun release(user: String) {
        scope.launch {
            lock.withLock {
                if (!users.remove(user)) return@withLock
                val current = _active.value ?: return@withLock
                if (users.isEmpty() && !current.manual) bringDown() else _active.value = current.copy(sessions = users.size)
            }
        }
    }

    suspend fun statistics(): Statistics? {
        val t = tunnel ?: return null
        return withContext(Dispatchers.IO) { runCatching { backend.getStatistics(t) }.getOrNull() }
    }

    private suspend fun switchFrom(current: ActiveTunnel, configId: Long) {
        val next = dao.get(configId)?.name ?: "another tunnel"
        if (!prompts.ask(Prompt.SwitchTunnel(current.name, next, users.size))) {
            throw TunnelException("${current.name} is still running; only one tunnel can be up")
        }
        bringDown()
    }

    private suspend fun bringUp(configId: Long, manual: Boolean) {
        val entity = dao.get(configId) ?: throw TunnelException("That WireGuard config has been deleted")
        val config = parse(text(entity))
        VpnService.prepare(context)?.let { consent ->
            if (!prompts.ask(Prompt.VpnPermission(consent))) throw TunnelException("ShellScout needs VPN permission to start the tunnel")
        }
        val t = AppTunnel(tunnelName(entity))
        try {
            withContext(Dispatchers.IO) { backend.setState(t, Tunnel.State.UP, config) }
        } catch (e: Exception) {
            throw TunnelException(describe(e))
        }
        tunnel = t
        users.clear()
        _active.value = ActiveTunnel(entity.id, entity.name, manual, 0)
    }

    private suspend fun bringDown() {
        val t = tunnel ?: return
        tunnel = null
        users.clear()
        _active.value = null
        withContext(Dispatchers.IO) { runCatching { backend.setState(t, Tunnel.State.DOWN, null) } }
    }

    /** The backend reports DOWN when Android takes the VPN away (another VPN app, or the user in Settings). */
    private inner class AppTunnel(private val name: String) : Tunnel {
        override fun getName(): String = name
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && tunnel === this) {
                tunnel = null
                scope.launch { lock.withLock { users.clear(); _active.value = null } }
            }
        }
    }

    private companion object {
        fun normalise(text: String) = text.replace("\r\n", "\n").trim() + "\n"

        /** Android shows this as the VPN's name; the backend allows 15 characters from a small set. */
        fun tunnelName(config: WgConfigEntity): String {
            val name = config.name.replace(Regex("[^a-zA-Z0-9_=+.-]"), "_").take(Tunnel.NAME_MAX_LENGTH)
            return if (Tunnel.isNameInvalid(name)) "wg${config.id}" else name
        }

        fun describe(e: Throwable): String = when (e) {
            is TunnelException -> e.message.orEmpty()
            is BadConfigException -> {
                val where = if (e.location == BadConfigException.Location.TOP_LEVEL) "" else " ${e.location.getName()}"
                val what = e.reason.name.lowercase().replace('_', ' ')
                "Invalid config: [${e.section.name.lowercase().replaceFirstChar { it.uppercase() }}]$where: $what" +
                    e.text?.takeIf { it.isNotBlank() && e.location != BadConfigException.Location.PRIVATE_KEY }?.let { " ($it)" }.orEmpty()
            }
            is BackendException -> when (e.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED -> "ShellScout needs VPN permission to start the tunnel"
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> "Couldn't look up the endpoint ${e.format.firstOrNull() ?: ""}".trim()
                BackendException.Reason.UNABLE_TO_START_VPN -> "Android didn't start the VPN service"
                BackendException.Reason.TUN_CREATION_ERROR -> "Android refused to create the VPN interface. Is another app's always-on VPN set?"
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "WireGuard couldn't start (error ${e.format.firstOrNull() ?: "?"})"
                else -> "WireGuard error: ${e.reason.name.lowercase().replace('_', ' ')}"
            }
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

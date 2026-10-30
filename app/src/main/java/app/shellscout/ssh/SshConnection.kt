package app.shellscout.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import app.shellscout.data.AuthMethod
import app.shellscout.data.HostEntity
import app.shellscout.data.HostDao
import app.shellscout.data.KeyDao
import app.shellscout.data.KnownHostDao
import app.shellscout.security.KeyManager
import app.shellscout.security.Vault

/** Shared dependencies for every connection. */
class SshDeps(
    val vault: Vault,
    val keyManager: KeyManager,
    val keyDao: KeyDao,
    val hostDao: HostDao,
    val knownHostDao: KnownHostDao,
    val prompts: PromptBroker,
    /** In-memory only: passphrases the user unlocked during this process lifetime, by key id. */
    val passphraseCache: MutableMap<Long, CharArray> = java.util.concurrent.ConcurrentHashMap(),
    val defaultKeepAliveSeconds: () -> Int,
)

sealed interface ConnState {
    data object Connecting : ConnState
    data object Authenticating : ConnState
    data object Connected : ConnState
    data class Disconnected(val reason: String?) : ConnState
}

class AuthCancelledException : CancellationException("Cancelled by user")

/** [exitStatus] is null if the server closed the channel without reporting one. */
data class ExecResult(val exitStatus: Int?, val output: String)

/**
 * SE-03: one SSH connection per session tab, carrying both the interactive shell and the SFTP
 * subsystem, so the user authenticates once. All methods block on network I/O and must be called
 * off the main thread (they switch to [Dispatchers.IO] themselves).
 */
class SshConnection(override val host: HostEntity, private val deps: SshDeps) : RemoteConnection {

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    private val client = SSHClient(AlgorithmPolicy.config(host.allowLegacyAlgorithms))
    private val sftpLock = Mutex()
    private var sftpClient: SFTPClient? = null
    private var shellSession: Session? = null

    override val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        _state.value = ConnState.Connecting
        try {
            client.addHostKeyVerifier(TofuHostKeyVerifier(deps.knownHostDao, deps.prompts))
            client.connectTimeout = 10_000
            client.connect(host.hostname, host.port)
            client.transport.disconnectListener = DisconnectListener { _, message ->
                _state.value = ConnState.Disconnected(message?.takeIf { it.isNotBlank() } ?: "Connection lost")
            }
            client.connection.keepAlive.apply {
                keepAliveInterval = host.keepAliveSeconds ?: deps.defaultKeepAliveSeconds()
                (this as? KeepAliveRunner)?.maxAliveCount = 3
            }
            _state.value = ConnState.Authenticating
            authenticate()
            deps.hostDao.markConnected(host.id, System.currentTimeMillis())
            _state.value = ConnState.Connected
        } catch (e: Throwable) {
            runCatching { client.disconnect() }
            _state.value = ConnState.Disconnected(describe(e))
            throw e
        }
    }

    private suspend fun authenticate() {
        when (host.authMethod) {
            AuthMethod.PASSWORD -> authWithPassword()
            AuthMethod.KEY -> authWithKey()
        }
    }

    private suspend fun authWithPassword() {
        val saved = host.encryptedPassword?.let { runCatching { deps.vault.decryptString(it) }.getOrNull() }
        if (saved != null) {
            try {
                client.auth(host.username, AuthPassword(oneOff(saved.toCharArray())), keyboardInteractive(saved.toCharArray()))
                return
            } catch (ignored: UserAuthException) {
                // Saved password no longer works; fall through and ask.
            }
        }
        repeat(MAX_ATTEMPTS) { attempt ->
            val answer = deps.prompts.ask(Prompt.Password(host.hostname, host.username, offerSave = true))
                ?: throw AuthCancelledException()
            try {
                client.auth(host.username, AuthPassword(oneOff(answer.secret)), keyboardInteractive(answer.secret))
                if (answer.remember) {
                    deps.hostDao.update(
                        host.copy(savePassword = true, encryptedPassword = deps.vault.encryptString(String(answer.secret))),
                    )
                }
                return
            } catch (e: UserAuthException) {
                if (attempt == MAX_ATTEMPTS - 1) throw e
            } finally {
                answer.secret.fill('\u0000')
            }
        }
    }

    private suspend fun authWithKey() {
        val keyId = host.keyId ?: error("No key selected for this host")
        val key = deps.keyDao.get(keyId) ?: error("The key for this host has been deleted")
        var passphrase: CharArray? = null
        if (key.passphraseProtected) {
            passphrase = deps.passphraseCache[key.id] ?: run {
                val answer = deps.prompts.ask(Prompt.Passphrase(key.name)) ?: throw AuthCancelledException()
                if (answer.remember) deps.passphraseCache[key.id] = answer.secret.copyOf()
                answer.secret
            }
        }
        val provider = try {
            deps.keyManager.keyProvider(key, passphrase).also { it.private }
        } catch (e: Exception) {
            deps.passphraseCache.remove(key.id)
            throw IllegalStateException("Could not unlock key \"${key.name}\": wrong passphrase?", e)
        }
        // Keyboard-interactive follows public key so servers requiring key + OTP work (AU-05).
        client.auth(host.username, AuthPublickey(provider), keyboardInteractive(null))
    }

    private fun oneOff(secret: CharArray) = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.copyOf()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    /** AU-05: answers "Password:" prompts from the known password and asks the user for anything else. */
    private fun keyboardInteractive(password: CharArray?): AuthKeyboardInteractive {
        val pw = password?.copyOf()
        return AuthKeyboardInteractive(object : ChallengeResponseProvider {
            private var title = ""
            private var instruction = ""
            private var passwordUsed = false
            override fun getSubmethods(): List<String> = emptyList()
            override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
                title = name.orEmpty(); this.instruction = instruction.orEmpty()
            }

            override fun getResponse(prompt: String, echo: Boolean): CharArray {
                if (pw != null && !passwordUsed && PasswordResponseProvider.DEFAULT_PROMPT_PATTERN.matcher(prompt).matches()) {
                    passwordUsed = true
                    return pw.copyOf()
                }
                val reply = deps.prompts.askBlocking(Prompt.Interactive(title.ifBlank { host.hostname }, instruction, prompt, echo))
                return reply?.toCharArray() ?: throw AuthCancelledException()
            }

            override fun shouldRetry(): Boolean = false
        })
    }

    /** Starts an interactive login shell with a PTY of the given size. */
    suspend fun openShell(columns: Int, rows: Int): Session.Shell = withContext(Dispatchers.IO) {
        val session = client.startSession()
        session.allocatePTY(host.termType, columns, rows, 0, 0, emptyMap())
        shellSession = session
        session.startShell()
    }

    /**
     * Runs one non-interactive command on its own channel and waits for it. stderr is merged into
     * stdout by the caller (`2>&1`) so a single stream can't stall on a full window.
     */
    suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val output = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            cmd.join()
            ExecResult(cmd.exitStatus, output)
        }
    }

    override fun fs(): SftpFs = SftpFs(this)

    /** SE-03: the SFTP subsystem on the same authenticated connection. */
    suspend fun sftp(): SFTPClient = sftpLock.withLock {
        sftpClient ?: withContext(Dispatchers.IO) { client.newSFTPClient() }.also { sftpClient = it }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        runCatching { sftpClient?.close() }
        runCatching { shellSession?.close() }
        runCatching { client.disconnect() }
        if (_state.value !is ConnState.Disconnected) _state.value = ConnState.Disconnected("Disconnected")
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun describe(e: Throwable): String = when (e) {
            is AuthCancelledException -> "Cancelled"
            is UserAuthException -> "Authentication failed"
            is java.net.UnknownHostException -> "Unknown host"
            is java.net.ConnectException -> "Connection refused"
            is java.net.SocketTimeoutException -> "Connection timed out"
            is net.schmizz.sshj.transport.TransportException ->
                if (e.message?.contains("HostKeyVerifier", true) == true || e.message?.contains("verify", true) == true)
                    "Host key not trusted" else (e.message ?: "Transport error")
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

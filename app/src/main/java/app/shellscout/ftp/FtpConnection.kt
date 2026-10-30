package app.shellscout.ftp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import app.shellscout.data.HostEntity
import app.shellscout.data.Protocol
import app.shellscout.ssh.AuthCancelledException
import app.shellscout.ssh.ConnState
import app.shellscout.ssh.Prompt
import app.shellscout.ssh.RemoteConnection
import app.shellscout.ssh.SshConnection
import app.shellscout.ssh.SshDeps
import java.io.IOException
import java.net.SocketException
import java.time.Duration
import javax.net.ssl.SSLContext

/** The server said no; [message] carries its reply, e.g. "550 Permission denied." */
class FtpException(message: String) : IOException(message)

class FtpLoginException(message: String = "Authentication failed") : IOException(message)

/**
 * A session tab's FTP or FTPS connection. FTP allows one command at a time per connection, so the
 * tab's browsing shares one control connection under a lock, and each transfer logs in on its own
 * (as FileZilla does) so transfers and browsing don't wait for each other. The password is held in
 * memory for that until the tab closes.
 *
 * Data connections are always passive, which is what works through NAT and mobile networks.
 */
class FtpConnection(override val host: HostEntity, private val deps: SshDeps) : RemoteConnection {

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var control: FTPClient? = null
    @Volatile private var password: CharArray? = null
    @Volatile private var closed = false

    /** The login directory; every path the app sends is absolute, so the control connection stays here. */
    var home: String = "/"
        private set

    /** Server supports MLST/MLSD (exact listings and times); from FEAT. */
    var hasMlst = false
        private set
    var hasSize = false
        private set

    override val isConnected: Boolean get() = _state.value == ConnState.Connected

    override fun fs(): FtpFs = FtpFs(this)

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        _state.value = ConnState.Connecting
        var client: FTPClient? = null
        try {
            client = newClient()
            _state.value = ConnState.Authenticating
            client = authenticate(client)
            hasMlst = runCatching { client.hasFeature("MLST") }.getOrDefault(false)
            hasSize = runCatching { client.hasFeature("SIZE") }.getOrDefault(false)
            home = client.printWorkingDirectory() ?: "/"
            control = client
            deps.hostDao.markConnected(host.id, System.currentTimeMillis())
            _state.value = ConnState.Connected
            startKeepAlive()
        } catch (e: Throwable) {
            client?.let { quietly(it) }
            _state.value = ConnState.Disconnected(describe(e))
            throw e
        }
    }

    /** Opens the control connection; for FTPS, commons-net upgrades it with AUTH TLS before returning. */
    private fun newClient(): FTPClient {
        val client = if (host.protocol == Protocol.FTPS) {
            // A context per client: Android caches TLS sessions per context and host:port, and each
            // control connection's data connections must resume that connection's own session.
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TofuTrustManager(host.hostname, host.port, deps.knownHostDao, deps.prompts)), null)
            }
            TlsClient(context)
        } else {
            FTPClient()
        }
        client.controlEncoding = "UTF-8"
        client.setAutodetectUTF8(true)
        client.connectTimeout = 10_000
        client.setDefaultTimeout(30_000)
        client.setDataTimeout(Duration.ofSeconds(60))
        try {
            client.connect(host.hostname, host.port)
        } catch (e: javax.net.ssl.SSLException) {
            quietly(client)
            // commons-net reports a refused AUTH TLS as an SSLException carrying the server's reply.
            if (e.message?.trim()?.firstOrNull()?.isDigit() == true) {
                throw FtpException("The server doesn't offer FTPS (${e.message?.trim()}). Edit the host and choose FTP, or enable TLS on the server.")
            }
            throw e
        }
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            val reply = client.replyString.trim()
            quietly(client)
            throw FtpException("The server refused the connection: $reply")
        }
        client.soTimeout = 30_000
        return client
    }

    /** Settings that only take effect once logged in. */
    private fun prepare(client: FTPClient) {
        if (client is TlsClient) {
            // Protect the data connections too, not just the control connection and password.
            client.execPBSZ(0)
            client.execPROT("P")
            client.setSocketFactory(ReusingTlsSocketFactory(client.context.socketFactory, host.hostname, host.port))
        }
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw FtpException("Couldn't switch to binary mode: ${client.replyString.trim()}")
        client.enterLocalPassiveMode()
        client.setListHiddenFiles(true)
        client.bufferSize = 64 * 1024
    }

    /** Returns the client that ended up logged in: the server may hang up after failed attempts. */
    private suspend fun authenticate(first: FTPClient): FTPClient {
        var client = first
        val saved = host.encryptedPassword?.let { runCatching { deps.vault.decryptString(it) }.getOrNull() }
        if (saved != null) {
            if (login(client, saved.toCharArray())) {
                password = saved.toCharArray()
                return client
            }
            // Saved password no longer works; fall through and ask.
        }
        repeat(MAX_ATTEMPTS) { attempt ->
            val answer = deps.prompts.ask(Prompt.Password(host.hostname, host.username, offerSave = true))
                ?: throw AuthCancelledException()
            try {
                if (!client.isConnected) client = newClient()
                if (login(client, answer.secret)) {
                    password = answer.secret.copyOf()
                    if (answer.remember) {
                        deps.hostDao.update(
                            host.copy(savePassword = true, encryptedPassword = deps.vault.encryptString(String(answer.secret))),
                        )
                    }
                    return client
                }
                if (attempt == MAX_ATTEMPTS - 1) throw FtpLoginException()
            } finally {
                answer.secret.fill('\u0000')
            }
        }
        throw FtpLoginException()
    }

    /** False for a wrong user name or password; anything else the server objects to is thrown. */
    private fun login(client: FTPClient, secret: CharArray): Boolean {
        if (!client.login(host.username, String(secret))) {
            val reply = client.replyString.trim()
            // vsftpd and others refuse plain-FTP logins with 530 too, but no password will fix that.
            if (Regex("encrypt|TLS|SSL|secure", RegexOption.IGNORE_CASE).containsMatchIn(reply)) {
                throw FtpLoginException("The server requires encryption. Edit the host and choose FTPS. ($reply)")
            }
            if (client.replyCode != FTPReply.NOT_LOGGED_IN) throw FtpLoginException("Login refused: $reply")
            return false
        }
        prepare(client)
        return true
    }

    /**
     * Runs [block] on the control connection, one caller at a time. If the server has dropped an idle
     * connection, logs in again and retries once, so a tab left open still works when it's next used.
     */
    suspend fun <T> withControl(block: (FTPClient) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val client = control?.takeIf { it.isConnected } ?: reopen()
            try {
                block(client)
            } catch (e: IOException) {
                if (e !is FTPConnectionClosedException && e !is SocketException || closed) throw e
                block(reopen())
            }
        }
    }

    private fun reopen(): FTPClient {
        if (closed) throw IOException("Disconnected")
        control?.let { quietly(it) }
        control = null
        try {
            val pw = password ?: throw IOException("Not connected")
            val client = newClient()
            if (!login(client, pw)) {
                quietly(client)
                throw FtpLoginException()
            }
            control = client
            _state.value = ConnState.Connected
            return client
        } catch (e: Exception) {
            _state.value = ConnState.Disconnected(describe(e))
            throw e
        }
    }

    /** A separate, logged-in connection for one transfer. Give it back with [release]. */
    suspend fun openTransferClient(): FTPClient = withContext(Dispatchers.IO) {
        val pw = password ?: throw IOException("Not connected")
        val client = newClient()
        try {
            if (!login(client, pw)) throw FtpLoginException()
            client
        } catch (e: Throwable) {
            quietly(client)
            throw e
        }
    }

    fun release(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        quietly(client)
    }

    /** Servers drop idle sessions (vsftpd after 5 minutes); a NOOP now and then keeps the tab's session open. */
    private fun startKeepAlive() {
        val seconds = host.keepAliveSeconds ?: deps.defaultKeepAliveSeconds()
        if (seconds <= 0) return
        scope.launch {
            while (isActive && !closed) {
                delay(seconds * 1000L)
                // Once marked disconnected, wait for the user (or their next action) rather than retrying here.
                if (_state.value == ConnState.Connected) runCatching { withControl { it.sendNoOp() } }
            }
        }
    }

    override suspend fun disconnect(reason: String): Unit = withContext(Dispatchers.IO) {
        closed = true
        scope.cancel()
        // Not under the lock: an operation in progress fails rather than holding up the close.
        control?.let { c -> runCatching { c.logout() }; quietly(c) }
        control = null
        password?.fill('\u0000')
        password = null
        if (_state.value !is ConnState.Disconnected) _state.value = ConnState.Disconnected(reason)
    }

    private fun quietly(client: FTPClient) {
        runCatching { client.disconnect() }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        fun describe(e: Throwable): String = when {
            e is FtpLoginException || e is FtpException -> e.message ?: "FTP error"
            e is javax.net.ssl.SSLException && generateSequence(e as Throwable) { it.cause }.any { it.message == TofuTrustManager.NOT_TRUSTED } ->
                "Certificate not trusted"
            else -> SshConnection.describe(e)
        }
    }
}

/** Explicit FTPS (AUTH TLS), remembering its TLS context for the data connections. */
private class TlsClient(val context: SSLContext) : FTPSClient(false, context)

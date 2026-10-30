package app.shellscout.ftp

import kotlinx.coroutines.runBlocking
import app.shellscout.data.KnownHostDao
import app.shellscout.data.KnownHostEntity
import app.shellscout.ssh.Prompt
import app.shellscout.ssh.PromptBroker
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * SC-03 for FTPS. A certificate that Android trusts and that names the host is accepted silently,
 * as a browser would. Anything else (self-signed is usual on home and small-office servers) is
 * trusted on first use like an SSH host key: the user checks the fingerprint once, and a different
 * certificate later gets the same "changed" warning. Entries share the known-hosts list.
 */
class TofuTrustManager(
    private val host: String,
    private val port: Int,
    private val dao: KnownHostDao,
    private val prompts: PromptBroker,
) : X509TrustManager {

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("The server sent no certificate")
        val publiclyTrusted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess
        if (publiclyTrusted && namesHost(chain[0], host)) return

        // Called on the handshake thread, so waiting for the user's answer here is fine.
        val trusted = runBlocking {
            val cert = chain[0]
            val blob = Base64.getEncoder().encodeToString(cert.encoded)
            val fp = fingerprint(cert)
            val known = dao.forHost(host, port).filter { it.keyType == KEY_TYPE }
            when {
                known.any { it.keyBlobBase64 == blob } -> true
                known.isNotEmpty() -> {
                    val replace = prompts.ask(Prompt.HostKeyChanged(host, port, KEY_TYPE, known.first().fingerprint, fp))
                    if (replace) {
                        known.forEach { dao.delete(it) }
                        dao.upsert(KnownHostEntity(host = host, port = port, keyType = KEY_TYPE, keyBlobBase64 = blob, fingerprint = fp))
                    }
                    replace
                }
                else -> {
                    val trust = prompts.ask(Prompt.HostKeyNew(host, port, KEY_TYPE, fp))
                    if (trust) dao.upsert(KnownHostEntity(host = host, port = port, keyType = KEY_TYPE, keyBlobBase64 = blob, fingerprint = fp))
                    trust
                }
            }
        }
        if (!trusted) throw CertificateException(NOT_TRUSTED)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("Client certificates aren't accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        /** Shown in the trust dialogs and the known-hosts list. */
        const val KEY_TYPE = "TLS certificate"
        const val NOT_TRUSTED = "Certificate not trusted"

        /** As `openssl x509 -noout -fingerprint -sha256` prints it, so it can be compared on the server. */
        fun fingerprint(cert: X509Certificate): String =
            "SHA256:" + MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        /** RFC 6125: the subject alternative names, with a wildcard covering one leftmost label. */
        fun namesHost(cert: X509Certificate, host: String): Boolean {
            val isIp = host.matches(Regex("[0-9.]+")) || ':' in host
            val target = host.lowercase().trimEnd('.')
            return cert.subjectAlternativeNames.orEmpty().any { san ->
                val value = (san.getOrNull(1) as? String)?.lowercase()?.trimEnd('.') ?: return@any false
                when (san.getOrNull(0) as? Int) {
                    2 -> !isIp && (value == target || value.startsWith("*.") &&
                        target.endsWith(value.substring(1)) && target.indexOf('.') == target.length - value.length + 1)
                    7 -> isIp && runCatching { InetAddress.getByName(value) == InetAddress.getByName(host) }.getOrDefault(false)
                    else -> false
                }
            }
        }
    }
}

/**
 * Makes FTPS data connections resume the control connection's TLS session. vsftpd insists on this
 * by default (require_ssl_reuse), and Android only offers a cached session to a socket made for the
 * same host and port as the one that created it, which a data connection's own port never is. So
 * each data socket is connected in plain TCP and then wrapped as if for the control port. Conscrypt
 * takes a wrapped socket's port from the socket underneath, so that one reports the control port too.
 *
 * The handshake is left to the first read or write: the server only starts it once it has the
 * transfer command, which commons-net sends after connecting.
 */
class ReusingTlsSocketFactory(
    private val tls: SSLSocketFactory,
    private val controlHost: String,
    private val controlPort: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = DataSocket()
    override fun createSocket(host: String?, port: Int): Socket = unsupported()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = unsupported()

    // commons-net only ever asks for an unconnected socket for passive-mode data.
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Data sockets are created unconnected")

    /** A plain socket until connected, then the TLS socket wrapped around it. Covers what commons-net uses. */
    private inner class DataSocket : Socket() {
        private val plain = object : Socket() {
            // Only the TLS session cache reads this; traffic goes to the address actually connected.
            override fun getPort(): Int = controlPort
        }
        @Volatile private var secure: SSLSocket? = null
        private val active: Socket get() = secure ?: plain

        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            plain.connect(endpoint, timeout)
            secure = (tls.createSocket(plain, controlHost, controlPort, true) as SSLSocket).apply { useClientMode = true }
        }

        override fun bind(bindpoint: SocketAddress?) = plain.bind(bindpoint)
        override fun getInetAddress(): InetAddress? = plain.inetAddress
        override fun getLocalAddress(): InetAddress = plain.localAddress
        override fun getPort(): Int = plain.port
        override fun getLocalPort(): Int = plain.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = plain.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = plain.localSocketAddress
        override fun getInputStream(): InputStream = active.getInputStream()
        override fun getOutputStream(): OutputStream = active.getOutputStream()
        override fun setSoTimeout(timeout: Int) { active.soTimeout = timeout }
        override fun getSoTimeout(): Int = active.soTimeout
        override fun setReceiveBufferSize(size: Int) { plain.receiveBufferSize = size }
        override fun getReceiveBufferSize(): Int = plain.receiveBufferSize
        override fun setSendBufferSize(size: Int) { plain.sendBufferSize = size }
        override fun getSendBufferSize(): Int = plain.sendBufferSize
        override fun setTcpNoDelay(on: Boolean) { plain.tcpNoDelay = on }
        override fun setKeepAlive(on: Boolean) { plain.keepAlive = on }
        override fun isConnected(): Boolean = plain.isConnected
        override fun isBound(): Boolean = plain.isBound
        override fun isClosed(): Boolean = active.isClosed
        override fun close() = active.close()
    }
}

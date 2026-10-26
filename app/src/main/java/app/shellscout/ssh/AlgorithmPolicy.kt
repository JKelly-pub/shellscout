package app.shellscout.ssh

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory

/**
 * SC-04 / SC-05. The strict profile removes SHA-1, CBC, RC4, 3DES, MD5 and group1/group14-sha1 key
 * exchange from sshj's defaults. A host can opt in to the legacy profile, which keeps sshj's
 * full default list; the UI flags such hosts.
 */
object AlgorithmPolicy {

    private val weakKex = Regex("group1-sha1|group14-sha1|group-exchange-sha1$|^diffie-hellman-group-exchange-sha1")
    private val weakCipher = Regex("cbc|3des|arcfour|blowfish|cast128|idea", RegexOption.IGNORE_CASE)
    private val weakMac = Regex("md5|sha1(?!\\d)|ripemd", RegexOption.IGNORE_CASE)
    private val weakKeyAlg = Regex("^ssh-rsa$|^ssh-dss$|^ssh-rsa-cert-v01@openssh.com$|^ssh-dss-cert")

    fun config(allowLegacy: Boolean): Config {
        val c: Config = DefaultConfig()
        // SE-04/SE-05: keep-alive that also detects a dead link (disconnects after N missed replies).
        c.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        // Host-key and keyboard-interactive prompts block sshj's key-exchange/auth wait while the user
        // reads a fingerprint or types an OTP; the 30 s default is too short for that.
        c.timeoutMs = 180_000
        if (allowLegacy) return c
        c.keyExchangeFactories = c.keyExchangeFactories.strip(weakKex)
        c.cipherFactories = c.cipherFactories.strip(weakCipher)
        c.macFactories = c.macFactories.strip(weakMac)
        c.keyAlgorithms = c.keyAlgorithms.strip(weakKeyAlg)
        return c
    }

    private fun <T> List<Factory.Named<T>>.strip(weak: Regex): List<Factory.Named<T>> =
        filterNot { weak.containsMatchIn(it.name) }
}

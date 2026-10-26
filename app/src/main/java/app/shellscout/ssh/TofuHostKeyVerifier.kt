package app.shellscout.ssh

import android.util.Base64
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import app.shellscout.data.KnownHostDao
import app.shellscout.data.KnownHostEntity
import java.security.MessageDigest
import java.security.PublicKey

/**
 * SC-03: trust on first use. Unknown hosts prompt with the SHA-256 fingerprint; a changed key is
 * refused unless the user explicitly chooses to replace it in a deliberately alarming dialog.
 * sshj calls this on its transport thread, so blocking for the user's answer is acceptable.
 */
class TofuHostKeyVerifier(
    private val dao: KnownHostDao,
    private val prompts: PromptBroker,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = runBlocking {
        val type = KeyType.fromKey(key).toString()
        val blob = Base64.encodeToString(keyBlob(key), Base64.NO_WRAP)
        val fp = fingerprint(key)
        val known = dao.forHost(hostname, port)
        val sameType = known.firstOrNull { it.keyType == type }

        when {
            sameType != null && sameType.keyBlobBase64 == blob -> true

            sameType != null || known.isNotEmpty() -> {
                // Either the key of this type changed, or the server now presents a different key type
                // than any we trusted before. Both are treated as a possible man-in-the-middle.
                val old = sameType ?: known.first()
                val replace = prompts.ask(Prompt.HostKeyChanged(hostname, port, type, old.fingerprint, fp))
                if (replace) {
                    known.forEach { dao.delete(it) }
                    dao.upsert(KnownHostEntity(host = hostname, port = port, keyType = type, keyBlobBase64 = blob, fingerprint = fp))
                }
                replace
            }

            else -> {
                val trust = prompts.ask(Prompt.HostKeyNew(hostname, port, type, fp))
                if (trust) {
                    dao.upsert(KnownHostEntity(host = hostname, port = port, keyType = type, keyBlobBase64 = blob, fingerprint = fp))
                }
                trust
            }
        }
    }

    /** Lets sshj negotiate the key type we already trust, so a known host is not re-prompted. */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = runBlocking {
        dao.forHost(hostname, port).map { it.keyType }
    }

    companion object {
        fun keyBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

        /** Same format as `ssh-keygen -lf`: SHA256:<unpadded base64>. */
        fun fingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob(key))
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}

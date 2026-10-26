package app.shellscout.security

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import app.shellscout.data.KeyDao
import app.shellscout.data.KeyEntity
import app.shellscout.data.KeyFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

enum class GeneratedKeyType(val label: String) {
    ED25519("Ed25519 (recommended)"),
    ECDSA_P256("ECDSA P-256"),
    RSA_3072("RSA 3072"),
    RSA_4096("RSA 4096"),
}

/** Thrown when an imported key needs a passphrase that was not supplied or was wrong. */
class PassphraseRequiredException : Exception("This key is passphrase-protected")

/** AU-02..AU-04: generate, import and load SSH keys. Private material only ever exists decrypted in memory. */
class KeyManager(private val dao: KeyDao, private val vault: Vault) {

    suspend fun generate(name: String, type: GeneratedKeyType): KeyEntity = withContext(Dispatchers.Default) {
        val kp = when (type) {
            GeneratedKeyType.ED25519 ->
                KeyPairGenerator.getInstance("Ed25519", BC).generateKeyPair()
            GeneratedKeyType.ECDSA_P256 ->
                KeyPairGenerator.getInstance("EC", BC).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            GeneratedKeyType.RSA_3072 ->
                KeyPairGenerator.getInstance("RSA", BC).apply { initialize(3072) }.generateKeyPair()
            GeneratedKeyType.RSA_4096 ->
                KeyPairGenerator.getInstance("RSA", BC).apply { initialize(4096) }.generateKeyPair()
        }
        val keyType = KeyType.fromKey(kp.public).toString()
        val entity = KeyEntity(
            name = name,
            keyType = keyType,
            publicKeyOpenSsh = openSshPublicKey(kp.public, name),
            encryptedPrivate = vault.encrypt(serialiseKeyPair(kp)),
            format = KeyFormat.GENERATED,
        )
        entity.copy(id = dao.insert(entity))
    }

    /**
     * Import an OpenSSH, PEM/PKCS#8 or PuTTY .ppk private key. If it is encrypted, [passphrase] is used
     * once to validate it; the passphrase itself is never stored.
     */
    suspend fun import(name: String, privateKeyText: String, passphrase: CharArray?): KeyEntity =
        withContext(Dispatchers.Default) {
            val provider = loadText(privateKeyText, passphrase)
            val pub = try {
                provider.public
            } catch (e: Exception) {
                throw if (looksEncrypted(privateKeyText) && passphrase == null) PassphraseRequiredException() else e
            }
            try {
                provider.private // forces decryption: proves the passphrase is right
            } catch (e: Exception) {
                throw if (looksEncrypted(privateKeyText)) PassphraseRequiredException() else e
            }
            val entity = KeyEntity(
                name = name,
                keyType = KeyType.fromKey(pub).toString(),
                publicKeyOpenSsh = openSshPublicKey(pub, name),
                encryptedPrivate = vault.encryptString(privateKeyText),
                format = KeyFormat.IMPORTED,
                passphraseProtected = passphrase != null && passphrase.isNotEmpty(),
            )
            entity.copy(id = dao.insert(entity))
        }

    /** Build an sshj [KeyProvider] for authentication. */
    fun keyProvider(key: KeyEntity, passphrase: CharArray?): KeyProvider = when (key.format) {
        KeyFormat.GENERATED -> KeyPairWrapper(deserialiseKeyPair(vault.decrypt(key.encryptedPrivate)))
        KeyFormat.IMPORTED -> loadText(vault.decryptString(key.encryptedPrivate), passphrase)
    }

    private fun loadText(text: String, passphrase: CharArray?): KeyProvider {
        val finder: PasswordFinder? = passphrase?.let { pw ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = pw.copyOf()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
        }
        // An unconnected client is only used for its configured key-file parsers.
        return SSHClient(DefaultConfig()).loadKeys(text, null, finder)
    }

    private fun looksEncrypted(text: String) =
        text.contains("ENCRYPTED") || text.contains("Proc-Type: 4,ENCRYPTED") ||
            text.contains("Encryption: aes") || isEncryptedOpenSshV1(text)

    /** openssh-key-v1 files carry the cipher name in the binary body; "none" means unencrypted. */
    private fun isEncryptedOpenSshV1(text: String): Boolean {
        if (!text.contains("BEGIN OPENSSH PRIVATE KEY")) return false
        return try {
            val body = text.lines().filter { !it.startsWith("-----") }.joinToString("")
            val bytes = Base64.decode(body, Base64.DEFAULT)
            val magic = "openssh-key-v1\u0000".toByteArray()
            val buf = Buffer.PlainBuffer(bytes.copyOfRange(magic.size, bytes.size))
            buf.readString() != "none"
        } catch (ignored: Exception) {
            false
        }
    }

    companion object {
        const val BC = "BC"

        fun openSshPublicKey(pub: PublicKey, comment: String): String {
            val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
            val safeComment = comment.replace(Regex("\\s+"), "_")
            return KeyType.fromKey(pub).toString() + " " + Base64.encodeToString(blob, Base64.NO_WRAP) + " " + safeComment
        }

        private fun algorithmFor(pub: PublicKey): String = when (pub.algorithm) {
            "EdDSA", "Ed25519" -> "Ed25519"
            "ECDSA", "EC" -> "EC"
            else -> pub.algorithm
        }

        private fun serialiseKeyPair(kp: KeyPair): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeUTF(algorithmFor(kp.public))
                val priv = kp.private.encoded
                val pub = kp.public.encoded
                d.writeInt(priv.size); d.write(priv)
                d.writeInt(pub.size); d.write(pub)
            }
            return out.toByteArray()
        }

        private fun deserialiseKeyPair(bytes: ByteArray): KeyPair {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val alg = d.readUTF()
                val priv = ByteArray(d.readInt()).also { d.readFully(it) }
                val pub = ByteArray(d.readInt()).also { d.readFully(it) }
                val kf = KeyFactory.getInstance(alg, BC)
                return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub)), kf.generatePrivate(PKCS8EncodedKeySpec(priv)))
            }
        }
    }
}

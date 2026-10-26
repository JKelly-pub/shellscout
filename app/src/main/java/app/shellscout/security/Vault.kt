package app.shellscout.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SC-01: secrets at rest are AES-256-GCM encrypted with a key that never leaves Android Keystore
 * (hardware-backed where the device supports it). Ciphertext format: "v1:<b64 iv>:<b64 ciphertext>".
 *
 * The Keystore key is not bound to user authentication, so background reconnects work while the
 * app is locked. Access to the UI is gated separately by [AppLock] (SC-02).
 */
class Vault {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        // BouncyCastle is appended at the END of the provider list (see ShellScoutApp), so provider
        // selection for a Keystore-held key still resolves to AndroidKeyStore.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain)
        return "v1:" + b64(cipher.iv) + ":" + b64(ct)
    }

    fun decrypt(token: String): ByteArray {
        val parts = token.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unrecognised vault token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(parts[1])))
        return cipher.doFinal(unb64(parts[2]))
    }

    fun encryptString(plain: String): String = encrypt(plain.toByteArray(Charsets.UTF_8))
    fun decryptString(token: String): String = decrypt(token).toString(Charsets.UTF_8)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "shellscout_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

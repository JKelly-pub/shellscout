package app.shellscout.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AuthMethod { PASSWORD, KEY }

/** How the app talks to a host. FTP and FTPS give file access only; there is no terminal. */
enum class Protocol(val defaultPort: Int) {
    SSH(22),
    /** Plain FTP: the password and files cross the network unencrypted. */
    FTP(21),
    /** FTP with explicit TLS (AUTH TLS) on the usual FTP port. */
    FTPS(21),
    ;

    val hasTerminal: Boolean get() = this == SSH
}

/** HM-01, HM-05: a saved host profile with per-host overrides. */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val keyId: Long? = null,
    /** HM-03: hosts are grouped by this label, e.g. "Home" or "Work". Empty = ungrouped. */
    val groupName: String = "",
    /** AU-06: off by default; the user is prompted on every connect. */
    val savePassword: Boolean = false,
    /** Vault-encrypted. Only set when [savePassword] is true. */
    val encryptedPassword: String? = null,
    /** Null = use the app default. */
    val keepAliveSeconds: Int? = null,
    val termType: String = "xterm-256color",
    val startDirectory: String? = null,
    /** SC-05: legacy algorithms are opt-in per host and flagged in the UI. */
    val allowLegacyAlgorithms: Boolean = false,
    val lastConnectedAt: Long? = null,
    @ColumnInfo(defaultValue = "SSH")
    val protocol: Protocol = Protocol.SSH,
)

enum class KeyFormat {
    /** Generated in-app; PKCS#8 private + X.509 public encoded, stored as a JCA key pair. */
    GENERATED,
    /** Imported text (OpenSSH or PEM), loaded by sshj, possibly passphrase-protected. */
    IMPORTED,
}

/** AU-02..AU-04: a private key held in the vault. */
@Entity(tableName = "keys")
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** e.g. ssh-ed25519, ecdsa-sha2-nistp256, ssh-rsa. */
    val keyType: String,
    /** One-line OpenSSH public key, safe to show and share. */
    val publicKeyOpenSsh: String,
    /** Vault-encrypted private material (see [KeyFormat]). */
    val encryptedPrivate: String,
    val format: KeyFormat,
    val passphraseProtected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** SC-03: trust-on-first-use host key store. */
@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["host", "port", "keyType"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val port: Int,
    val keyType: String,
    /** Base64 of the SSH wire-format public key blob. */
    val keyBlobBase64: String,
    /** SHA256:<base64> fingerprint as OpenSSH prints it. */
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** SN-01..SN-04. A null [hostId] and empty [groupName] means global. */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,
    val sendEnter: Boolean = true,
    val hostId: Long? = null,
    val groupName: String = "",
    val sortOrder: Int = 0,
)

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** FT-05, RP-04: transfer queue persisted across process death. */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val direction: TransferDirection,
    /** content:// URI of the local file or SAF directory. */
    val localUri: String,
    val remotePath: String,
    val displayName: String,
    val totalBytes: Long = -1,
    val doneBytes: Long = 0,
    val status: TransferStatus = TransferStatus.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

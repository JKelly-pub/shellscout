package app.shellscout.ssh

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** SFTP on an SSH connection, plus the extras a shell on the server makes possible (cp, mv, tar, unzip). */
class SftpFs(private val connection: SshConnection) : RemoteFs() {

    override val canCopy: Boolean get() = true
    override val canExtract: Boolean get() = true

    private suspend fun sftp(): SFTPClient = connection.sftp()

    override suspend fun home(): String = io { sftp().canonicalize(".") }

    override suspend fun list(dir: String): List<RemoteEntry> = io {
        val s = sftp()
        s.ls(dir).map { info ->
            val attrs = info.attributes
            val isLink = attrs.type == FileMode.Type.SYMLINK
            // Follow symlinks so a link to a directory can be opened.
            val target: FileAttributes? = if (isLink) runCatching { s.stat(info.path) }.getOrNull() else attrs
            RemoteEntry(
                name = info.name,
                path = info.path,
                isDirectory = (target ?: attrs).type == FileMode.Type.DIRECTORY,
                isSymlink = isLink,
                size = (target ?: attrs).size,
                mtime = attrs.mtime,
                permissions = attrs.mode.permissionsMask,
            )
        }.sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun stat(path: String): RemoteStat? = io {
        sftp().statExistence(path)?.let { RemoteStat(it.type == FileMode.Type.DIRECTORY, it.size, it.mtime) }
    }

    override suspend fun mkdir(path: String) = io { sftp().mkdir(path) }

    override suspend fun mkdirs(path: String) = io { sftp().mkdirs(path) }

    override suspend fun rename(from: String, to: String) = io { sftp().rename(from, to) }

    override suspend fun chmod(path: String, permissions: Int) = io { sftp().chmod(path, permissions) }

    override suspend fun delete(entry: RemoteEntry): Unit = io { deleteRecursive(sftp(), entry.path, entry.isDirectory && !entry.isSymlink) }

    private fun deleteRecursive(s: SFTPClient, path: String, isDir: Boolean) {
        try {
            if (!isDir) {
                s.rm(path)
                return
            }
            // A read-only folder can't be emptied, even by its owner (Go's module cache does this). It is
            // about to go anyway, so give the owner full access first; if we aren't the owner this just fails.
            val perms = s.stat(path).mode.permissionsMask
            if (perms and OWNER_RWX != OWNER_RWX) runCatching { s.chmod(path, perms or OWNER_RWX) }
            for (child in s.ls(path)) {
                val type = child.attributes.type
                deleteRecursive(s, child.path, type == FileMode.Type.DIRECTORY)
            }
            s.rmdir(path)
        } catch (e: SFTPException) {
            // Name the item that failed: in a big tree "Permission denied" alone says nothing.
            throw RemoteDeleteException(path, e)
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = io {
        val rf = sftp().open(path, EnumSet.of(OpenMode.READ))
        object : FilterInputStream(rf.ReadAheadRemoteFileInputStream(16, offset)) {
            override fun close() = try { super.close() } finally { rf.close() }
        }
    }

    override suspend fun openWrite(path: String, offset: Long): OutputStream = io {
        val modes = if (offset > 0) EnumSet.of(OpenMode.WRITE) else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        val rf = sftp().open(path, modes)
        object : FilterOutputStream(rf.RemoteFileOutputStream(offset, 16)) {
            // FilterOutputStream writes byte by byte unless told otherwise.
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = try { super.close() } finally { rf.close() }
        }
    }

    // mv also copies across filesystems, which an SFTP rename can't.
    override suspend fun moveOnServer(from: String, to: String, renameError: Throwable) = run("mv", "mv -- ${quote(from)} ${quote(to)} 2>&1")

    // -a keeps permissions, times and symlinks as they are; the data never leaves the server.
    override suspend fun copyOnServer(from: String, to: String) = run("cp", "cp -a -- ${quote(from)} ${quote(to)} 2>&1")

    /**
     * AU-05: adds [publicKey] (one OpenSSH line) to ~/.ssh/authorized_keys, like ssh-copy-id.
     * A key already there, matched on its type and data and not its comment, is left alone.
     * Returns true if the key was added, false if it was already there.
     */
    suspend fun authorizeKey(publicKey: String): Boolean {
        val line = publicKey.trim()
        val keyPart = line.split(Regex("\\s+")).take(2).joinToString(" ")
        // Run by sh whatever the login shell is (fish, for one, can't parse this). umask makes a
        // new ~/.ssh 700 and the file 600, as sshd requires. A last line without a newline would
        // join onto the key, so one is added first.
        val script = "umask 077 && mkdir -p ~/.ssh && f=~/.ssh/authorized_keys && touch \"\$f\" && " +
            "if grep -qF ${quote(keyPart)} \"\$f\"; then echo SHELLSCOUT_PRESENT; else " +
            "{ [ ! -s \"\$f\" ] || [ -z \"\$(tail -c 1 \"\$f\")\" ] || echo >> \"\$f\"; } && " +
            "printf '%s\\n' ${quote(line)} >> \"\$f\" && echo SHELLSCOUT_ADDED; fi"
        val result = connection.exec("sh -c ${quote(script)} 2>&1")
        return when {
            "SHELLSCOUT_ADDED" in result.output -> true
            "SHELLSCOUT_PRESENT" in result.output -> false
            else -> throw IllegalStateException(
                "Couldn't add the key: " + result.output.trim().lines().takeLast(2).joinToString(" ").ifBlank { "exit status ${result.exitStatus}" },
            )
        }
    }

    private suspend fun run(tool: String, command: String) {
        val result = connection.exec(command)
        if (result.exitStatus != 0) {
            throw IllegalStateException(
                if (result.exitStatus == 127) "$tool isn't available on the server"
                else "$tool failed: " + result.output.trim().lines().takeLast(3).joinToString(" ").ifBlank { "exit status ${result.exitStatus}" },
            )
        }
    }

    /**
     * Unpacks a .zip or tar archive on the server into a new folder beside it, named after the archive
     * ("site.zip" -> "site", or "site (2)" if that exists), so nothing already there is overwritten.
     * The archive itself is kept. Returns the folder's path.
     */
    override suspend fun extract(entry: RemoteEntry): String {
        val base = archiveBaseName(entry.name) ?: throw IllegalArgumentException("${entry.name} isn't a supported archive")
        val dest = freeName(parent(entry.path)) { if (it == 1) base else "$base ($it)" }
        mkdir(dest)
        val archive = quote(entry.path)
        val command = if (entry.name.endsWith(".zip", ignoreCase = true)) {
            "unzip -q -n $archive -d ${quote(dest)} 2>&1"
        } else {
            // GNU tar picks the decompressor itself; -k refuses to replace anything.
            "tar -xkf $archive -C ${quote(dest)} 2>&1"
        }
        val result = connection.exec(command)
        // unzip exits 1 for warnings (e.g. a file it skipped) with everything else extracted.
        val ok = result.exitStatus == 0 || (result.exitStatus == 1 && command.startsWith("unzip"))
        if (!ok) {
            io { runCatching { sftp().rmdir(dest) } } // only succeeds if nothing was extracted
            val tool = command.substringBefore(' ')
            throw IllegalStateException(
                if (result.exitStatus == 127) "$tool isn't installed on the server (try: sudo apt install $tool)"
                else "Extract failed: " + result.output.trim().lines().takeLast(3).joinToString(" ").ifBlank { "$tool exited with ${result.exitStatus}" },
            )
        }
        return dest
    }

    private companion object {
        /** 0700 */
        const val OWNER_RWX = 0x1C0

        /** Single-quotes [s] for a POSIX shell. */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

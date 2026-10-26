package app.shellscout.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.File
import java.util.EnumSet

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** Seconds since epoch. */
    val mtime: Long,
    /** Permission bits, e.g. 0755. */
    val permissions: Int,
) {
    val isHidden: Boolean get() = name.startsWith(".")
}

/** A remote text file opened for editing, with what we need to detect conflicts (ED-02) and round-trip it (ED-04). */
data class EditableFile(
    val path: String,
    val text: String,
    val mtime: Long,
    val size: Long,
    val crlf: Boolean,
)

class FileTooLargeException(val size: Long) : Exception("File is too large to edit (${size / 1024} KB)")
class BinaryFileException : Exception("This looks like a binary file and can't be edited")
class RemoteChangedException : Exception("The file changed on the server since you opened it")
class RemoteDeleteException(val path: String, cause: SFTPException) : Exception("Can't delete $path: ${cause.message}", cause)

/** FT-10: items cut or copied in the SFTP browser, waiting to be pasted into another folder. */
data class RemoteClipboard(val entries: List<RemoteEntry>, val cut: Boolean) {
    val paths: Set<String> = entries.mapTo(HashSet()) { it.path }
}

/** FT-01, FT-04, ED-01..ED-05: SFTP operations on one connection. */
class RemoteFs(private val connection: SshConnection) {

    private suspend fun sftp(): SFTPClient = connection.sftp()

    suspend fun home(): String = io { sftp().canonicalize(".") }

    suspend fun canonicalize(path: String): String = io { sftp().canonicalize(path) }

    suspend fun list(dir: String): List<RemoteEntry> = io {
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

    suspend fun stat(path: String): FileAttributes? = io { sftp().statExistence(path) }

    suspend fun mkdir(path: String) = io { sftp().mkdir(path) }

    suspend fun mkdirs(path: String) = io { sftp().mkdirs(path) }

    suspend fun rename(from: String, to: String) = io { sftp().rename(from, to) }

    suspend fun chmod(path: String, permissions: Int) = io { sftp().chmod(path, permissions) }

    /** Deletes a file, or a directory and everything under it. Symlinks are removed, never followed. */
    suspend fun delete(entry: RemoteEntry): Unit = io { deleteRecursive(sftp(), entry.path, entry.isDirectory && !entry.isSymlink) }

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

    /**
     * FT-10: moves or copies [entries] into [destDir]. Nothing is overwritten: an item whose name is
     * taken gets a numbered name ("notes (2).txt"), so copying into the same folder duplicates it.
     * Moving an item to the folder it is already in does nothing. Returns how many items changed.
     */
    suspend fun paste(entries: List<RemoteEntry>, destDir: String, move: Boolean): Int {
        val dest = destDir.trimEnd('/').ifEmpty { "/" }
        entries.firstOrNull { it.isDirectory && !it.isSymlink && (dest == it.path || dest.startsWith(it.path.trimEnd('/') + "/")) }
            ?.let { throw IllegalArgumentException("Can't put ${it.name} inside itself") }
        var done = 0
        for (e in entries) {
            if (move && parent(e.path) == dest) continue
            val target = io {
                val s = sftp()
                generateSequence(1) { it + 1 }.map { join(dest, numberedName(e.name, it)) }.first { s.statExistence(it) == null }
            }
            if (move) {
                // An SFTP rename is instant but only works within one filesystem; mv also copies across them.
                val renamed = runCatching { io { sftp().rename(e.path, target) } }.isSuccess
                if (!renamed) run("mv", "mv -- ${quote(e.path)} ${quote(target)} 2>&1")
            } else {
                // -a keeps permissions, times and symlinks as they are; the data never leaves the server.
                run("cp", "cp -a -- ${quote(e.path)} ${quote(target)} 2>&1")
            }
            done++
        }
        return done
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
    suspend fun extract(entry: RemoteEntry): String {
        val base = archiveBaseName(entry.name) ?: throw IllegalArgumentException("${entry.name} isn't a supported archive")
        val parent = parent(entry.path)
        val dest = io {
            val s = sftp()
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) base else "$base ($it)" }
                .first { s.statExistence(join(parent, it)) == null }
            join(parent, name).also { s.mkdir(it) }
        }
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

    suspend fun readText(path: String, maxBytes: Long = DEFAULT_MAX_EDIT_BYTES): EditableFile = io {
        val s = sftp()
        val attrs = s.stat(path)
        if (attrs.size > maxBytes) throw FileTooLargeException(attrs.size)
        val bytes = s.open(path, EnumSet.of(OpenMode.READ)).use { rf ->
            rf.RemoteFileInputStream().use { it.readBytes() }
        }
        if (bytes.take(8192).any { it == 0.toByte() }) throw BinaryFileException()
        val text = bytes.toString(Charsets.UTF_8)
        EditableFile(path, text.replace("\r\n", "\n"), attrs.mtime, attrs.size, crlf = text.contains("\r\n"))
    }

    /**
     * ED-02: refuses to save if the file changed since it was opened, unless [force].
     * ED-04: writes in place with truncate, so the file keeps its owner and permission bits; line
     * endings are restored to what the file had.
     */
    suspend fun writeText(file: EditableFile, newText: String, force: Boolean = false): EditableFile = io {
        val s = sftp()
        val current = s.statExistence(file.path)
        if (!force && current != null && (current.mtime != file.mtime || current.size != file.size)) {
            throw RemoteChangedException()
        }
        val out = if (file.crlf) newText.replace("\n", "\r\n") else newText
        val bytes = out.toByteArray(Charsets.UTF_8)
        s.open(file.path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
            rf.RemoteFileOutputStream(0, 16).use { it.write(bytes) }
        }
        val after = s.stat(file.path)
        file.copy(text = newText, mtime = after.mtime, size = after.size)
    }

    /** FT-09: fetch a remote file into the app cache so it can be shared with another app. */
    suspend fun downloadToCache(entry: RemoteEntry, cacheDir: File): File = io {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest shared file
        val out = File(dir, entry.name)
        sftp().open(entry.path, EnumSet.of(OpenMode.READ)).use { rf ->
            rf.RemoteFileInputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** ED-05 default threshold: 5 MB. */
        const val DEFAULT_MAX_EDIT_BYTES = 5L * 1024 * 1024

        /** 0700 */
        private const val OWNER_RWX = 0x1C0

        private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz", ".tar", ".zip")

        /** The archive's name without its extension, or null if [extract] can't handle it. */
        fun archiveBaseName(name: String): String? =
            ARCHIVE_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) }
                ?.let { name.dropLast(it.length).ifBlank { "extracted" } }

        /** Single-quotes [s] for a POSIX shell. */
        private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /** "notes.txt" with i = 2 gives "notes (2).txt"; i = 1 leaves the name as it is. */
        fun numberedName(name: String, i: Int): String {
            if (i == 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
        }

        fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

        fun parent(path: String): String {
            val trimmed = path.trimEnd('/')
            val i = trimmed.lastIndexOf('/')
            return if (i <= 0) "/" else trimmed.substring(0, i)
        }

        fun permissionString(perms: Int, isDir: Boolean): String {
            val chars = "rwxrwxrwx"
            val sb = StringBuilder(if (isDir) "d" else "-")
            for (i in 0 until 9) sb.append(if (perms and (1 shl (8 - i)) != 0) chars[i] else '-')
            return sb.toString()
        }
    }
}

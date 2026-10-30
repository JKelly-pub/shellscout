package app.shellscout.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import app.shellscout.data.HostEntity
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** Seconds since epoch. */
    val mtime: Long,
    /** Permission bits, e.g. 0755, or -1 if the server doesn't say (some FTP listings). */
    val permissions: Int,
) {
    val isHidden: Boolean get() = name.startsWith(".")
}

/** What [RemoteFs.stat] reports about one path, with symlinks followed. */
data class RemoteStat(val isDirectory: Boolean, val size: Long, /** Seconds since epoch. */ val mtime: Long)

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
class RemoteDeleteException(val path: String, cause: Exception) : Exception("Can't delete $path: ${cause.message}", cause)

/** FT-10: items cut or copied in the file browser, waiting to be pasted into another folder. */
data class RemoteClipboard(val entries: List<RemoteEntry>, val cut: Boolean) {
    val paths: Set<String> = entries.mapTo(HashSet()) { it.path }
}

/**
 * One session tab's connection to a host: SSH ([SshConnection]) or FTP ([app.shellscout.ftp.FtpConnection]).
 * Only SSH has a terminal; both have files.
 */
interface RemoteConnection {
    val host: HostEntity
    val state: StateFlow<ConnState>
    val isConnected: Boolean
    suspend fun connect()
    suspend fun disconnect(reason: String = "Disconnected")
    fun fs(): RemoteFs
}

/**
 * FT-01, FT-04, ED-01..ED-05: file operations on one connection. Subclasses supply the protocol's
 * primitives; editing, pasting and sharing are built on them here so they behave the same over
 * SFTP and FTP.
 */
abstract class RemoteFs {

    /** Copying runs on the server (cp over SSH); FTP has no copy command. */
    abstract val canCopy: Boolean

    /** Unpacking archives needs a shell on the server. */
    abstract val canExtract: Boolean

    abstract suspend fun home(): String

    abstract suspend fun list(dir: String): List<RemoteEntry>

    /** Null if nothing is at [path]. */
    abstract suspend fun stat(path: String): RemoteStat?

    abstract suspend fun mkdir(path: String)

    abstract suspend fun mkdirs(path: String)

    abstract suspend fun rename(from: String, to: String)

    abstract suspend fun chmod(path: String, permissions: Int)

    /** Deletes a file, or a directory and everything under it. Symlinks are removed, never followed. */
    abstract suspend fun delete(entry: RemoteEntry)

    /** Reads [path] from byte [offset]. Closing the stream ends the read. */
    abstract suspend fun openRead(path: String, offset: Long = 0): InputStream

    /**
     * Writes [path]. With [offset] 0 the file is created or truncated; otherwise it already holds
     * exactly [offset] bytes from an earlier upload and the write continues from there (FT-07).
     */
    abstract suspend fun openWrite(path: String, offset: Long = 0): OutputStream

    /** Called when [rename] fails during a move, e.g. across filesystems. By default the rename's error stands. */
    protected open suspend fun moveOnServer(from: String, to: String, renameError: Throwable): Unit = throw renameError

    protected open suspend fun copyOnServer(from: String, to: String): Unit =
        throw UnsupportedOperationException("This server can't copy files")

    /**
     * Unpacks a .zip or tar archive on the server into a new folder beside it. Returns the folder's
     * path. Only offered when [canExtract].
     */
    open suspend fun extract(entry: RemoteEntry): String =
        throw UnsupportedOperationException("This server can't extract archives")

    /**
     * FT-10: moves or copies [entries] into [destDir]. Nothing is overwritten: an item whose name is
     * taken gets a numbered name ("notes (2).txt"), so copying into the same folder duplicates it.
     * Moving an item to the folder it is already in does nothing. Returns how many items changed.
     */
    suspend fun paste(entries: List<RemoteEntry>, destDir: String, move: Boolean): Int {
        if (!move && !canCopy) throw UnsupportedOperationException("This server can't copy files; download and upload them instead")
        val dest = destDir.trimEnd('/').ifEmpty { "/" }
        entries.firstOrNull { it.isDirectory && !it.isSymlink && (dest == it.path || dest.startsWith(it.path.trimEnd('/') + "/")) }
            ?.let { throw IllegalArgumentException("Can't put ${it.name} inside itself") }
        var done = 0
        for (e in entries) {
            if (move && parent(e.path) == dest) continue
            val target = freeName(dest) { numberedName(e.name, it) }
            if (move) {
                // A rename is instant but only works within one filesystem.
                val renameError = runCatching { rename(e.path, target) }.exceptionOrNull()
                if (renameError != null) moveOnServer(e.path, target, renameError)
            } else {
                copyOnServer(e.path, target)
            }
            done++
        }
        return done
    }

    /** The first of `dir/name(1)`, `dir/name(2)`, … that doesn't exist yet. */
    protected suspend fun freeName(dir: String, name: (Int) -> String): String {
        var i = 1
        while (true) {
            val candidate = join(dir, name(i++))
            if (stat(candidate) == null) return candidate
        }
    }

    suspend fun readText(path: String, maxBytes: Long = DEFAULT_MAX_EDIT_BYTES): EditableFile {
        val attrs = stat(path) ?: throw FileNotFoundException("$path no longer exists")
        if (attrs.size > maxBytes) throw FileTooLargeException(attrs.size)
        val bytes = io { openRead(path).use { it.readBytes() } }
        if (bytes.take(8192).any { it == 0.toByte() }) throw BinaryFileException()
        val text = bytes.toString(Charsets.UTF_8)
        return EditableFile(path, text.replace("\r\n", "\n"), attrs.mtime, attrs.size, crlf = text.contains("\r\n"))
    }

    /**
     * ED-02: refuses to save if the file changed since it was opened, unless [force].
     * ED-04: writes in place with truncate, so the file keeps its owner and permission bits; line
     * endings are restored to what the file had.
     */
    suspend fun writeText(file: EditableFile, newText: String, force: Boolean = false): EditableFile {
        val current = stat(file.path)
        if (!force && current != null && (current.mtime != file.mtime || current.size != file.size)) {
            throw RemoteChangedException()
        }
        val out = if (file.crlf) newText.replace("\n", "\r\n") else newText
        val bytes = out.toByteArray(Charsets.UTF_8)
        io { openWrite(file.path).use { it.write(bytes) } }
        val after = stat(file.path) ?: throw FileNotFoundException("${file.path} vanished after saving")
        return file.copy(text = newText, mtime = after.mtime, size = after.size)
    }

    /** FT-09: fetch a remote file into the app cache so it can be shared with another app. */
    suspend fun downloadToCache(entry: RemoteEntry, cacheDir: File): File = io {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest shared file
        val out = File(dir, entry.name)
        openRead(entry.path).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }

    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** ED-05 default threshold: 5 MB. */
        const val DEFAULT_MAX_EDIT_BYTES = 5L * 1024 * 1024

        private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz", ".tar", ".zip")

        /** The archive's name without its extension, or null if [extract] can't handle it. */
        fun archiveBaseName(name: String): String? =
            ARCHIVE_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) }
                ?.let { name.dropLast(it.length).ifBlank { "extracted" } }

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

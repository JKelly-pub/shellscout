package app.shellscout.ftp

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import app.shellscout.ssh.RemoteDeleteException
import app.shellscout.ssh.RemoteEntry
import app.shellscout.ssh.RemoteFs
import app.shellscout.ssh.RemoteStat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FT-01, FT-04 over FTP. Commands run on the tab's control connection; file contents move on a
 * connection of their own (see [FtpConnection]). FTP can't unpack archives, so the browser doesn't
 * offer that; copies are relayed through the device (see [copyOnServer]).
 */
class FtpFs(private val conn: FtpConnection) : RemoteFs() {

    override val canCopy: Boolean get() = true
    override val canExtract: Boolean get() = false

    override suspend fun copiesThroughDevice(): Boolean = !hasSiteCopy()

    /**
     * Probes once per connection with a path that can't exist. mod_copy answers 550 naming that path
     * ("<path>: No such file or directory"). Servers without it reject the command (500, 502, 504),
     * or, like vsftpd for some logins, answer a bare "550 Permission denied", so a 550 alone proves nothing.
     */
    private suspend fun hasSiteCopy(): Boolean = conn.hasSiteCopy ?: conn.withControl { c ->
        val probe = "/.shellscout-copy-probe-${System.nanoTime()}"
        c.sendSiteCommand("CPFR $probe")
        c.replyCode == 350 || c.replyCode == 550 && c.replyString.orEmpty().contains(probe)
    }.also { conn.hasSiteCopy = it }

    /**
     * With SITE CPFR/CPTO the server copies by itself, folders included. Otherwise (plain FTP has no
     * copy command) each file is read from the server and written straight back on a second
     * connection; nothing is stored on the device, and folders are rebuilt item by item. The browser
     * warns before that, using [copiesThroughDevice].
     */
    override suspend fun copyOnServer(from: String, to: String) {
        if (hasSiteCopy()) return siteCopy(from, to)
        relayCopy(from, to)
    }

    private suspend fun siteCopy(from: String, to: String) = conn.withControl { c ->
        val name = from.substringAfterLast('/')
        c.sendSiteCommand("CPFR $from")
        if (c.replyCode != 350) {
            // The probe was wrong after all: say so rather than quietly sending gigabytes through the device.
            if (c.replyCode in UNKNOWN_COMMAND) {
                conn.hasSiteCopy = false
                throw FtpException("This server can't copy $name itself. Paste again to copy it through this device.")
            }
            throw failure(c, "Can't copy $name")
        }
        if (!c.sendSiteCommand("CPTO $to")) throw failure(c, "Can't copy $name")
    }

    private suspend fun relayCopy(from: String, to: String) {
        val st = stat(from) ?: throw IOException("${from.substringAfterLast('/')} no longer exists")
        if (st.isDirectory) {
            mkdir(to)
            for (child in list(from)) {
                // FTP can't make links, and following a folder link could loop forever.
                if (child.isSymlink && child.isDirectory) throw FtpException("Can't copy the folder link ${child.path}")
                relayCopy(child.path, join(to, child.name))
            }
        } else {
            copyFile(from, to)
        }
    }

    private suspend fun copyFile(from: String, to: String) {
        try {
            io { openRead(from).use { input -> openWrite(to).use { output -> input.copyTo(output, 64 * 1024) } } }
        } catch (e: Throwable) {
            // Don't leave a half-written copy behind, even when cancelled.
            withContext(NonCancellable) { runCatching { conn.withControl { it.deleteFile(to) } } }
            throw e
        }
    }

    override suspend fun home(): String = conn.home

    override suspend fun list(dir: String): List<RemoteEntry> = conn.withControl { c ->
        val files = listing(c, dir)
        files.filter { it.name != "." && it.name != ".." }.map { f ->
            val path = join(dir, f.name)
            RemoteEntry(
                name = f.name,
                path = path,
                // A listing doesn't say what a link points at; one that can be entered is a folder.
                isDirectory = f.isDirectory || f.isSymbolicLink && isDirectory(c, path),
                isSymlink = f.isSymbolicLink,
                size = f.size.coerceAtLeast(0),
                mtime = epochSeconds(f),
                permissions = permissions(f),
            )
        }.sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** MLSD where the server has it (exact times, no parsing guesswork), LIST otherwise. Throws if the server refuses. */
    private fun listing(c: FTPClient, dir: String): List<FTPFile> {
        val files = if (conn.hasMlst) c.mlistDir(dir) else c.listFiles(dir)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) throw failure(c, "Can't list $dir")
        return files.filterNotNull().filter { it.isValid }
    }

    override suspend fun stat(path: String): RemoteStat? = conn.withControl { c -> stat(c, path) }

    private fun stat(c: FTPClient, path: String): RemoteStat? {
        if (conn.hasMlst) {
            return c.mlistFile(path)?.let { RemoteStat(it.isDirectory, it.size.coerceAtLeast(0), epochSeconds(it)) }
        }
        if (isDirectory(c, path)) return RemoteStat(true, 0, 0)
        if (conn.hasSize) {
            // SIZE and MDTM follow links and are exact; a listing's times can be to the minute or the day.
            val size = c.getSize(path)?.trim()?.toLongOrNull() ?: return null
            val mtime = runCatching { c.mdtmInstant(path)?.epochSecond }.getOrNull() ?: 0
            return RemoteStat(false, size, mtime)
        }
        val name = path.trimEnd('/').substringAfterLast('/')
        return c.listFiles(parent(path)).firstOrNull { it?.name == name }
            ?.let { RemoteStat(it.isDirectory, it.size.coerceAtLeast(0), epochSeconds(it)) }
    }

    /** True if [path] can be entered. Leaves the control connection in the login directory. */
    private fun isDirectory(c: FTPClient, path: String): Boolean {
        if (!c.changeWorkingDirectory(path)) return false
        c.changeWorkingDirectory(conn.home)
        return true
    }

    override suspend fun mkdir(path: String) = conn.withControl { c ->
        if (!c.makeDirectory(path)) throw failure(c, "Can't create $path")
    }

    override suspend fun mkdirs(path: String) = conn.withControl { c ->
        var prefix = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            prefix += "/$part"
            if (!isDirectory(c, prefix) && !c.makeDirectory(prefix)) throw failure(c, "Can't create $prefix")
        }
    }

    override suspend fun rename(from: String, to: String) = conn.withControl { c ->
        if (!c.rename(from, to)) throw failure(c, "Can't rename ${from.substringAfterLast('/')}")
    }

    /** SITE CHMOD isn't part of FTP proper, but most Unix servers accept it. */
    override suspend fun chmod(path: String, permissions: Int) = conn.withControl { c ->
        if (!c.sendSiteCommand("CHMOD ${Integer.toOctalString(permissions)} $path")) {
            throw failure(c, "The server didn't change the permissions")
        }
    }

    override suspend fun delete(entry: RemoteEntry) = conn.withControl { c ->
        deleteRecursive(c, entry.path, entry.isDirectory && !entry.isSymlink)
    }

    private fun deleteRecursive(c: FTPClient, path: String, isDir: Boolean) {
        if (isDir) {
            for (child in listing(c, path).filter { it.name != "." && it.name != ".." }) {
                deleteRecursive(c, join(path, child.name), child.isDirectory)
            }
            if (!c.removeDirectory(path)) throw RemoteDeleteException(path, failure(c, "Server refused"))
        } else if (!c.deleteFile(path)) {
            // Name the item that failed: in a big tree "Permission denied" alone says nothing.
            throw RemoteDeleteException(path, failure(c, "Server refused"))
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        val c = conn.openTransferClient()
        return io {
            try {
                c.restartOffset = offset
                val input = c.retrieveFileStream(path) ?: throw failure(c, "Can't read ${path.substringAfterLast('/')}")
                object : FilterInputStream(input) {
                    private var ended = false

                    override fun read(): Int = super.read().also { if (it < 0) ended = true }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it < 0) ended = true }

                    override fun close() {
                        try {
                            super.close()
                            // Stopped early (cancelled): the server's reply would only report the abort.
                            if (ended && !c.completePendingCommand()) throw failure(c, "Download failed")
                        } finally {
                            conn.release(c)
                        }
                    }
                }
            } catch (e: Throwable) {
                conn.release(c)
                throw e
            }
        }
    }

    override suspend fun openWrite(path: String, offset: Long): OutputStream {
        val c = conn.openTransferClient()
        return io {
            try {
                // APPE adds to the end, which is the offset by the contract of openWrite; more servers have it than REST + STOR.
                val output = (if (offset > 0) c.appendFileStream(path) else c.storeFileStream(path))
                    ?: throw failure(c, "Can't write ${path.substringAfterLast('/')}")
                object : FilterOutputStream(output) {
                    // FilterOutputStream writes byte by byte unless told otherwise.
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                    override fun close() {
                        try {
                            super.close()
                            if (!c.completePendingCommand()) throw failure(c, "Upload failed")
                        } finally {
                            conn.release(c)
                        }
                    }
                }
            } catch (e: Throwable) {
                conn.release(c)
                throw e
            }
        }
    }

    private companion object {
        /** Replies meaning the server doesn't know the command at all. */
        val UNKNOWN_COMMAND = setOf(500, 502, 504)

        fun failure(c: FTPClient, what: String): IOException = FtpException("$what: ${c.replyString?.trim().orEmpty()}")

        fun epochSeconds(f: FTPFile): Long = f.timestamp?.timeInMillis?.div(1000) ?: 0

        /** rwx for user, group and other, or -1 if the listing has none (MLSD without UNIX.mode, Windows servers). */
        fun permissions(f: FTPFile): Int {
            var bits = 0
            for ((i, access) in listOf(FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS).withIndex()) {
                val shift = 6 - 3 * i
                if (f.hasPermission(access, FTPFile.READ_PERMISSION)) bits = bits or (4 shl shift)
                if (f.hasPermission(access, FTPFile.WRITE_PERMISSION)) bits = bits or (2 shl shift)
                if (f.hasPermission(access, FTPFile.EXECUTE_PERMISSION)) bits = bits or (1 shl shift)
            }
            return if (bits == 0) -1 else bits
        }
    }
}

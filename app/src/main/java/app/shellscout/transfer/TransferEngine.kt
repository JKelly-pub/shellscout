package app.shellscout.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.shellscout.data.TransferDao
import app.shellscout.data.TransferDirection
import app.shellscout.data.TransferEntity
import app.shellscout.data.TransferStatus
import app.shellscout.ssh.ConflictAnswer
import app.shellscout.ssh.ConflictChoice
import app.shellscout.ssh.Prompt
import app.shellscout.ssh.PromptBroker
import app.shellscout.ssh.RemoteConnection
import app.shellscout.ssh.RemoteEntry
import app.shellscout.ssh.RemoteFs
import app.shellscout.ssh.SshConnection
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Live, in-memory progress for a running transfer (FT-05). */
data class LiveProgress(val doneBytes: Long, val totalBytes: Long, val bytesPerSecond: Long) {
    val etaSeconds: Long? get() =
        if (bytesPerSecond > 0 && totalBytes > 0) (totalBytes - doneBytes) / bytesPerSecond else null
}

/**
 * FT-03..FT-08: queued, resumable transfers over SFTP or FTP. Directories are expanded into one queue entry per
 * file when enqueued. Runs in the app scope, so transfers survive screen changes and continue while
 * the foreground service keeps the process alive (FT-06).
 */
class TransferEngine(
    private val context: Context,
    private val dao: TransferDao,
    private val prompts: PromptBroker,
    private val scope: CoroutineScope,
    /** Finds a live connection to the host, for retries. */
    private val connectionForHost: (Long) -> RemoteConnection?,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val parallel = Semaphore(2)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val connections = ConcurrentHashMap<Long, RemoteConnection>()
    /** Per-batch "apply to all" conflict answers. */
    private val batchAnswers = ConcurrentHashMap<String, ConflictAnswer>()
    private val batchPromptLock = Mutex()

    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    val transfers = dao.observeAll()

    init {
        scope.launch { dao.failInterrupted() } // RP-04
    }

    // ---------------------------------------------------------------- enqueue

    /** Upload local files (content:// documents) into [remoteDir]. */
    fun uploadFiles(connection: RemoteConnection, uris: List<Uri>, remoteDir: String) = scope.launch {
        val batch = newBatch()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "upload"
            enqueue(connection, batch, TransferEntity(
                hostId = connection.host.id, direction = TransferDirection.UPLOAD, localUri = uri.toString(),
                remotePath = RemoteFs.join(remoteDir, name), displayName = name, totalBytes = size(uri),
            ))
        }
    }

    /** Upload a local SAF directory tree (or a single DocumentFile) into [remoteDir], recursively. */
    fun uploadDocument(connection: RemoteConnection, doc: DocumentFile, remoteDir: String) = scope.launch {
        val batch = newBatch()
        val fs = connection.fs()
        suspend fun walk(d: DocumentFile, dest: String) {
            val name = d.name ?: "item"
            if (d.isDirectory) {
                val target = RemoteFs.join(dest, name)
                runCatching { fs.mkdirs(target) }
                d.listFiles().forEach { walk(it, target) }
            } else {
                enqueue(connection, batch, TransferEntity(
                    hostId = connection.host.id, direction = TransferDirection.UPLOAD, localUri = d.uri.toString(),
                    remotePath = RemoteFs.join(dest, name), displayName = name, totalBytes = d.length(),
                ))
            }
        }
        withContext(Dispatchers.IO) { walk(doc, remoteDir) }
    }

    /** Download a remote file or directory (recursively) into a local SAF directory tree. */
    fun download(connection: RemoteConnection, entries: List<RemoteEntry>, localDir: DocumentFile) = scope.launch {
        val batch = newBatch()
        val fs = connection.fs()
        suspend fun walk(e: RemoteEntry, dir: DocumentFile) {
            if (e.isDirectory && !e.isSymlink) {
                val sub = dir.findFile(e.name)?.takeIf { it.isDirectory } ?: dir.createDirectory(e.name) ?: return
                fs.list(e.path).forEach { walk(it, sub) }
            } else if (!e.isDirectory) {
                enqueue(connection, batch, TransferEntity(
                    hostId = connection.host.id, direction = TransferDirection.DOWNLOAD,
                    localUri = dir.uri.toString(), remotePath = e.path, displayName = e.name, totalBytes = e.size,
                ))
            }
        }
        withContext(Dispatchers.IO) { entries.forEach { walk(it, localDir) } }
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        scope.launch { dao.setStatus(id, TransferStatus.CANCELLED, null) }
    }

    fun retry(id: Long) = scope.launch {
        val t = dao.get(id) ?: return@launch
        val conn = connections[id]?.takeIf { it.isConnected } ?: connectionForHost(t.hostId)
        if (conn == null) {
            dao.setStatus(id, TransferStatus.FAILED, "Open a session to this host, then retry")
            return@launch
        }
        // A retry resumes where it stopped rather than asking again (FT-07).
        start(conn, t.copy(status = TransferStatus.QUEUED, error = null), batch = "retry-$id", resumeByDefault = true)
    }

    fun clearFinished() = scope.launch { dao.clearFinished() }

    // ---------------------------------------------------------------- execution

    private fun newBatch() = java.util.UUID.randomUUID().toString()

    private suspend fun enqueue(connection: RemoteConnection, batch: String, t: TransferEntity) {
        val id = dao.insert(t)
        start(connection, t.copy(id = id), batch, resumeByDefault = false)
    }

    private suspend fun start(connection: RemoteConnection, t: TransferEntity, batch: String, resumeByDefault: Boolean) {
        dao.update(t)
        connections[t.id] = connection
        jobs[t.id] = scope.launch(Dispatchers.IO) {
            parallel.withPermit {
                _activeCount.update { it + 1 }
                dao.setStatus(t.id, TransferStatus.RUNNING, null)
                try {
                    when (t.direction) {
                        TransferDirection.UPLOAD -> upload(connection, t, batch, resumeByDefault)
                        TransferDirection.DOWNLOAD -> download(connection, t, batch, resumeByDefault)
                    }
                } catch (e: CancellationException) {
                    dao.setStatus(t.id, TransferStatus.CANCELLED, null)
                    throw e
                } catch (e: Exception) {
                    dao.setStatus(t.id, TransferStatus.FAILED, SshConnection.describe(e))
                } finally {
                    _live.update { it - t.id }
                    _activeCount.update { it - 1 }
                    jobs.remove(t.id)
                }
            }
        }
    }

    private suspend fun upload(conn: RemoteConnection, t: TransferEntity, batch: String, resumeByDefault: Boolean) {
        val fs = conn.fs()
        val uri = Uri.parse(t.localUri)
        val total = if (t.totalBytes >= 0) t.totalBytes else size(uri)
        var remotePath = t.remotePath
        var offset = 0L

        val existing = fs.stat(remotePath)
        if (existing != null && !existing.isDirectory) {
            val canResume = existing.size in 1 until total
            val choice = if (resumeByDefault && canResume) ConflictChoice.RESUME
            else conflict(batch, t.displayName, existing.size, total, canResume)
            when (choice) {
                ConflictChoice.OVERWRITE -> offset = 0
                ConflictChoice.RESUME -> offset = if (canResume) existing.size else 0
                ConflictChoice.RENAME -> remotePath = uniqueRemoteName(conn, remotePath)
                ConflictChoice.SKIP -> return finish(t.id, TransferStatus.CANCELLED, "Skipped")
                ConflictChoice.CANCEL -> return finish(t.id, TransferStatus.CANCELLED, "Cancelled")
            }
        }

        val input = resolver.openInputStream(uri) ?: error("Cannot open local file")
        input.use { ins ->
            skipFully(ins, offset)
            fs.openWrite(remotePath, offset).use { out ->
                copy(t.id, ins, out, offset, total)
            }
        }
        finish(t.id, TransferStatus.DONE, null)
    }

    private suspend fun download(conn: RemoteConnection, t: TransferEntity, batch: String, resumeByDefault: Boolean) {
        val fs = conn.fs()
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(t.localUri))
            ?: DocumentFile.fromSingleUri(context, Uri.parse(t.localUri))
            ?: error("Local folder is no longer available")
        val total = fs.stat(t.remotePath)?.size ?: error("${t.displayName} is no longer on the server")
        var target = dir.findFile(t.displayName)
        var offset = 0L
        var mode = "wt"

        if (target != null && target.isFile) {
            val canResume = target.length() in 1 until total
            val choice = if (resumeByDefault && canResume) ConflictChoice.RESUME
            else conflict(batch, t.displayName, target.length(), total, canResume)
            when (choice) {
                ConflictChoice.OVERWRITE -> mode = "wt"
                ConflictChoice.RESUME -> if (canResume) { offset = target.length(); mode = "wa" }
                ConflictChoice.RENAME -> target = dir.createFile(mimeFor(t.displayName), uniqueLocalName(dir, t.displayName))
                ConflictChoice.SKIP -> return finish(t.id, TransferStatus.CANCELLED, "Skipped")
                ConflictChoice.CANCEL -> return finish(t.id, TransferStatus.CANCELLED, "Cancelled")
            }
        } else {
            target = dir.createFile(mimeFor(t.displayName), t.displayName)
        }
        val file = target ?: error("Could not create the local file")

        val output = resolver.openOutputStream(file.uri, mode) ?: error("Cannot write local file")
        output.use { out ->
            fs.openRead(t.remotePath, offset).use { ins ->
                copy(t.id, ins, out, offset, total)
            }
        }
        finish(t.id, TransferStatus.DONE, null)
    }

    private suspend fun copy(id: Long, input: InputStream, output: OutputStream, startAt: Long, total: Long) {
        val buf = ByteArray(64 * 1024)
        var done = startAt
        var windowStart = SystemClock.elapsedRealtime()
        var windowBytes = 0L
        var speed = 0L
        var lastPersist = 0L
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            windowBytes += n
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 500) {
                speed = windowBytes * 1000 / (now - windowStart)
                windowStart = now; windowBytes = 0
                _live.update { it + (id to LiveProgress(done, total, speed)) }
            }
            if (now - lastPersist >= 1000) {
                dao.progress(id, done, total)
                lastPersist = now
            }
        }
        output.flush()
        dao.progress(id, done, total)
        // A connection that drops can look like the end of the file; don't report a short copy as done.
        if (total >= 0 && done < total) error("Transfer stopped early ($done of $total bytes)")
    }

    private suspend fun finish(id: Long, status: TransferStatus, error: String?) {
        dao.setStatus(id, status, error)
    }

    /** FT-08: asks once per batch when the user ticks "apply to all". */
    private suspend fun conflict(batch: String, name: String, existing: Long, incoming: Long, canResume: Boolean): ConflictChoice =
        batchPromptLock.withLock {
            batchAnswers[batch]?.let { saved ->
                return@withLock if (saved.choice == ConflictChoice.RESUME && !canResume) ConflictChoice.OVERWRITE else saved.choice
            }
            val answer = prompts.ask(Prompt.FileConflict(name, existing, incoming, canResume))
            if (answer.applyToAll) batchAnswers[batch] = answer
            answer.choice
        }

    private suspend fun uniqueRemoteName(conn: RemoteConnection, path: String): String {
        val fs = conn.fs()
        val dir = RemoteFs.parent(path)
        val name = path.substringAfterLast('/')
        var i = 1
        while (true) {
            val candidate = RemoteFs.join(dir, numbered(name, i++))
            if (fs.stat(candidate) == null) return candidate
        }
    }

    private fun uniqueLocalName(dir: DocumentFile, name: String): String {
        var i = 1
        while (true) {
            val candidate = numbered(name, i++)
            if (dir.findFile(candidate) == null) return candidate
        }
    }

    private fun numbered(name: String, i: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) error("Local file is shorter than the remote copy")
                remaining--
            } else remaining -= skipped
        }
    }

    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun size(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)
}

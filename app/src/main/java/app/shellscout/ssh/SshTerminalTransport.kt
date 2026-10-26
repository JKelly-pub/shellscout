package app.shellscout.ssh

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException

/**
 * Bridges a Termux [TerminalSession] to an SSH shell channel on [connection].
 * Remote output is pushed into the emulator; keystrokes are written to the channel.
 */
class SshTerminalTransport(
    private val connection: SshConnection,
    private val scope: CoroutineScope,
    private val startDirectory: String?,
) : TerminalSession.Transport {

    private val shell = CompletableDeferred<Session.Shell>()

    override fun onStart(session: TerminalSession, columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val sh = connection.openShell(columns, rows)
                shell.complete(sh)
                startDirectory?.takeIf { it.isNotBlank() }?.let { dir ->
                    // HM-05: start directory. Quoted for POSIX shells; clears the echoed command.
                    val cmd = "cd -- '" + dir.replace("'", "'\\''") + "' && clear\n"
                    sh.outputStream.write(cmd.toByteArray())
                    sh.outputStream.flush()
                }
                val input = sh.inputStream
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!session.feedFromRemote(buf, 0, n)) break
                }
                session.onRemoteClosed(0, "Session closed by server")
            } catch (e: Throwable) {
                shell.completeExceptionally(e)
                session.onRemoteClosed(0, "Connection lost: " + SshConnection.describe(e))
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        // Called on the session's dedicated writer thread, so blocking until the shell is ready is fine.
        val sh = try {
            runBlocking { shell.await() }
        } catch (e: Throwable) {
            throw IOException("Shell not available", e)
        }
        sh.outputStream.write(data, offset, count)
        sh.outputStream.flush()
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // TE-07: tell the remote PTY about the new size.
        scope.launch(Dispatchers.IO) {
            runCatching { shell.await().changeWindowDimensions(columns, rows, 0, 0) }
        }
    }
}

package app.shellscout.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.shellscout.data.HostEntity
import app.shellscout.settings.AppSettings
import app.shellscout.ssh.ConnState
import app.shellscout.ssh.RemoteClipboard
import app.shellscout.ssh.RemoteFs
import app.shellscout.ssh.SshConnection
import app.shellscout.ssh.SshDeps
import app.shellscout.ssh.SshTerminalTransport
import java.util.UUID

/** What a tab shows: the terminal, the file browser, or both side by side (tablet). */
enum class SessionView { TERMINAL, FILES, SPLIT }

/**
 * One tab (SE-01). The terminal session is replaced on reconnect, so the UI observes [terminal].
 * [connection] is likewise replaced; observe [connectionFlow] for state.
 */
class SessionTab(val id: String, val host: HostEntity, connection: SshConnection) {
    val connectionFlow = MutableStateFlow(connection)
    val connection: SshConnection get() = connectionFlow.value
    val terminal = MutableStateFlow<TerminalSession?>(null)
    val title = MutableStateFlow(host.name)
    val view = MutableStateFlow(SessionView.TERMINAL)
    /** Last directory shown in the SFTP browser, kept across view switches. */
    val remoteDir = MutableStateFlow<String?>(null)
    /** Files cut or copied in the SFTP browser, kept while the user moves to the folder to paste into (FT-10). */
    val fileClipboard = MutableStateFlow<RemoteClipboard?>(null)
    /** Remote file open in the editor for this tab, if any (ED-01). */
    val editorPath = MutableStateFlow<String?>(null)
    val fs: RemoteFs get() = RemoteFs(connection)
}

/**
 * Owns every live connection, independent of any screen (SE-01..SE-05, RP-03). The UI observes
 * [tabs]; [app.shellscout.service.SessionService] keeps the process alive while any tab is open.
 */
class SessionManager(
    private val context: Context,
    private val deps: SshDeps,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** FT-09: files shared into the app, waiting for the user to pick a remote folder. */
    private val _pendingShares = MutableStateFlow<List<Uri>>(emptyList())
    val pendingShares: StateFlow<List<Uri>> = _pendingShares.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private var clipboardClearJob: Job? = null

    fun connectionForHost(hostId: Long): SshConnection? =
        _tabs.value.firstOrNull { it.host.id == hostId && it.connection.isConnected }?.connection

    fun select(tabId: String) {
        _activeTabId.value = tabId
    }

    fun selectNext(delta: Int) {
        val list = _tabs.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == _activeTabId.value }.coerceAtLeast(0)
        _activeTabId.value = list[(i + delta + list.size) % list.size].id
    }

    /** Opens a new tab and connects. Several tabs may target the same host (SE-01). */
    fun open(host: HostEntity): SessionTab {
        val tab = SessionTab(UUID.randomUUID().toString(), host, SshConnection(host, deps))
        _tabs.update { it + tab }
        _activeTabId.value = tab.id
        connect(tab)
        return tab
    }

    fun reconnect(tab: SessionTab) {
        val old = tab.connection
        scope.launch { old.disconnect() }
        tab.terminal.value?.finishIfRunning()
        tab.connectionFlow.value = SshConnection(tab.host, deps)
        connect(tab)
    }

    private fun connect(tab: SessionTab) {
        tab.terminal.value = null
        scope.launch {
            try {
                tab.connection.connect()
                val transport = SshTerminalTransport(tab.connection, scope, tab.host.startDirectory)
                tab.terminal.value = TerminalSession(transport, settings.current.scrollbackLines, sessionClient(tab))
            } catch (e: Exception) {
                post("${tab.host.name}: ${SshConnection.describe(e)}")
            }
        }
    }

    fun close(tab: SessionTab) {
        tab.terminal.value?.finishIfRunning()
        scope.launch { tab.connection.disconnect() }
        _tabs.update { list -> list.filterNot { it.id == tab.id } }
        if (_activeTabId.value == tab.id) _activeTabId.value = _tabs.value.lastOrNull()?.id
    }

    fun closeAll() {
        _tabs.value.forEach { close(it) }
    }

    fun addPendingShares(uris: List<Uri>) {
        if (uris.isNotEmpty()) _pendingShares.value = uris
    }

    fun clearPendingShares() {
        _pendingShares.value = emptyList()
    }

    fun post(message: String) {
        _messages.value = message
    }

    fun consumeMessage() {
        _messages.value = null
    }

    fun isLive(tab: SessionTab): Boolean = tab.connection.state.value == ConnState.Connected

    /**
     * Default callbacks for a session with no view attached. The terminal pane replaces this with a
     * client that also redraws the view; copy/paste and bell behave the same either way.
     */
    fun sessionClient(tab: SessionTab, redraw: () -> Unit = {}): TerminalSessionClient =
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) = redraw()
            override fun onTitleChanged(changedSession: TerminalSession) {
                tab.title.value = changedSession.title?.takeIf { it.isNotBlank() } ?: tab.host.name
            }
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) = copyToClipboard(text)
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                pasteText()?.let { session.emulator?.paste(it) }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

    /** SC-09/SC-10: copies are marked sensitive (hidden from clipboard previews) and auto-cleared. */
    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("terminal", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        val seconds = settings.current.clipboardClearSeconds
        clipboardClearJob?.cancel()
        if (seconds > 0) {
            clipboardClearJob = scope.launch {
                delay(seconds * 1000L)
                withContext(Dispatchers.Main) {
                    val current = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (current == text) {
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
    }

    fun pasteText(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }
}

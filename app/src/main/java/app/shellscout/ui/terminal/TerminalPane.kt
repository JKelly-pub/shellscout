package app.shellscout.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import app.shellscout.session.SessionTab
import app.shellscout.settings.TerminalTheme
import app.shellscout.ssh.ConnState
import app.shellscout.ui.common.LocalContainer
import java.util.Properties

/** Shortcut actions the terminal forwards to the app (UI-09). */
interface TerminalShortcuts {
    fun newTab()
    fun closeTab()
    fun nextTab(delta: Int)
    fun toggleFiles()
    fun openSnippets()
}

/**
 * TE-01..TE-08: the Termux terminal view hosted in Compose, plus the extra-keys bar and the
 * disconnected banner (SE-04).
 */
@Composable
fun TerminalPane(tab: SessionTab, shortcuts: TerminalShortcuts, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsState()
    val session by tab.terminal.collectAsState()
    val connection by tab.connectionFlow.collectAsState()
    val state by connection.state.collectAsState()

    val modifiers = remember { StickyModifiers() }
    val holder = remember { ViewHolder() }
    val focusManager = LocalFocusManager.current

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(themeBackground(settings.terminalTheme)))) {
            val current = session
            if (current != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Terminal for ${tab.host.name}" },
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            // setTextSize creates the renderer; setTypeface needs it to exist.
                            setTextSize(spToPx(ctx, settings.fontSizeSp))
                            setTypeface(Typeface.MONOSPACE)
                            holder.fontSp = settings.fontSizeSp
                            keepScreenOn = true
                            holder.view = this
                        }
                    },
                    update = { view ->
                        view.setTerminalViewClient(
                            ViewClient(
                                view, modifiers, shortcuts,
                                clearComposeFocus = { focusManager.clearFocus(force = true) },
                                currentFontSp = { container.settings.current.fontSizeSp },
                            ) { newSize ->
                                container.settings.update { it.copy(fontSizeSp = newSize) }
                            },
                        )
                        if (view.currentSession !== current) {
                            current.updateTerminalSessionClient(
                                container.sessions.sessionClient(tab) { view.onScreenUpdated() },
                            )
                            view.attachSession(current)
                            view.requestFocus()
                        }
                        if (holder.fontSp != settings.fontSizeSp) {
                            // Re-creating the renderer re-measures the grid and resizes the remote PTY (TE-07).
                            view.setTextSize(spToPx(view.context, settings.fontSizeSp))
                            holder.fontSp = settings.fontSizeSp
                        }
                    },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (val s = state) {
                        is ConnState.Disconnected -> Text(s.reason ?: "Disconnected", color = Color.LightGray)
                        else -> CircularProgressIndicator()
                    }
                }
            }

            val s = state
            if (s is ConnState.Disconnected) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Text("Disconnected: ${s.reason ?: "connection lost"}", color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { container.sessions.reconnect(tab) }) { Text("Reconnect") }
                    }
                }
            }
        }
        ExtraKeysBar(
            keys = settings.extraKeys,
            modifiers = modifiers,
            onKey = { key -> holder.view?.let { sendExtraKey(it, key, modifiers) } },
            onSnippets = shortcuts::openSnippets,
        )
    }

    // TE-08: apply the colour theme to the shared scheme and refresh the emulator.
    LaunchedEffect(settings.terminalTheme, session) {
        applyTheme(settings.terminalTheme)
        session?.emulator?.mColors?.reset()
        holder.view?.onScreenUpdated()
    }

    DisposableEffect(Unit) { onDispose { holder.view = null } }
}

private class ViewHolder {
    var view: TerminalView? = null
    var fontSp: Int = 0
}

/** CTRL and ALT on the extra-keys bar apply to the next key only. */
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    fun consume(): Int {
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        ctrl = false; alt = false
        return mod
    }
}

@Composable
private fun ExtraKeysBar(keys: List<String>, modifiers: StickyModifiers, onKey: (String) -> Unit, onSnippets: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val barColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    Surface(tonalElevation = 2.dp) { Box {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // SN-05: snippets are one tap from the terminal.
            TextButton(onClick = onSnippets, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ShortText, contentDescription = "Snippets")
            }
            keys.forEach { key ->
                val active = (key == "CTRL" && modifiers.ctrl) || (key == "ALT" && modifiers.alt)
                if (active) {
                    FilledTonalButton(onClick = { onKey(key) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)) {
                        Text(label(key), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = { onKey(key) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)) {
                        Text(label(key), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        // In a narrow pane (split view, large text) most keys are off-screen; show which way there are more.
        val page = scroll.viewportSize * 0.8f
        Box(Modifier.matchParentSize()) {
            if (scroll.canScrollBackward) {
                ScrollHint(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous keys", barColor, fadeToStart = false,
                    Modifier.align(Alignment.CenterStart)) { scope.launch { scroll.animateScrollBy(-page) } }
            }
            if (scroll.canScrollForward) {
                ScrollHint(Icons.AutoMirrored.Filled.KeyboardArrowRight, "More keys", barColor, fadeToStart = true,
                    Modifier.align(Alignment.CenterEnd)) { scope.launch { scroll.animateScrollBy(page) } }
            }
        }
    } }
}

/** An arrow over the end of the keys bar that fades into it, so the key beneath still shows it continues. */
@Composable
private fun ScrollHint(icon: ImageVector, description: String, color: Color, fadeToStart: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fade = if (fadeToStart) listOf(Color.Transparent, color, color) else listOf(color, color, Color.Transparent)
    Box(
        modifier.fillMaxHeight().width(40.dp)
            .background(Brush.horizontalGradient(fade))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
    }
}

private fun label(key: String) = when (key) {
    "LEFT" -> "←"; "RIGHT" -> "→"; "UP" -> "↑"; "DOWN" -> "↓"
    "PGUP" -> "PgUp"; "PGDN" -> "PgDn"; "HOME" -> "Home"; "END" -> "End"
    else -> key
}

private val SPECIAL_KEYS = mapOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "INS" to KeyEvent.KEYCODE_INSERT, "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
    "F1" to KeyEvent.KEYCODE_F1, "F2" to KeyEvent.KEYCODE_F2, "F3" to KeyEvent.KEYCODE_F3,
    "F4" to KeyEvent.KEYCODE_F4, "F5" to KeyEvent.KEYCODE_F5, "F6" to KeyEvent.KEYCODE_F6,
    "F7" to KeyEvent.KEYCODE_F7, "F8" to KeyEvent.KEYCODE_F8, "F9" to KeyEvent.KEYCODE_F9,
    "F10" to KeyEvent.KEYCODE_F10, "F11" to KeyEvent.KEYCODE_F11, "F12" to KeyEvent.KEYCODE_F12,
)

private fun sendExtraKey(view: TerminalView, key: String, modifiers: StickyModifiers) {
    when (key) {
        "CTRL" -> { modifiers.ctrl = !modifiers.ctrl; return }
        "ALT" -> { modifiers.alt = !modifiers.alt; return }
    }
    if (view.currentSession == null) return
    val code = SPECIAL_KEYS[key]
    if (code != null) {
        view.handleKeyCode(code, modifiers.consume())
    } else {
        val mod = modifiers.consume()
        var i = 0
        while (i < key.length) {
            val cp = key.codePointAt(i)
            view.inputCodePoint(cp, mod and KeyHandler.KEYMOD_CTRL != 0, mod and KeyHandler.KEYMOD_ALT != 0)
            i += Character.charCount(cp)
        }
    }
}

private class ViewClient(
    private val view: TerminalView,
    private val modifiers: StickyModifiers,
    private val shortcuts: TerminalShortcuts,
    private val clearComposeFocus: () -> Unit,
    private val currentFontSp: () -> Int,
    private val onFontSizeChange: (Int) -> Unit,
) : TerminalViewClient {

    private var scaleAccumulator = 1f

    /** TE-03: pinch to zoom changes the font size in 1sp steps. */
    override fun onScale(scale: Float): Float {
        scaleAccumulator = scale
        if (scale < 0.9f || scale > 1.1f) {
            val current = currentFontSp()
            val next = (current + if (scale > 1f) 1 else -1).coerceIn(8, 32)
            onFontSizeChange(next)
            scaleAccumulator = 1f
            return 1f
        }
        return scaleAccumulator
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // In split view a Compose text field (the editor) can hold focus and the keyboard's input
        // connection; requestFocus() on this view alone leaves typing going to the editor.
        clearComposeFocus()
        view.requestFocus()
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        imm?.restartInput(view)
        imm?.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean {
        // UI-09: app shortcuts with a hardware keyboard. Ctrl+Shift keeps plain Ctrl combos for the shell.
        if (e.isCtrlPressed && e.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_T -> { shortcuts.newTab(); return true }
                KeyEvent.KEYCODE_W -> { shortcuts.closeTab(); return true }
                KeyEvent.KEYCODE_F -> { shortcuts.toggleFiles(); return true }
                KeyEvent.KEYCODE_S -> { shortcuts.openSnippets(); return true }
                KeyEvent.KEYCODE_C -> { view.storedSelectedTextOrNull()?.let { session?.onCopyTextToClipboard(it) }; return true }
                KeyEvent.KEYCODE_V -> { session?.onPasteTextFromClipboard(); return true }
            }
        }
        if (e.isCtrlPressed && keyCode == KeyEvent.KEYCODE_TAB) {
            shortcuts.nextTab(if (e.isShiftPressed) -1 else 1); return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false

    // Soft-keyboard input picks up the sticky modifiers from the extra-keys bar.
    override fun readControlKey(): Boolean = modifiers.ctrl.also { if (it) modifiers.ctrl = false }
    override fun readAltKey(): Boolean = modifiers.alt.also { if (it) modifiers.alt = false }
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {
        view.setTerminalCursorBlinkerRate(0)
    }

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

private fun TerminalView.storedSelectedTextOrNull(): String? =
    if (isSelectingText) selectedText?.takeIf { it.isNotEmpty() } else null

/**
 * Terminal text follows the system font scale only up to [MAX_TERMINAL_FONT_SCALE]. The grid has to
 * fit 80 columns to be usable, and at the largest system setting (1.7x and up) a 14sp font leaves
 * about 35. Pinch-to-zoom and the font size setting still make it as large as anyone wants.
 */
private fun spToPx(ctx: Context, sp: Int): Int {
    val metrics = ctx.resources.displayMetrics
    val fontScale = ctx.resources.configuration.fontScale.coerceAtMost(MAX_TERMINAL_FONT_SCALE)
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp * fontScale, metrics).toInt()
}

private const val MAX_TERMINAL_FONT_SCALE = 1.3f

private fun themeBackground(theme: TerminalTheme): Long = when (theme) {
    TerminalTheme.DARK -> 0xFF101418
    TerminalTheme.LIGHT -> 0xFFFAFAF7
    TerminalTheme.HIGH_CONTRAST -> 0xFF000000
}

/** TE-08: light, dark and high-contrast palettes. */
private fun applyTheme(theme: TerminalTheme) {
    val p = Properties()
    when (theme) {
        TerminalTheme.DARK -> {
            p["background"] = "#101418"; p["foreground"] = "#D8DEE4"; p["cursor"] = "#7FD1AE"
        }
        TerminalTheme.LIGHT -> {
            p["background"] = "#FAFAF7"; p["foreground"] = "#1E2328"; p["cursor"] = "#2E7D6B"
            // Darken the bright ANSI colours so they stay readable on a light background.
            p["color3"] = "#8A6A00"; p["color11"] = "#7A5C00"; p["color7"] = "#5C6670"; p["color15"] = "#3A424A"
        }
        TerminalTheme.HIGH_CONTRAST -> {
            p["background"] = "#000000"; p["foreground"] = "#FFFFFF"; p["cursor"] = "#FFFF00"
        }
    }
    TerminalColors.COLOR_SCHEME.updateWith(p)
}

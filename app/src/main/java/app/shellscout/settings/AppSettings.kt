package app.shellscout.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** SC-02: on by default. */
    val appLockEnabled: Boolean = true,
    /** False until the user has answered the first-run app lock question; the lock stays off until then. */
    val appLockChosen: Boolean = true,
    /** Re-lock after the app has been in the background this long. */
    val lockTimeoutSeconds: Int = 60,
    /** SC-08: block screenshots and recents thumbnails. */
    val secureScreen: Boolean = false,
    /** SC-10: 0 = never clear. */
    val clipboardClearSeconds: Int = 60,
    /** SE-04 default keep-alive interval. */
    val keepAliveSeconds: Int = 30,
    /** TE-03 */
    val fontSizeSp: Int = 14,
    /** TE-04: scrollback lines (Termux caps transcripts at 50,000). */
    val scrollbackLines: Int = 10_000,
    /** TE-08 */
    val terminalTheme: TerminalTheme = TerminalTheme.DARK,
    /** TE-05: keys on the extra-keys bar, in order. */
    val extraKeys: List<String> = DEFAULT_EXTRA_KEYS,
)

enum class TerminalTheme { DARK, LIGHT, HIGH_CONTRAST }

val DEFAULT_EXTRA_KEYS = listOf(
    "ESC", "CTRL", "ALT", "TAB", "-", "/", "|", "~",
    "LEFT", "DOWN", "UP", "RIGHT", "HOME", "END", "PGUP", "PGDN",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
)

/** Small, synchronous preferences store exposed as a StateFlow. Contains no secrets. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val isFreshInstall = context.packageManager.getPackageInfo(context.packageName, 0)
        .let { it.firstInstallTime == it.lastUpdateTime }
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        prefs.edit()
            .putBoolean("appLock", next.appLockEnabled)
            .putBoolean("appLockChosen", next.appLockChosen)
            .putInt("lockTimeout", next.lockTimeoutSeconds)
            .putBoolean("secureScreen", next.secureScreen)
            .putInt("clipClear", next.clipboardClearSeconds)
            .putInt("keepAlive", next.keepAliveSeconds)
            .putInt("fontSize", next.fontSizeSp)
            .putInt("scrollback", next.scrollbackLines)
            .putString("termTheme", next.terminalTheme.name)
            .putString("extraKeys", next.extraKeys.joinToString(","))
            .apply()
        state.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            appLockEnabled = prefs.getBoolean("appLock", d.appLockEnabled),
            // Only a fresh install asks. An update keeps whatever the lock was doing, so the
            // question can never appear on a phone where the lock was already on.
            appLockChosen = prefs.getBoolean("appLockChosen", !isFreshInstall),
            lockTimeoutSeconds = prefs.getInt("lockTimeout", d.lockTimeoutSeconds),
            secureScreen = prefs.getBoolean("secureScreen", d.secureScreen),
            clipboardClearSeconds = prefs.getInt("clipClear", d.clipboardClearSeconds),
            keepAliveSeconds = prefs.getInt("keepAlive", d.keepAliveSeconds),
            fontSizeSp = prefs.getInt("fontSize", d.fontSizeSp),
            scrollbackLines = prefs.getInt("scrollback", d.scrollbackLines),
            terminalTheme = runCatching { TerminalTheme.valueOf(prefs.getString("termTheme", null)!!) }
                .getOrDefault(d.terminalTheme),
            extraKeys = prefs.getString("extraKeys", null)?.split(',')?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() } ?: d.extraKeys,
        )
    }
}

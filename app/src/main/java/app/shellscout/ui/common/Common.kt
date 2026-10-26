package app.shellscout.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import app.shellscout.AppContainer
import java.text.DateFormat
import java.util.Date

/** UI-01: layout is chosen by window width, not device type. */
enum class FormFactor { PHONE, TABLET }

@Composable
fun currentFormFactor(): FormFactor =
    // Configuration reflects the current window, so split-screen, freeform and folds are handled (UI-02).
    if (LocalConfiguration.current.screenWidthDp >= 600) FormFactor.TABLET else FormFactor.PHONE

/**
 * Material's "expanded" width class (840dp+): a 12" tablet in landscape, but not a foldable's inner
 * screen or a tablet in portrait. Only here is there room for a permanent list beside a session.
 */
@Composable
fun isExpandedWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** Very wide windows (large tablets in landscape) can afford labels and wider side panels. */
@Composable
fun isExtraWideWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 1200

val LocalContainer =staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun formatEpochSeconds(seconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

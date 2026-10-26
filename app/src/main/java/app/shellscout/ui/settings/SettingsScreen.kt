package app.shellscout.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.shellscout.settings.TerminalTheme
import app.shellscout.ui.common.LocalContainer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenKeys: () -> Unit, onBack: (() -> Unit)? = null) {
    val container = LocalContainer.current
    val s by container.settings.settings.collectAsState()
    val knownHosts by container.db.knownHosts().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showLicences by rememberSaveable { mutableStateOf(false) }
    if (showLicences) {
        LicencesScreen(onBack = { showLicences = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Security")
            SwitchRow("App lock", "Require fingerprint, face or device PIN to open the app (SC-02)", s.appLockEnabled) { on ->
                container.settings.update { it.copy(appLockEnabled = on) }
                container.appLock.onSettingChanged(on)
            }
            SliderRow("Lock after", "${s.lockTimeoutSeconds}s in the background", s.lockTimeoutSeconds.toFloat(), 0f..600f, 19) { v ->
                container.settings.update { it.copy(lockTimeoutSeconds = v.roundToInt()) }
            }
            SwitchRow("Secure screen", "Block screenshots and hide the app in recent apps", s.secureScreen) { on ->
                container.settings.update { it.copy(secureScreen = on) }
            }
            SliderRow(
                "Clear copied text after", if (s.clipboardClearSeconds == 0) "Never" else "${s.clipboardClearSeconds}s",
                s.clipboardClearSeconds.toFloat(), 0f..300f, 9,
            ) { v -> container.settings.update { it.copy(clipboardClearSeconds = v.roundToInt()) } }
            ListItem(
                headlineContent = { Text("Keys") }, supportingContent = { Text("Generate, import and share SSH keys") },
                trailingContent = { TextButton(onClick = onOpenKeys) { Text("Open") } },
            )

            Section("Connections")
            SliderRow("Default keep-alive", "${s.keepAliveSeconds}s", s.keepAliveSeconds.toFloat(), 5f..120f, 22) { v ->
                container.settings.update { it.copy(keepAliveSeconds = v.roundToInt()) }
            }

            Section("Terminal")
            SliderRow("Font size", "${s.fontSizeSp}sp", s.fontSizeSp.toFloat(), 8f..32f, 23) { v ->
                container.settings.update { it.copy(fontSizeSp = v.roundToInt()) }
            }
            SliderRow("Scrollback", "${s.scrollbackLines} lines (new sessions)", s.scrollbackLines.toFloat(), 1000f..50000f, 48) { v ->
                container.settings.update { it.copy(scrollbackLines = (v / 1000).roundToInt() * 1000) }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Colours", modifier = Modifier.weight(1f))
                TerminalTheme.entries.forEach { t ->
                    FilterChip(
                        selected = s.terminalTheme == t,
                        onClick = { container.settings.update { it.copy(terminalTheme = t) } },
                        label = { Text(t.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            Section("Trusted host keys")
            if (knownHosts.isEmpty()) {
                Text("None yet. Hosts are added when you first trust them.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            knownHosts.forEach { k ->
                ListItem(
                    headlineContent = { Text("${k.host}:${k.port}") },
                    supportingContent = {
                        Column {
                            Text(k.keyType, style = MaterialTheme.typography.labelSmall)
                            Text(k.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { container.db.knownHosts().delete(k) } }) {
                            Icon(Icons.Default.Delete, "Forget this host key")
                        }
                    },
                )
            }

            Section("About")
            ListItem(
                headlineContent = { Text("Open-source licences") }, supportingContent = { Text("ShellScout and the libraries it uses") },
                trailingContent = { TextButton(onClick = { showLicences = true }) { Text("Open") } },
            )
            HorizontalDivider()
            Text(
                "ShellScout © 2026 James Kelly, open source under the Apache License 2.0 and provided without warranty. " +
                    "It makes no network connections except the ones you start.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row { Text(title, modifier = Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.labelLarge) }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

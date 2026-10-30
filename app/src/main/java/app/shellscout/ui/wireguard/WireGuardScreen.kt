package app.shellscout.ui.wireguard

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.backend.Statistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.shellscout.data.HostEntity
import app.shellscout.data.WgConfigEntity
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.common.formatBytes
import app.shellscout.ui.common.formatDuration

/** A config being added (existing = null) or edited. */
private data class Draft(val existing: WgConfigEntity?, val name: String, val text: String, val error: String? = null)

/**
 * WireGuard tunnels: import from a file, a QR code or pasted text, start and stop them, and pin them
 * to hosts so connecting to the host starts the tunnel first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wg = container.wireguard
    val configs by wg.configs.collectAsState(initial = emptyList())
    val active by wg.active.collectAsState()
    val hosts by container.db.hosts().observeAll().collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<Draft?>(null) }
    var assigning by remember { mutableStateOf<WgConfigEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<WgConfigEntity?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, text) = withContext(Dispatchers.IO) { readConfigFile(context, uri) }
                draft = Draft(null, name, text)
            } catch (e: Exception) {
                container.sessions.post(e.message ?: "Couldn't read the file")
            }
        }
    }
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { draft = Draft(null, "", it) }
    }
    val startScan = {
        scanQr.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a WireGuard config QR code")
                .setBeepEnabled(false).setOrientationLocked(false),
        )
    }
    val run: (suspend () -> Unit) -> Unit = { action ->
        busy = true
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { container.sessions.post(e.message ?: "WireGuard error") }
            finally { busy = false }
        }
    }

    // Live traffic and handshake for the running tunnel, while this screen is showing.
    var stats by remember { mutableStateOf<Statistics?>(null) }
    LaunchedEffect(active?.configId) {
        stats = null
        while (active != null) {
            stats = wg.statistics()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WireGuard") },
                actions = {
                    IconButton(onClick = startScan) { Icon(Icons.Default.QrCodeScanner, "Scan QR code") }
                    IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, "Import file") }
                    IconButton(onClick = { draft = Draft(null, "", "") }) { Icon(Icons.Default.ContentPaste, "Paste config") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (configs.isEmpty()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Add a WireGuard config, then start it here or pin it to hosts so it starts when you connect to them. " +
                                "It runs as Android's VPN, so while it's up, traffic to its AllowedIPs from the whole device goes through it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = startScan) { Text("Scan QR code") }
                            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Import file") }
                        }
                        OutlinedButton(onClick = { draft = Draft(null, "", "") }) { Text("Paste config") }
                    }
                }
            }
            items(configs, key = { it.id }) { c ->
                val isUp = active?.configId == c.id
                val summary by produceState("", c) {
                    value = withContext(Dispatchers.IO) { runCatching { summarise(wg.parse(wg.text(c))) }.getOrDefault("Can't read this config") }
                }
                val pinned = hosts.filter { it.wgConfigId == c.id }
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (pinned.isEmpty()) "Not pinned to any host" else "Starts with: " + pinned.joinToString { it.name.ifBlank { it.hostname } },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (isUp) {
                                Text(
                                    statusLine(stats, active?.manual == true, active?.sessions ?: 0),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = isUp, enabled = !busy,
                                onCheckedChange = { on ->
                                    when {
                                        on -> run { wg.start(c.id) }
                                        (active?.sessions ?: 0) > 0 -> confirmStop = true
                                        else -> run { wg.stop() }
                                    }
                                },
                            )
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${c.name}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Pin to hosts…") }, onClick = { menu = false; assigning = c })
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                        menu = false
                                        scope.launch { draft = Draft(c, c.name, withContext(Dispatchers.IO) { wg.text(c) }) }
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = c })
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    draft?.let { d ->
        ConfigDialog(d, onDismiss = { draft = null }) { name, text ->
            scope.launch {
                try {
                    val finalName = name.ifBlank { "Tunnel ${configs.size + 1}" }
                    if (d.existing == null) wg.import(finalName, text) else wg.update(d.existing, finalName, text)
                    draft = null
                    container.sessions.post(if (d.existing == null) "Added $finalName" else "Saved $finalName" + if (active?.configId == d.existing.id) ". Restart the tunnel to use the changes." else "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Shown in the dialog: a snackbar would be hidden behind it.
                    draft = d.copy(name = name, text = text, error = e.message ?: "Invalid config")
                }
            }
        }
    }
    assigning?.let { c -> AssignDialog(c, hosts, configs, onDismiss = { assigning = null }) }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("The config and its keys are removed from this device, and hosts pinned to it connect without a tunnel. If it's running, it stops.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; run { wg.delete(c) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmStop) {
        val n = active?.sessions ?: 0
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop ${active?.name}?") },
            text = { Text(if (n == 1) "A session is using this tunnel and will lose its connection." else "$n sessions are using this tunnel and will lose their connections.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; run { wg.stop() } }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConfigDialog(draft: Draft, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var text by remember(draft) { mutableStateOf(draft.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.existing == null) "Add WireGuard config" else "Edit ${draft.existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text, { text = it }, label = { Text("Config ([Interface] and [Peer] sections)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(
                    "The config includes your private key. It's stored encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pins [config] to the ticked hosts. A host can have one tunnel, so ticking one moves it from any other. */
@Composable
private fun AssignDialog(config: WgConfigEntity, hosts: List<HostEntity>, configs: List<WgConfigEntity>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val initial = remember(config.id) { hosts.filter { it.wgConfigId == config.id }.map { it.id }.toSet() }
    var ticked by remember(config.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin ${config.name} to hosts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Connecting to a ticked host starts this tunnel first. It applies from the next connection.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                )
                if (hosts.isEmpty()) Text("No saved hosts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                hosts.forEach { h ->
                    val other = h.wgConfigId?.takeIf { it != config.id }?.let { id -> configs.firstOrNull { it.id == id }?.name }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { ticked = if (h.id in ticked) ticked - h.id else ticked + h.id },
                    ) {
                        Checkbox(checked = h.id in ticked, onCheckedChange = { ticked = if (it) ticked + h.id else ticked - h.id })
                        Column {
                            Text(h.name.ifBlank { h.hostname })
                            if (other != null) Text("Now uses $other", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val dao = container.db.hosts()
                    (initial - ticked).takeIf { it.isNotEmpty() }?.let { dao.setWgConfig(it.toList(), null) }
                    ticked.takeIf { it.isNotEmpty() }?.let { dao.setWgConfig(it.toList(), config.id) }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "10.0.0.2/32 → vpn.example.com:51820" */
private fun summarise(config: com.wireguard.config.Config): String {
    val addresses = config.`interface`.addresses.joinToString()
    val endpoints = config.peers.mapNotNull { p -> p.endpoint.orElse(null)?.toString() }.joinToString()
    return listOf(addresses, endpoints).filter { it.isNotEmpty() }.joinToString(" → ").ifEmpty { "No address or endpoint" }
}

private fun statusLine(stats: Statistics?, manual: Boolean, sessions: Int): String {
    val who = when {
        manual -> "Started here"
        sessions == 1 -> "Used by 1 session"
        else -> "Used by $sessions sessions"
    }
    if (stats == null) return "Running · $who"
    val handshake = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
    val ago = if (handshake <= 0) "no handshake yet" else "handshake ${formatDuration((System.currentTimeMillis() - handshake) / 1000)} ago"
    return "Running · ↓ ${formatBytes(stats.totalRx())} ↑ ${formatBytes(stats.totalTx())} · $ago · $who"
}

/** Name from the file name without ".conf"; refuses anything too big to be a config. */
private fun readConfigFile(context: android.content.Context, uri: Uri): Pair<String, String> {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull().orEmpty().removeSuffix(".conf")
    val out = java.io.ByteArrayOutputStream()
    resolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_CONFIG_BYTES) error("That file is too big to be a WireGuard config")
        }
    } ?: error("Couldn't open the file")
    return name to out.toString(Charsets.UTF_8.name())
}

private const val MAX_CONFIG_BYTES = 64 * 1024

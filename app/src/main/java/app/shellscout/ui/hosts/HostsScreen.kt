package app.shellscout.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.shellscout.data.AuthMethod
import app.shellscout.data.HostEntity
import app.shellscout.ui.common.FormFactor
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.common.isExtraWideWidth

/** Sentinel for "editing a new host". */
private const val NEW_HOST = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    formFactor: FormFactor,
    onConnect: (HostEntity) -> Unit,
    topBarActions: @Composable () -> Unit,
) {
    val container = LocalContainer.current
    val hosts by container.db.hosts().observeAll().collectAsState(initial = emptyList())
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = when (editingId) {
        null -> null
        NEW_HOST -> HostEntity(name = "", hostname = "", username = "")
        else -> hosts.firstOrNull { it.id == editingId }
    }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        HostList(
            hosts = hosts,
            selectedId = editingId,
            onConnect = onConnect,
            onEdit = { editingId = it.id },
            onAdd = { editingId = NEW_HOST },
            modifier = modifier,
        )
    }

    if (formFactor == FormFactor.TABLET) {
        // UI-04: list-detail, with the editor beside the list.
        Scaffold(topBar = { TopAppBar(title = { Text("Hosts") }, actions = { topBarActions() }) }) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                list(Modifier.width(if (isExtraWideWidth()) 420.dp else 360.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (editing != null) {
                        // A form stretched across a 12" screen is hard to scan; keep it at a readable width.
                        HostEditor(editing, onDone = { editingId = null }, modifier = Modifier.widthIn(max = 640.dp).fillMaxHeight())
                    } else {
                        Text(
                            "Select a host to edit, or tap ▶ to connect.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else if (editing != null) {
        // UI-03: phone shows one screen at a time.
        Scaffold(topBar = { TopAppBar(title = { Text(if (editing.id == 0L) "New host" else "Edit host") }) }) { pad ->
            HostEditor(editing, onDone = { editingId = null }, modifier = Modifier.padding(pad).fillMaxSize())
        }
    } else {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Hosts") }, actions = { topBarActions() }) },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { editingId = NEW_HOST }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Host") })
            },
        ) { pad -> list(Modifier.padding(pad).fillMaxSize()) }
    }
}

@Composable
private fun HostList(
    hosts: List<HostEntity>,
    selectedId: Long?,
    onConnect: (HostEntity) -> Unit,
    onEdit: (HostEntity) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<HostEntity?>(null) }

    LazyColumn(modifier) {
        item { QuickConnect(onConnect) }
        item {
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.Add, null); Text("  Add host")
            }
        }
        val grouped = hosts.groupBy { it.groupName.ifBlank { "Ungrouped" } }
        grouped.forEach { (group, items) ->
            item(key = "g-$group") {
                Text(
                    group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(items, key = { it.id }) { host ->
                HostRow(
                    host = host,
                    selected = host.id == selectedId,
                    onConnect = { onConnect(host) },
                    onEdit = { onEdit(host) },
                    onDuplicate = { scope.launch { container.db.hosts().insert(host.copy(id = 0, name = host.name + " copy", lastConnectedAt = null)) } },
                    onDelete = { confirmDelete = host },
                )
            }
        }
        if (hosts.isEmpty()) {
            item {
                Text(
                    "No saved hosts yet. Use quick connect above, or add a host.",
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${host.name}?") },
            text = { Text("The saved password for this host is deleted too. Trusted host keys are kept.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { container.db.hosts().delete(host) }; confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HostRow(
    host: HostEntity,
    selected: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onConnect),
        headlineContent = { Text(host.name.ifBlank { host.hostname }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${host.username}@${host.hostname}" + if (host.port != 22) ":${host.port}" else "",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (host.allowLegacyAlgorithms) {
                    Icon(
                        Icons.Default.Warning, contentDescription = "Legacy algorithms enabled",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        },
        tonalElevation = if (selected) 4.dp else 0.dp,
        trailingContent = {
            Row {
                IconButton(onClick = onConnect) { Icon(Icons.Default.PlayArrow, contentDescription = "Connect") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

/** HM-04: `user@host[:port]` without saving a profile. */
@Composable
private fun QuickConnect(onConnect: (HostEntity) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        val parsed = parseQuickConnect(text)
        if (parsed == null) error = "Use user@host or user@host:port" else { error = null; onConnect(parsed) }
    }
    val currentError = error
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = null },
            label = { Text("Quick connect") }, placeholder = { Text("user@host:22") },
            singleLine = true, isError = currentError != null,
            supportingText = if (currentError != null) { { Text(currentError) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.weight(1f),
        )
        // With large text or a narrow list, a labelled button leaves the field too short to type in.
        if (isExtraWideWidth()) {
            Button(onClick = { go() }, modifier = Modifier.padding(start = 8.dp)) { Text("Connect") }
        } else {
            FilledIconButton(onClick = { go() }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Connect")
            }
        }
    }
}

fun parseQuickConnect(input: String): HostEntity? {
    val m = Regex("^([^@\\s]+)@([^:\\s]+|\\[[0-9a-fA-F:]+])(?::(\\d{1,5}))?$").find(input.trim()) ?: return null
    val (user, hostRaw, portRaw) = m.destructured
    val port = portRaw.toIntOrNull() ?: 22
    if (port !in 1..65535) return null
    val host = hostRaw.removePrefix("[").removeSuffix("]")
    return HostEntity(name = "$user@$host", hostname = host, port = port, username = user)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditor(initial: HostEntity, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val keys by container.db.keys().observeAll().collectAsState(initial = emptyList())
    val groups by container.db.hosts().observeGroups().collectAsState(initial = emptyList())

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var hostname by remember(initial.id) { mutableStateOf(initial.hostname) }
    var port by remember(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var group by remember(initial.id) { mutableStateOf(initial.groupName) }
    var auth by remember(initial.id) { mutableStateOf(initial.authMethod) }
    var keyId by remember(initial.id) { mutableStateOf(initial.keyId) }
    var savePassword by remember(initial.id) { mutableStateOf(initial.savePassword) }
    var newPassword by remember(initial.id) { mutableStateOf("") }
    var keepAlive by remember(initial.id) { mutableStateOf(initial.keepAliveSeconds?.toString().orEmpty()) }
    var termType by remember(initial.id) { mutableStateOf(initial.termType) }
    var startDir by remember(initial.id) { mutableStateOf(initial.startDirectory.orEmpty()) }
    var legacy by remember(initial.id) { mutableStateOf(initial.allowLegacyAlgorithms) }

    val portNum = port.toIntOrNull()
    val valid = hostname.isNotBlank() && username.isNotBlank() && portNum != null && portNum in 1..65535 &&
        (auth == AuthMethod.PASSWORD || keyId != null)

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                hostname, { hostname = it.trim() }, label = { Text("Host or IP") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                isError = portNum == null || portNum !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
            )
        }
        OutlinedTextField(username, { username = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        // HM-03: free-text group with existing groups offered.
        var groupMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = groupMenu && groups.isNotEmpty(), onExpandedChange = { groupMenu = it }) {
            OutlinedTextField(
                group, { group = it }, label = { Text("Group (optional)") }, singleLine = true,
                trailingIcon = { if (groups.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(groupMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(expanded = groupMenu && groups.isNotEmpty(), onDismissRequest = { groupMenu = false }) {
                groups.forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { group = g; groupMenu = false }) }
            }
        }

        Text("Authentication", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = auth == AuthMethod.PASSWORD, onClick = { auth = AuthMethod.PASSWORD }); Text("Password")
            RadioButton(selected = auth == AuthMethod.KEY, onClick = { auth = AuthMethod.KEY }); Text("SSH key")
        }
        if (auth == AuthMethod.PASSWORD) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = savePassword, onCheckedChange = { savePassword = it })
                Text("  Save password on this device", modifier = Modifier.weight(1f))
            }
            if (savePassword) {
                OutlinedTextField(
                    newPassword, { newPassword = it }, singleLine = true,
                    label = { Text(if (initial.encryptedPassword != null) "Password (leave blank to keep)" else "Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("You'll be asked for the password each time you connect.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            var keyMenu by remember { mutableStateOf(false) }
            val selectedKey = keys.firstOrNull { it.id == keyId }
            ExposedDropdownMenuBox(expanded = keyMenu, onExpandedChange = { keyMenu = it }) {
                OutlinedTextField(
                    value = selectedKey?.let { "${it.name} (${it.keyType})" } ?: if (keys.isEmpty()) "No keys yet — add one under Keys" else "Choose a key",
                    onValueChange = {}, readOnly = true, label = { Text("Key") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                    keys.forEach { k -> DropdownMenuItem(text = { Text("${k.name} (${k.keyType})") }, onClick = { keyId = k.id; keyMenu = false }) }
                }
            }
        }

        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            keepAlive, { keepAlive = it.filter(Char::isDigit).take(4) }, singleLine = true,
            label = { Text("Keep-alive interval, seconds (blank = app default)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(termType, { termType = it.trim() }, label = { Text("Terminal type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(startDir, { startDir = it }, label = { Text("Start directory (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = legacy, onCheckedChange = { legacy = it })
            Column(Modifier.padding(start = 12.dp)) {
                Text("Allow legacy algorithms")
                Text(
                    "Enables SHA-1, CBC and older key exchange for old devices. Weakens security for this host only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (legacy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = valid, onClick = {
                scope.launch {
                    val encrypted = when {
                        auth != AuthMethod.PASSWORD || !savePassword -> null
                        newPassword.isNotEmpty() -> container.vault.encryptString(newPassword)
                        else -> initial.encryptedPassword
                    }
                    val updated = initial.copy(
                        name = name.ifBlank { "$username@$hostname" },
                        hostname = hostname, port = portNum ?: 22, username = username, groupName = group.trim(),
                        authMethod = auth, keyId = if (auth == AuthMethod.KEY) keyId else null,
                        savePassword = encrypted != null, encryptedPassword = encrypted,
                        keepAliveSeconds = keepAlive.toIntOrNull()?.takeIf { it > 0 },
                        termType = termType.ifBlank { "xterm-256color" },
                        startDirectory = startDir.ifBlank { null }, allowLegacyAlgorithms = legacy,
                    )
                    if (updated.id == 0L) container.db.hosts().insert(updated) else container.db.hosts().update(updated)
                    onDone()
                }
            }) { Text("Save") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

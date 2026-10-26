package app.shellscout.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.shellscout.data.HostEntity
import app.shellscout.data.SnippetEntity
import app.shellscout.session.SessionTab
import app.shellscout.ssh.Prompt
import app.shellscout.ui.common.LocalContainer

private val VARIABLE = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

/** SN-03: global snippets, plus those scoped to this host or its group. */
fun SnippetEntity.appliesTo(host: HostEntity): Boolean = when {
    hostId != null -> hostId == host.id
    groupName.isNotBlank() -> groupName.equals(host.groupName, ignoreCase = true)
    else -> true
}

/** SN-02/SN-04: fill variables if any, then send to the tab's terminal. */
suspend fun sendSnippet(snippet: SnippetEntity, tab: SessionTab, ask: suspend (Prompt.SnippetVariables) -> Map<String, String>?) {
    val session = tab.terminal.value ?: return
    val vars = VARIABLE.findAll(snippet.command).map { it.groupValues[1] }.distinct().toList()
    var text = snippet.command
    if (vars.isNotEmpty()) {
        val values = ask(Prompt.SnippetVariables(snippet.name, vars)) ?: return
        text = VARIABLE.replace(text) { m -> values[m.groupValues[1]].orEmpty() }
    }
    // Multi-line snippets are sent as typed lines; a trailing Enter is optional.
    val body = text.replace("\r\n", "\n").replace("\n", "\r")
    session.write(if (snippet.sendEnter) body + "\r" else body)
}

/** Snippet list for a session: bottom sheet on phone, side panel on tablet (UI-08). */
@Composable
fun SnippetPicker(tab: SessionTab, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val all by container.db.snippets().observeAll().collectAsState(initial = emptyList())
    val snippets = all.filter { it.appliesTo(tab.host) }
    Column(modifier) {
        Text("Snippets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (snippets.isEmpty()) {
            Text(
                "No snippets for this host yet. Add them from the Snippets screen.",
                modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn {
            itemsIndexed(snippets, key = { _, s -> s.id }) { _, s ->
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch { sendSnippet(s, tab) { container.prompts.ask(it) } }
                        onPicked()
                    }.heightIn(min = 48.dp),
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Text(s.command, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/** SN-01: create, edit, order and delete snippets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val snippets by container.db.snippets().observeAll().collectAsState(initial = emptyList())
    val hosts by container.db.hosts().observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<SnippetEntity?>(null) }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in snippets.indices) return
        val reordered = snippets.toMutableList().apply { add(target, removeAt(index)) }
        scope.launch { container.db.snippets().updateAll(reordered.mapIndexed { i, s -> s.copy(sortOrder = i) }) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Snippets") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = SnippetEntity(name = "", command = "", sortOrder = snippets.size) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Snippet") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (snippets.isEmpty()) {
                item {
                    Text(
                        "Save commands you run often. Use {{name}} for values you want to be asked for, e.g. sudo systemctl restart {{service}}",
                        modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(snippets, key = { _, s -> s.id }) { index, s ->
                val scopeLabel = when {
                    s.hostId != null -> hosts.firstOrNull { it.id == s.hostId }?.name?.let { "Host: $it" } ?: "Host: (deleted)"
                    s.groupName.isNotBlank() -> "Group: ${s.groupName}"
                    else -> "All hosts"
                }
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Column {
                            Text(s.command, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(scopeLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { move(index, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                            IconButton(onClick = { move(index, 1) }, enabled = index < snippets.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                            IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    },
                )
            }
        }
    }

    editing?.let { s ->
        SnippetEditor(s, hosts, onDismiss = { editing = null }) { updated ->
            editing = null
            scope.launch {
                if (updated.id == 0L) container.db.snippets().insert(updated) else container.db.snippets().update(updated)
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${s.name}\"?") },
            confirmButton = { TextButton(onClick = { scope.launch { container.db.snippets().delete(s) }; confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditor(initial: SnippetEntity, hosts: List<HostEntity>, onDismiss: () -> Unit, onSave: (SnippetEntity) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var sendEnter by remember { mutableStateOf(initial.sendEnter) }
    var hostId by remember { mutableStateOf(initial.hostId) }
    var group by remember { mutableStateOf(initial.groupName) }
    var scopeMenu by remember { mutableStateOf(false) }
    val groups = hosts.map { it.groupName }.filter { it.isNotBlank() }.distinct()

    val scopeText = when {
        hostId != null -> "Host: " + (hosts.firstOrNull { it.id == hostId }?.name ?: "?")
        group.isNotBlank() -> "Group: $group"
        else -> "All hosts"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New snippet" else "Edit snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    command, { command = it }, label = { Text("Command") }, minLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = sendEnter, onCheckedChange = { sendEnter = it })
                    Text("  Press Enter after sending")
                }
                ExposedDropdownMenuBox(expanded = scopeMenu, onExpandedChange = { scopeMenu = it }) {
                    OutlinedTextField(
                        scopeText, {}, readOnly = true, label = { Text("Available on") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(scopeMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                        DropdownMenuItem(text = { Text("All hosts") }, onClick = { hostId = null; group = ""; scopeMenu = false })
                        groups.forEach { g ->
                            DropdownMenuItem(text = { Text("Group: $g") }, onClick = { hostId = null; group = g; scopeMenu = false })
                        }
                        hosts.forEach { h ->
                            DropdownMenuItem(text = { Text("Host: ${h.name}") }, onClick = { hostId = h.id; group = ""; scopeMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && command.isNotEmpty(),
                onClick = { onSave(initial.copy(name = name.trim(), command = command, sendEnter = sendEnter, hostId = hostId, groupName = group)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

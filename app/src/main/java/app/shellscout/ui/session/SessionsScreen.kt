package app.shellscout.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shellscout.data.HostEntity
import app.shellscout.data.TransferStatus
import app.shellscout.session.SessionTab
import app.shellscout.session.SessionView
import app.shellscout.ssh.ConnState
import app.shellscout.ui.common.FormFactor
import app.shellscout.ui.common.isExtraWideWidth
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.files.EditorPane
import app.shellscout.ui.files.FileBrowser
import app.shellscout.ui.snippets.SnippetPicker
import app.shellscout.ui.terminal.TerminalPane
import app.shellscout.ui.terminal.TerminalShortcuts
import app.shellscout.ui.transfers.TransfersList

/** SE-01 tabs with layouts per form factor (UI-03..UI-08). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(formFactor: FormFactor, onConnect: (HostEntity) -> Unit) {
    val container = LocalContainer.current
    val tabs by container.sessions.tabs.collectAsState()
    val activeId by container.sessions.activeTabId.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    var hostPicker by remember { mutableStateOf(false) }
    var snippetsOpen by remember { mutableStateOf(false) }
    var snippetsPinned by rememberSaveable { mutableStateOf(false) }

    val shortcuts = remember(active, formFactor) {
        object : TerminalShortcuts {
            override fun newTab() { hostPicker = true }
            override fun closeTab() { active?.let { container.sessions.close(it) } }
            override fun nextTab(delta: Int) = container.sessions.selectNext(delta)
            override fun toggleFiles() {
                active?.let { t ->
                    t.view.value = when (t.view.value) {
                        SessionView.TERMINAL -> if (formFactor == FormFactor.TABLET) SessionView.SPLIT else SessionView.FILES
                        else -> SessionView.TERMINAL
                    }
                }
            }
            override fun openSnippets() {
                if (formFactor == FormFactor.TABLET) snippetsPinned = !snippetsPinned else snippetsOpen = true
            }
        }
    }

    if (active == null) {
        EmptySessions(formFactor, onConnect = onConnect, onPick = { hostPicker = true })
    } else if (formFactor == FormFactor.TABLET) {
        val extraWide = isExtraWideWidth()
        // The session gets the full width; other hosts are one tap away via "+".
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                // Labels only when the session area is wide enough to keep room for several tabs.
                val labelToggle = maxWidth >= 1100.dp
                Column(Modifier.fillMaxSize()) {
                    TabStrip(tabs, active, onNew = { hostPicker = true }, trailing = {
                        ViewToggle(active, formFactor, showLabels = labelToggle)
                        IconToggleButton(checked = snippetsPinned, onCheckedChange = { snippetsPinned = it }) {
                            Icon(if (snippetsPinned) Icons.Default.PushPin else Icons.AutoMirrored.Filled.ShortText, "Snippets panel")
                        }
                    })
                    Row(Modifier.weight(1f)) {
                        TabletContent(active, shortcuts, Modifier.weight(1f).fillMaxHeight())
                        if (snippetsPinned) {
                            VerticalDivider()
                            SnippetPicker(active, onPicked = {}, modifier = Modifier.width(if (extraWide) 320.dp else 260.dp).fillMaxHeight())
                        }
                    }
                    TransfersPanel()
                }
            }
        }
    } else {
        PhoneSession(tabs, active, shortcuts, onNew = { hostPicker = true }, onSnippets = { snippetsOpen = true })
    }

    if (snippetsOpen && active != null) {
        // UI-08: bottom sheet on phone.
        ModalBottomSheet(onDismissRequest = { snippetsOpen = false }) {
            SnippetPicker(active, onPicked = { snippetsOpen = false }, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    if (hostPicker) {
        HostPickerDialog(onDismiss = { hostPicker = false }) { hostPicker = false; onConnect(it) }
    }
}

@Composable
private fun TabletContent(tab: SessionTab, shortcuts: TerminalShortcuts, modifier: Modifier) {
    val view by tab.view.collectAsState()
    val editorPath by tab.editorPath.collectAsState()
    val files: @Composable (Modifier) -> Unit = { m ->
        val path = editorPath
        if (path != null && view == SessionView.FILES) {
            // Editor opens beside the file browser.
            Row(m) {
                FileBrowser(tab, FormFactor.PHONE, onEdit = { tab.editorPath.value = it.path }, modifier = Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                EditorPane(tab, path, onClose = { tab.editorPath.value = null }, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else if (path != null) {
            EditorPane(tab, path, onClose = { tab.editorPath.value = null }, modifier = m)
        } else {
            FileBrowser(tab, FormFactor.TABLET, onEdit = { tab.editorPath.value = it.path }, modifier = m)
        }
    }
    when (view) {
        SessionView.TERMINAL -> TerminalPane(tab, shortcuts, modifier)
        SessionView.FILES -> files(modifier)
        SessionView.SPLIT -> SplitPane(
            modifier = modifier,
            first = { TerminalPane(tab, shortcuts, it) },
            second = { files(it) },
        )
    }
}

/** UI-05: terminal and files side by side with a draggable divider. */
@Composable
private fun SplitPane(
    modifier: Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // Windows can be squeezed briefly (returning from Recents, folding, entering split-screen).
        // Never lay out a pane too narrow for its content: list rows throw when that happens.
        if (maxWidth < MIN_SPLIT_PANE * 2 + SPLIT_DIVIDER) {
            first(Modifier.fillMaxSize())
            return@BoxWithConstraints
        }
        val totalPx = with(density) { maxWidth.toPx() }
        val minFraction = MIN_SPLIT_PANE / maxWidth
        val f = fraction.coerceIn(minFraction, 1f - minFraction)
        Row(Modifier.fillMaxSize()) {
            first(Modifier.weight(f).fillMaxHeight())
            Box(
                Modifier.width(SPLIT_DIVIDER).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            fraction = (fraction.coerceIn(minFraction, 1f - minFraction) + delta / totalPx)
                                .coerceIn(minFraction, 1f - minFraction)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(4.dp, 32.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            }
            second(Modifier.weight(1f - f).fillMaxHeight())
        }
    }
}

/** Narrowest useful terminal or file pane in split view. */
private val MIN_SPLIT_PANE = 280.dp
private val SPLIT_DIVIDER = 12.dp

@Composable
private fun PhoneSession(
    tabs: List<SessionTab>,
    active: SessionTab,
    shortcuts: TerminalShortcuts,
    onNew: () -> Unit,
    onSnippets: () -> Unit,
) {
    val view by active.view.collectAsState()
    val editorPath by active.editorPath.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TabStrip(tabs, active, onNew = onNew, trailing = {
            IconButton(onClick = onSnippets) { Icon(Icons.AutoMirrored.Filled.ShortText, "Snippets") }
        })
        val path = editorPath
        if (path != null) {
            // Phone: the editor is full screen.
            EditorPane(active, path, onClose = { active.editorPath.value = null }, modifier = Modifier.weight(1f))
            return@Column
        }
        // UI-07: one pane at a time; split is offered on tablet only.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            SegmentedButton(view != SessionView.FILES, { active.view.value = SessionView.TERMINAL }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Terminal") }
            SegmentedButton(view == SessionView.FILES, { active.view.value = SessionView.FILES }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Files") }
        }
        if (view == SessionView.FILES) {
            FileBrowser(active, FormFactor.PHONE, onEdit = { active.editorPath.value = it.path }, modifier = Modifier.weight(1f))
        } else {
            TerminalPane(active, shortcuts, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabStrip(tabs: List<SessionTab>, active: SessionTab, onNew: () -> Unit, trailing: @Composable () -> Unit) {
    val container = LocalContainer.current
    // Shell titles ("user@host: ~/long/path") would otherwise make one tab fill the strip,
    // especially with a large system font, hiding the others.
    val maxTitleWidth = if (isExtraWideWidth()) 220.dp else 140.dp
    Surface(tonalElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabs.indexOf(active).coerceAtLeast(0),
                edgePadding = 0.dp,
                modifier = Modifier.weight(1f),
            ) {
                tabs.forEach { t -> key(t.id) {
                    val title by t.title.collectAsState()
                    val conn by t.connectionFlow.collectAsState()
                    val state by conn.state.collectAsState()
                    Tab(
                        selected = t.id == active.id,
                        onClick = { container.sessions.select(t.id) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(state)
                                Text(
                                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 6.dp).widthIn(max = maxTitleWidth),
                                )
                                IconButton(onClick = { container.sessions.close(t) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Close ${t.host.name}", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                    )
                } }
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New session") }
            trailing()
        }
    }
}

@Composable
private fun StatusDot(state: ConnState) {
    val color = when (state) {
        ConnState.Connected -> Color(0xFF3FA36B)
        is ConnState.Disconnected -> MaterialTheme.colorScheme.error
        else -> Color(0xFFE0A800)
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun ViewToggle(tab: SessionTab, formFactor: FormFactor, showLabels: Boolean) {
    val view by tab.view.collectAsState()
    val options = if (formFactor == FormFactor.TABLET) SessionView.entries else listOf(SessionView.TERMINAL, SessionView.FILES)
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 8.dp)) {
        options.forEachIndexed { i, v ->
            val name = v.name.lowercase().replaceFirstChar { it.uppercase() }
            if (showLabels) {
                SegmentedButton(
                    selected = view == v, onClick = { tab.view.value = v },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name) }
            } else {
                // Icon-only so the tab strip keeps its room on narrower tablets and with large text.
                SegmentedButton(
                    selected = view == v, onClick = { tab.view.value = v },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Icon(v.icon, contentDescription = name) }
            }
        }
    }
}

private val SessionView.icon: ImageVector
    get() = when (this) {
        SessionView.TERMINAL -> Icons.Default.Terminal
        SessionView.FILES -> Icons.Default.Folder
        SessionView.SPLIT -> Icons.Default.VerticalSplit
    }

/** UI tablet: transfers as a collapsible bottom panel. */
@Composable
private fun TransfersPanel() {
    val container = LocalContainer.current
    val all by container.transfers.transfers.collectAsState(initial = emptyList())
    val active = all.count { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
    val failed = all.count { it.status == TransferStatus.FAILED }
    if (active == 0 && failed == 0) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    buildString {
                        append("Transfers: $active active")
                        if (failed > 0) append(", $failed failed")
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Collapse transfers" else "Expand transfers")
                }
            }
            if (expanded) TransfersList(Modifier.height(200.dp), onlyActive = true)
        }
    }
}

@Composable
private fun EmptySessions(formFactor: FormFactor, onConnect: (HostEntity) -> Unit, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No open sessions", style = MaterialTheme.typography.titleMedium)
        Text("Pick a host to connect.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Button(onClick = onPick, modifier = Modifier.padding(top = 16.dp)) { Text("Connect…") }
    }
}

@Composable
private fun HostPickerDialog(onDismiss: () -> Unit, onPick: (HostEntity) -> Unit) {
    val container = LocalContainer.current
    val hosts by container.db.hosts().observeAll().collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open session") },
        text = {
            if (hosts.isEmpty()) Text("No saved hosts. Add one on the Hosts screen.")
            else LazyColumn {
                items(hosts, key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text(h.name) },
                        supportingContent = { Text("${h.username}@${h.hostname}") },
                        modifier = Modifier.padding(0.dp),
                        trailingContent = { TextButton(onClick = { onPick(h) }) { Text("Connect") } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package app.shellscout.ui.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.shellscout.session.SessionTab
import app.shellscout.ssh.ConnState
import app.shellscout.ssh.RemoteClipboard
import app.shellscout.ssh.RemoteEntry
import app.shellscout.ssh.RemoteFs
import app.shellscout.ui.common.FormFactor
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.common.formatBytes
import app.shellscout.ui.common.formatEpochSeconds
import kotlin.math.roundToInt

enum class SortBy { NAME, SIZE, DATE }

/** A file being dragged between panes (UI-06). */
sealed interface DragPayload {
    val label: String
    /** One row, or every selected row when the dragged row is part of the selection. */
    data class Remote(val entries: List<RemoteEntry>) : DragPayload { override val label get() = entries.singleOrNull()?.name ?: itemCount(entries.size) }
    data class Local(val doc: DocumentFile) : DragPayload { override val label get() = doc.name ?: "file" }
}

class DragState {
    var payload by mutableStateOf<DragPayload?>(null)
    var position by mutableStateOf(Offset.Zero)
    var localBounds by mutableStateOf(Rect.Zero)
    var remoteBounds by mutableStateOf(Rect.Zero)
    /** Called with the payload and the release point (root coordinates). */
    var onDrop: (DragPayload, Offset) -> Unit = { _, _ -> }
}

/**
 * FT-01..FT-09 and UI-06/UI-07. Tablet: local and remote side by side with drag and drop.
 * Phone: one pane at a time with a local/remote toggle and menu actions.
 */
@Composable
fun FileBrowser(tab: SessionTab, formFactor: FormFactor, onEdit: (RemoteEntry) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val connection by tab.connectionFlow.collectAsState()
    val state by connection.state.collectAsState()
    var localDir by remember { mutableStateOf(LocalFolder.saved(context)) }
    var localRoot by remember { mutableStateOf(localDir) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) LocalFolder.save(context, uri)?.let { localDir = it; localRoot = it }
    }
    val remoteDir by tab.remoteDir.collectAsState()

    if (state != ConnState.Connected) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val s = state
            if (s is ConnState.Disconnected) {
                // An FTP tab has no terminal, and so no terminal banner to reconnect from.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text("Not connected")
                    s.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { container.sessions.reconnect(tab) }) { Text("Reconnect") }
                }
            } else {
                Text("Connecting…")
            }
        }
        return
    }

    val upload: (List<DocumentFile>) -> Unit = { docs ->
        val dir = remoteDir
        if (dir != null) docs.forEach { container.transfers.uploadDocument(connection, it, dir) }
    }
    val download: (List<RemoteEntry>) -> Unit = { entries ->
        val dir = localDir
        if (dir == null) pickTree.launch(null) else container.transfers.download(connection, entries, dir)
    }

    // The window can be tablet-sized while this pane is not (split view, a narrow fold), so the
    // side-by-side layout also needs the pane itself to be wide enough. Below that, ListItem
    // rows are narrower than their icon and menu button and measuring them throws.
    BoxWithConstraints(modifier) {
        if (maxWidth < SINGLE_PANE_MIN_WIDTH) {
            // Only reachable for a frame or two while the window is being resized.
            Text("Widen this pane to browse files", modifier = Modifier.align(Alignment.Center).padding(8.dp))
        } else if (formFactor == FormFactor.TABLET && maxWidth >= DUAL_PANE_MIN_WIDTH) {
            val drag = remember { DragState() }
            drag.onDrop = { payload, at ->
                if (payload is DragPayload.Local && drag.remoteBounds.contains(at)) {
                    upload(listOf(payload.doc))
                } else if (payload is DragPayload.Remote && drag.localBounds.contains(at)) {
                    download(payload.entries)
                }
            }
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    LocalPane(
                        dir = localDir, root = localRoot, onNavigate = { localDir = it }, onPickRoot = { pickTree.launch(null) },
                        onUpload = upload, drag = drag,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned { drag.localBounds = it.boundsInRoot() }
                            .dropHighlight(drag.payload is DragPayload.Remote && drag.localBounds.contains(drag.position)),
                    )
                    VerticalDivider()
                    RemotePane(
                        tab = tab, onEdit = onEdit, onDownload = download, drag = drag,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned { drag.remoteBounds = it.boundsInRoot() }
                            .dropHighlight(drag.payload is DragPayload.Local && drag.remoteBounds.contains(drag.position)),
                    )
                }
                drag.payload?.let { p ->
                    // Floating label following the finger. Root coordinates are converted to this Box's space
                    // by subtracting its own origin (the local pane's left/top edge).
                    val origin = Offset(drag.localBounds.left, drag.localBounds.top)
                    Surface(
                        shape = MaterialTheme.shapes.small, tonalElevation = 8.dp, shadowElevation = 8.dp,
                        modifier = Modifier.offset {
                            IntOffset((drag.position.x - origin.x).roundToInt() + 12, (drag.position.y - origin.y).roundToInt() + 12)
                        },
                    ) { Text(p.label, modifier = Modifier.padding(8.dp)) }
                }
            }
        } else {
            var showRemote by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxSize()) {
                // One line each and no check mark: in a narrow split pane with large text a wrapped
                // label makes its half taller than the other. The fill already shows which is selected.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(8.dp)) {
                    SegmentedButton(selected = !showRemote, onClick = { showRemote = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) {
                        Text("This device", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(selected = showRemote, onClick = { showRemote = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
                        Text(tab.host.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showRemote) {
                    RemotePane(tab, onEdit, download, drag = null, modifier = Modifier.weight(1f))
                } else {
                    LocalPane(localDir, localRoot, { localDir = it }, { pickTree.launch(null) }, upload, drag = null, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Narrowest pane that still fits local and remote lists side by side. */
private val DUAL_PANE_MIN_WIDTH = 560.dp

/** Below this a file row's icon, padding and menu button leave no room for the name. */
private val SINGLE_PANE_MIN_WIDTH = 200.dp

private fun Modifier.dropHighlight(active: Boolean): Modifier =
    if (!active) this else this.border(2.dp, androidx.compose.ui.graphics.Color(0xFF2E7D6B))

/** Long-press then drag an item to the other pane (tablet only). */
private fun Modifier.draggableItem(drag: DragState?, payload: () -> DragPayload, origin: () -> Offset): Modifier {
    if (drag == null) return this
    return pointerInput(drag) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> drag.payload = payload(); drag.position = origin() + offset },
            onDrag = { change, amount -> change.consume(); drag.position += amount },
            onDragEnd = {
                val p = drag.payload
                drag.payload = null
                if (p != null) drag.onDrop(p, drag.position)
            },
            onDragCancel = { drag.payload = null },
        )
    }
}

// --------------------------------------------------------------------------------- remote pane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotePane(
    tab: SessionTab,
    onEdit: (RemoteEntry) -> Unit,
    onDownload: (List<RemoteEntry>) -> Unit,
    drag: DragState?,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by tab.connectionFlow.collectAsState()
    val fs = remember(conn) { conn.fs() }
    val dir by tab.remoteDir.collectAsState()
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<RemoteDialog?>(null) }
    val pendingShares by container.sessions.pendingShares.collectAsState()
    val clipboard by tab.fileClipboard.collectAsState()
    // FT-10: ticked rows, by path so a refresh keeps the ticks on whatever is still there. Leaving the folder clears them.
    var selecting by remember(dir) { mutableStateOf(false) }
    var selected by remember(dir) { mutableStateOf(emptySet<String>()) }
    var anchor by remember(dir) { mutableStateOf<String?>(null) }
    val clickMods = remember { ClickModifiers() }
    val focus = remember { FocusRequester() }
    val transfersList by container.transfers.transfers.collectAsState(initial = emptyList())
    // Runs a server-side change from a menu or dialog, then refreshes the listing.
    val perform: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            try { action(); refreshTick++ } catch (e: CancellationException) { throw e } catch (e: Exception) { container.sessions.post(e.message ?: "Operation failed") }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val d = dir
        if (uris.isNotEmpty() && d != null) container.transfers.uploadFiles(tab.connection, uris, d)
    }

    LaunchedEffect(fs) {
        if (tab.remoteDir.value == null) {
            tab.remoteDir.value = runCatching { fs.home() }.getOrDefault("/")
        }
    }
    // Refresh when the directory changes, on request, and when a transfer into this host finishes.
    val finishedCount = transfersList.count { it.hostId == tab.host.id && it.status == app.shellscout.data.TransferStatus.DONE }
    LaunchedEffect(dir, refreshTick, fs, finishedCount) {
        val d = dir ?: return@LaunchedEffect
        loading = true
        error = null
        // Leaving the pane or changing directory cancels the listing; that must not show as an error.
        entries = try { fs.list(d) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not list $d"; emptyList() }
        loading = false
    }

    val visible = entries.filter { showHidden || !it.isHidden }.let { list ->
        when (sortBy) {
            SortBy.NAME -> list
            SortBy.SIZE -> list.sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { -it.size }))
            SortBy.DATE -> list.sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { -it.mtime }))
        }
    }
    val selectedEntries = visible.filter { it.path in selected }
    val allSelected = visible.isNotEmpty() && selectedEntries.size == visible.size

    fun endSelection() { selecting = false; selected = emptySet(); anchor = null }
    fun toggle(e: RemoteEntry) {
        selecting = true
        selected = if (e.path in selected) selected - e.path else selected + e.path
        anchor = e.path
    }
    // Shift-click: everything between the last ticked row and this one.
    fun selectRangeTo(e: RemoteEntry) {
        val to = visible.indexOf(e)
        val from = visible.indexOfFirst { it.path == anchor }.takeIf { it >= 0 } ?: to
        selecting = true
        selected = selected + visible.subList(minOf(from, to), maxOf(from, to) + 1).map { it.path }
    }
    fun selectAll() { selecting = true; selected = visible.mapTo(HashSet()) { it.path } }
    fun putOnClipboard(cut: Boolean) {
        if (selectedEntries.isEmpty()) return
        tab.fileClipboard.value = RemoteClipboard(selectedEntries, cut)
        endSelection()
    }
    fun paste() {
        val clip = clipboard ?: return
        val d = dir ?: return
        container.sessions.post(if (clip.cut) "Moving…" else "Copying…")
        perform {
            // A failed move may have moved some items already, so the cut list is spent either way.
            val n = try { fs.paste(clip.entries, d, move = clip.cut) } finally { if (clip.cut) tab.fileClipboard.value = null }
            container.sessions.post(
                when {
                    n == 0 -> "Already in this folder"
                    clip.cut -> "Moved ${itemCount(n)}"
                    else -> "Copied ${itemCount(n)}"
                },
            )
        }
    }
    BackHandler(enabled = selecting) { endSelection() }

    // Keyboard shortcuts for a hardware keyboard. The pane takes focus when it is clicked or tapped.
    val onKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { ev ->
        val any = selectedEntries.isNotEmpty()
        if (ev.type != KeyEventType.KeyDown) false else when {
            ev.isCtrlPressed && ev.key == Key.A -> { selectAll(); true }
            ev.isCtrlPressed && ev.key == Key.X && any -> { putOnClipboard(cut = true); true }
            ev.isCtrlPressed && ev.key == Key.C && any && fs.canCopy -> { putOnClipboard(cut = false); true }
            ev.isCtrlPressed && ev.key == Key.V && clipboard != null -> { paste(); true }
            ev.key == Key.Delete && any -> { dialog = RemoteDialog.Delete(selectedEntries); true }
            ev.key == Key.Escape && selecting -> { endSelection(); true }
            else -> false
        }
    }

    Column(
        modifier
            .onKeyEvent(onKey)
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                // Sees each press before the rows do: notes Ctrl and Shift for the click that follows.
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press) {
                            clickMods.ctrl = ev.keyboardModifiers.isCtrlPressed
                            clickMods.shift = ev.keyboardModifiers.isShiftPressed
                            runCatching { focus.requestFocus() }
                        }
                    }
                }
            },
    ) {
        PaneHeader(
            title = dir ?: "…",
            canGoUp = dir != null && dir != "/",
            onUp = { dir?.let { tab.remoteDir.value = RemoteFs.parent(it) } },
        ) {
            if (selecting) {
                val any = selectedEntries.isNotEmpty()
                IconButton(onClick = { endSelection() }) { Icon(Icons.Default.Close, "Stop selecting") }
                Text(if (any) "${selectedEntries.size} selected" else "Tap items to select", maxLines = 1, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = { if (allSelected) selected = emptySet() else selectAll() }, enabled = visible.isNotEmpty()) {
                    Icon(Icons.Default.SelectAll, if (allSelected) "Select none" else "Select all")
                }
                IconButton(onClick = { putOnClipboard(cut = true) }, enabled = any) { Icon(Icons.Default.ContentCut, "Cut") }
                // FTP can move on the server but not copy.
                if (fs.canCopy) IconButton(onClick = { putOnClipboard(cut = false) }, enabled = any) { Icon(Icons.Default.ContentCopy, "Copy") }
                IconButton(onClick = { onDownload(selectedEntries); endSelection() }, enabled = any) { Icon(Icons.Default.CloudDownload, "Download") }
                IconButton(onClick = { dialog = RemoteDialog.Delete(selectedEntries) }, enabled = any) { Icon(Icons.Default.Delete, "Delete") }
            } else {
                IconButton(onClick = { dir?.let { val p = shellQuote(it); container.sessions.copyToClipboard(p); container.sessions.post("Copied $p") } }, enabled = dir != null) {
                    Icon(Icons.Default.ContentCopy, "Copy folder path")
                }
                IconButton(onClick = { refreshTick++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = { dialog = RemoteDialog.NewFolder }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Icon(Icons.Default.CloudUpload, "Upload files here") }
                IconButton(onClick = { selecting = true }) { Icon(Icons.Default.Checklist, "Select files") }
                IconButton(onClick = { showHidden = !showHidden }) {
                    Icon(if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (showHidden) "Hide hidden files" else "Show hidden files")
                }
                SortMenu(sortBy) { sortBy = it }
            }
        }
        val clip = clipboard
        if (clip != null && dir != null) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (clip.cut) Icons.Default.ContentCut else Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        (clip.entries.singleOrNull()?.name ?: itemCount(clip.entries.size)) + if (clip.cut) " to move" else " to copy",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { paste() }) { Text("Paste here") }
                    TextButton(onClick = { tab.fileClipboard.value = null }) { Text("Cancel") }
                }
            }
        }
        if (pendingShares.isNotEmpty() && dir != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pendingShares.size} shared file(s) ready", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        container.transfers.uploadFiles(tab.connection, pendingShares, dir!!)
                        container.sessions.clearPendingShares()
                    }) { Text("Upload here") }
                    TextButton(onClick = { container.sessions.clearPendingShares() }) { Text("Dismiss") }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.path }) { e ->
                var itemOrigin by remember { mutableStateOf(Offset.Zero) }
                FileRow(
                    name = e.name,
                    isDirectory = e.isDirectory,
                    isLink = e.isSymlink,
                    detail = (if (e.isDirectory) "" else formatBytes(e.size) + " · ") +
                        (if (e.permissions < 0) "" else RemoteFs.permissionString(e.permissions, e.isDirectory) + " · ") + formatEpochSeconds(e.mtime),
                    onClick = {
                        when {
                            clickMods.ctrl -> toggle(e)
                            clickMods.shift -> selectRangeTo(e)
                            selecting -> toggle(e)
                            e.isDirectory -> tab.remoteDir.value = e.path
                            else -> onEdit(e)
                        }
                    },
                    checked = if (selecting) e.path in selected else null,
                    onToggle = { toggle(e) },
                    modifier = Modifier
                        .alpha(if (clip?.cut == true && e.path in clip.paths) 0.5f else 1f)
                        .onGloballyPositioned { itemOrigin = it.positionInRoot() }
                        .draggableItem(drag, {
                            // Read the state here, not values from this composition: the gesture outlives it.
                            val sel = selected
                            DragPayload.Remote(if (e.path in sel) entries.filter { it.path in sel && (showHidden || !it.isHidden) } else listOf(e))
                        }, { itemOrigin }),
                ) { close ->
                    if (!e.isDirectory) DropdownMenuItem(text = { Text("Edit") }, onClick = { close(); onEdit(e) })
                    DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) }, onClick = { close(); onDownload(listOf(e)) })
                    if (!e.isDirectory) DropdownMenuItem(text = { Text("Open with / share…") }, onClick = {
                        close()
                        scope.launch {
                            try {
                                val file = fs.downloadToCache(e, context.cacheDir)
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                                val send = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, e.name))
                            } catch (ex: Exception) {
                                container.sessions.post("Share failed: ${ex.message}")
                            }
                        }
                    })
                    if (!e.isDirectory && fs.canExtract && RemoteFs.archiveBaseName(e.name) != null) DropdownMenuItem(text = { Text("Extract here") }, onClick = {
                        close()
                        container.sessions.post("Extracting ${e.name}…")
                        perform { container.sessions.post("Extracted to ${fs.extract(e).substringAfterLast('/')}") }
                    })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); dialog = RemoteDialog.Rename(e) })
                    DropdownMenuItem(text = { Text("Permissions") }, onClick = { close(); dialog = RemoteDialog.Chmod(e) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); dialog = RemoteDialog.Delete(listOf(e)) })
                }
            }
            if (!loading && visible.isEmpty() && error == null) {
                item { Text("Empty folder", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    // FT-04 dialogs
    when (val d = dialog) {
        null -> {}
        RemoteDialog.NewFolder -> TextInputDialog("New folder", "Name", "", onDismiss = { dialog = null }) { name ->
            dialog = null; dir?.let { base -> perform { fs.mkdir(RemoteFs.join(base, name)) } }
        }
        is RemoteDialog.Rename -> TextInputDialog("Rename", "New name", d.entry.name, onDismiss = { dialog = null }) { name ->
            dialog = null; perform { fs.rename(d.entry.path, RemoteFs.join(RemoteFs.parent(d.entry.path), name)) }
        }
        is RemoteDialog.Chmod -> TextInputDialog(
            "Permissions", "Octal, e.g. 644 or 755",
            // Unknown permissions (some FTP listings) start from the usual defaults.
            if (d.entry.permissions < 0) (if (d.entry.isDirectory) "755" else "644")
            else Integer.toOctalString(d.entry.permissions and 0xFFF).padStart(3, '0'),
            onDismiss = { dialog = null },
            validate = { it.matches(Regex("[0-7]{3,4}")) },
        ) { octal ->
            dialog = null; perform { fs.chmod(d.entry.path, octal.toInt(8)) }
        }
        is RemoteDialog.Delete -> {
            val one = d.entries.singleOrNull()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (one != null) "Delete ${one.name}?" else "Delete ${itemCount(d.entries.size)}?") },
                text = {
                    Text(
                        when {
                            one == null -> "These items, and everything in any folders among them, will be deleted from the server."
                            one.isDirectory && !one.isSymlink -> "This folder and everything in it will be deleted from the server."
                            else -> "This file will be deleted from the server."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        endSelection()
                        perform {
                            d.entries.forEach { fs.delete(it) }
                            // Don't leave deleted items waiting to be pasted.
                            tab.fileClipboard.value?.let { c ->
                                val left = c.entries.filter { k -> d.entries.none { k.path == it.path || k.path.startsWith(it.path.trimEnd('/') + "/") } }
                                tab.fileClipboard.value = if (left.isEmpty()) null else c.copy(entries = left)
                            }
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }
}

/** Ctrl and Shift as they were when the latest click began; a click handler can't see them otherwise. */
private class ClickModifiers {
    var ctrl = false
    var shift = false
}

private fun itemCount(n: Int): String = if (n == 1) "1 item" else "$n items"

/** Single-quotes a path for pasting into a shell, but only when it has spaces or other characters the shell would split on. */
private fun shellQuote(path: String): String =
    if (path.matches(Regex("[A-Za-z0-9_./~+=:,@%-]+"))) path else "'" + path.replace("'", "'\\''") + "'"

private sealed interface RemoteDialog {
    data object NewFolder : RemoteDialog
    data class Rename(val entry: RemoteEntry) : RemoteDialog
    data class Chmod(val entry: RemoteEntry) : RemoteDialog
    data class Delete(val entries: List<RemoteEntry>) : RemoteDialog
}

// --------------------------------------------------------------------------------- local pane

@Composable
private fun LocalPane(
    dir: DocumentFile?,
    root: DocumentFile?,
    onNavigate: (DocumentFile) -> Unit,
    onPickRoot: () -> Unit,
    onUpload: (List<DocumentFile>) -> Unit,
    drag: DragState?,
    modifier: Modifier = Modifier,
) {
    var children by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // DocumentFile has no parent for tree children on all providers, so keep our own back stack.
    val stack = remember(root) { mutableListOf<DocumentFile>() }
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<LocalDialog?>(null) }
    val transfersList by container.transfers.transfers.collectAsState(initial = emptyList())

    // Refresh when the directory changes, on request, and when a download finishes.
    val finishedDownloads = transfersList.count {
        it.direction == app.shellscout.data.TransferDirection.DOWNLOAD && it.status == app.shellscout.data.TransferStatus.DONE
    }
    LaunchedEffect(dir, refreshTick, finishedDownloads) {
        children = withContext(Dispatchers.IO) {
            dir?.listFiles()?.sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() }))?.toList().orEmpty()
        }
    }

    Column(modifier) {
        PaneHeader(
            title = dir?.name ?: "This device",
            canGoUp = stack.isNotEmpty(),
            onUp = { if (stack.isNotEmpty()) onNavigate(stack.removeAt(stack.lastIndex)) },
        ) {
            IconButton(onClick = { refreshTick++ }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = onPickRoot) { Icon(Icons.Default.FolderOpen, "Choose local folder") }
        }
        if (dir == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Choose a folder on this device for downloads and uploads.")
                Button(onClick = onPickRoot, modifier = Modifier.padding(top = 12.dp)) { Text("Choose folder") }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(children, key = { it.uri.toString() }) { doc ->
                var itemOrigin by remember { mutableStateOf(Offset.Zero) }
                FileRow(
                    name = doc.name ?: "?",
                    isDirectory = doc.isDirectory,
                    isLink = false,
                    detail = if (doc.isDirectory) "" else formatBytes(doc.length()) + " · " + formatEpochSeconds(doc.lastModified() / 1000),
                    onClick = { if (doc.isDirectory) { stack.add(dir); onNavigate(doc) } },
                    modifier = Modifier
                        .onGloballyPositioned { itemOrigin = it.positionInRoot() }
                        .draggableItem(drag, { DragPayload.Local(doc) }, { itemOrigin }),
                ) { close ->
                    DropdownMenuItem(text = { Text("Upload") }, leadingIcon = { Icon(Icons.Default.CloudUpload, null) }, onClick = { close(); onUpload(listOf(doc)) })
                    if (!doc.isDirectory) DropdownMenuItem(text = { Text("Open with…") }, onClick = {
                        close()
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, doc.type ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(view, doc.name))
                        } catch (e: Exception) {
                            container.sessions.post("No app can open ${doc.name}")
                        }
                    })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); dialog = LocalDialog.Rename(doc) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); dialog = LocalDialog.Delete(doc) })
                }
            }
        }
    }

    // DocumentFile reports failure as false rather than throwing.
    val perform: (String, () -> Boolean) -> Unit = { failure, action ->
        scope.launch {
            val ok = try { withContext(Dispatchers.IO) { action() } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) container.sessions.post(failure)
            refreshTick++
        }
    }
    when (val d = dialog) {
        null -> {}
        is LocalDialog.Rename -> TextInputDialog("Rename", "New name", d.doc.name.orEmpty(), onDismiss = { dialog = null }) { name ->
            dialog = null; perform("Couldn't rename ${d.doc.name}") { d.doc.renameTo(name) }
        }
        is LocalDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.doc.name}?") },
            text = { Text(if (d.doc.isDirectory) "This folder and everything in it will be deleted from this device." else "This file will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { dialog = null; perform("Couldn't delete ${d.doc.name}") { d.doc.delete() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

private sealed interface LocalDialog {
    data class Rename(val doc: DocumentFile) : LocalDialog
    data class Delete(val doc: DocumentFile) : LocalDialog
}

// --------------------------------------------------------------------------------- shared bits

@Composable
private fun PaneHeader(title: String, canGoUp: Boolean, onUp: () -> Unit, actions: @Composable () -> Unit) {
    Surface(tonalElevation = 1.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUp, enabled = canGoUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one level") }
                Text(
                    title, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { actions() }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileRow(
    name: String,
    isDirectory: Boolean,
    isLink: Boolean,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null outside selection mode; otherwise whether this row is ticked. */
    checked: Boolean? = null,
    /** Set where rows can be selected: tapping the icon ticks the row and starts selecting. */
    onToggle: (() -> Unit)? = null,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = {
            val icon = @Composable {
                Icon(
                    when {
                        isLink -> Icons.Default.Link
                        isDirectory -> Icons.Default.Folder
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = if (isDirectory) "Folder" else "File",
                )
            }
            when {
                checked != null -> Checkbox(checked = checked, onCheckedChange = { onToggle?.invoke() })
                onToggle != null -> IconButton(onClick = onToggle) { icon() }
                else -> icon()
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (detail.isNotEmpty()) { { Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
        trailingContent = {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Actions for $name") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        },
    )
}

@Composable
private fun SortMenu(current: SortBy, onChange: (SortBy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortBy.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() } + if (s == current) "  ✓" else "") },
                    onClick = { open = false; onChange(s) },
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    validate: (String) -> Boolean = { it.isNotBlank() && !it.contains('/') },
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, isError = !validate(value)) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = validate(value.trim())) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

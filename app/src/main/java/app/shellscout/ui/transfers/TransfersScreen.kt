package app.shellscout.ui.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shellscout.data.TransferDirection
import app.shellscout.data.TransferEntity
import app.shellscout.data.TransferStatus
import app.shellscout.transfer.LiveProgress
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.common.formatBytes
import app.shellscout.ui.common.formatDuration

/** FT-05: the transfer queue with progress, speed, ETA, cancel and retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("Transfers") }) }) { pad ->
        TransfersList(Modifier.padding(pad).fillMaxSize())
    }
}

@Composable
fun TransfersList(modifier: Modifier = Modifier, onlyActive: Boolean = false) {
    val container = LocalContainer.current
    val all by container.transfers.transfers.collectAsState(initial = emptyList())
    val live by container.transfers.live.collectAsState()
    val list = if (onlyActive) all.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED || it.status == TransferStatus.FAILED } else all

    Column(modifier) {
        if (!onlyActive && all.any { it.status == TransferStatus.DONE || it.status == TransferStatus.CANCELLED }) {
            TextButton(onClick = { container.transfers.clearFinished() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear finished") }
        }
        if (list.isEmpty()) {
            Text("No transfers.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(list, key = { it.id }) { t -> TransferRow(t, live[t.id]) }
        }
    }
}

@Composable
private fun TransferRow(t: TransferEntity, live: LiveProgress?) {
    val container = LocalContainer.current
    val done = live?.doneBytes ?: t.doneBytes
    val total = live?.totalBytes ?: t.totalBytes
    ListItem(
        leadingContent = {
            Icon(
                if (t.direction == TransferDirection.UPLOAD) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (t.direction == TransferDirection.UPLOAD) "Upload" else "Download",
            )
        },
        headlineContent = { Text(t.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (t.status == TransferStatus.RUNNING) {
                    if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val parts = buildList {
                    add(statusLabel(t))
                    add(formatBytes(done) + " of " + formatBytes(total))
                    if (t.status == TransferStatus.RUNNING && live != null) {
                        add(formatBytes(live.bytesPerSecond) + "/s")
                        live.etaSeconds?.let { add(formatDuration(it) + " left") }
                    }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text(t.remotePath, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row {
                when (t.status) {
                    TransferStatus.QUEUED, TransferStatus.RUNNING ->
                        IconButton(onClick = { container.transfers.cancel(t.id) }) { Icon(Icons.Default.Close, "Cancel") }
                    TransferStatus.FAILED, TransferStatus.CANCELLED ->
                        IconButton(onClick = { container.transfers.retry(t.id) }) { Icon(Icons.Default.Refresh, "Retry or resume") }
                    TransferStatus.DONE -> {}
                }
            }
        },
    )
}

private fun statusLabel(t: TransferEntity): String = when (t.status) {
    TransferStatus.QUEUED -> "Queued"
    TransferStatus.RUNNING -> "Transferring"
    TransferStatus.DONE -> "Done"
    TransferStatus.FAILED -> "Failed" + (t.error?.let { ": $it" } ?: "")
    TransferStatus.CANCELLED -> t.error ?: "Cancelled"
}

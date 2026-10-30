package app.shellscout.ui.keys

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.shellscout.data.AuthMethod
import app.shellscout.data.KeyEntity
import app.shellscout.ssh.SshConnection
import app.shellscout.security.GeneratedKeyType
import app.shellscout.security.PassphraseRequiredException
import app.shellscout.ui.common.LocalContainer

/** AU-02..AU-04: generate, import, export (public) and delete keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(onBack: (() -> Unit)? = null) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by container.db.keys().observeAll().collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) } // "generate" | "import"
    var confirmDelete by remember { mutableStateOf<KeyEntity?>(null) }
    var installing by remember { mutableStateOf<KeyEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtendedFloatingActionButton(onClick = { dialog = "import" }, icon = { Icon(Icons.Default.FileOpen, null) }, text = { Text("Import") })
                ExtendedFloatingActionButton(onClick = { dialog = "generate" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Generate") })
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (keys.isEmpty()) {
                item {
                    Text(
                        "No keys yet. Generate an Ed25519 key, then use Install on server to add it to a server you're connected to.",
                        modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(keys, key = { it.id }) { k ->
                ListItem(
                    headlineContent = { Text(k.name) },
                    supportingContent = {
                        Column {
                            Text(k.keyType + if (k.passphraseProtected) " · passphrase" else "", style = MaterialTheme.typography.labelMedium)
                            Text(k.publicKeyOpenSsh, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { installing = k }, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Default.Dns, null, modifier = Modifier.padding(end = 8.dp))
                                Text("Install on server")
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { container.sessions.copyToClipboard(k.publicKeyOpenSsh) }) { Icon(Icons.Default.ContentCopy, "Copy public key") }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKeyOpenSsh)
                                context.startActivity(Intent.createChooser(send, "Public key"))
                            }) { Icon(Icons.Default.Share, "Share public key") }
                            IconButton(onClick = { confirmDelete = k }) { Icon(Icons.Default.Delete, "Delete key") }
                        }
                    },
                )
            }
        }
    }

    when (dialog) {
        "generate" -> GenerateDialog(onDismiss = { dialog = null }) { name, type ->
            dialog = null
            scope.launch {
                try { container.keyManager.generate(name, type); container.sessions.post("Generated $name") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { container.sessions.post("Key generation failed: ${e.message}") }
            }
        }
        "import" -> ImportDialog(onDismiss = { dialog = null })
    }
    confirmDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete key \"${k.name}\"?") },
            text = { Text("Hosts using this key will no longer be able to connect with it. This can't be undone.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.db.keys().delete(k) }; confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    installing?.let { k -> InstallKeyDialog(k, onDismiss = { installing = null }) }
}

/**
 * AU-05: adds a key's public half to a server through a session that is already signed in (usually
 * with a password), then optionally switches that host to sign in with the key from then on.
 */
@Composable
private fun InstallKeyDialog(key: KeyEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tabs by container.sessions.tabs.collectAsState()
    // One entry per host: two tabs on the same server would install to the same account. FTP tabs have no shell to do it with.
    val live = tabs.filter { it.connection is SshConnection && container.sessions.isLive(it) }.distinctBy { it.host.id }
    var chosen by remember { mutableStateOf(live.firstOrNull()?.id) }
    var useKey by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Install \"${key.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live.isEmpty()) {
                    Text("Connect to the server with your password first, then come back here. The key is added to that session's account.")
                } else {
                    Text("Adds this key to ~/.ssh/authorized_keys for the account you're signed in as:")
                    live.forEach { tab ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { chosen = tab.id },
                        ) {
                            RadioButton(selected = chosen == tab.id, onClick = { chosen = tab.id })
                            Column {
                                Text(tab.host.name)
                                Text("${tab.host.username}@${tab.host.hostname}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useKey = !useKey }) {
                        Checkbox(checked = useKey, onCheckedChange = { useKey = it })
                        Text("Sign in to this host with the key from now on")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && live.any { it.id == chosen },
                onClick = {
                    val tab = live.first { it.id == chosen }
                    busy = true
                    scope.launch {
                        try {
                            val added = (tab.connection as SshConnection).fs().authorizeKey(key.publicKeyOpenSsh)
                            if (useKey) {
                                container.db.hosts().get(tab.host.id)?.let { h ->
                                    container.db.hosts().update(h.copy(authMethod = AuthMethod.KEY, keyId = key.id))
                                }
                            }
                            container.sessions.post(
                                (if (added) "Key added on ${tab.host.name}" else "${tab.host.name} already had this key") +
                                    if (useKey) ". It signs in with the key from the next connection." else "",
                            )
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            container.sessions.post(e.message ?: "Couldn't add the key")
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Installing…" else "Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun GenerateDialog(onDismiss: () -> Unit, onGenerate: (String, GeneratedKeyType) -> Unit) {
    var name by remember { mutableStateOf("tablet-" + android.os.Build.MODEL.replace(' ', '-').lowercase()) }
    var type by remember { mutableStateOf(GeneratedKeyType.ED25519) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate key") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                GeneratedKeyType.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        RadioButton(selected = type == t, onClick = { type = t }); Text(t.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onGenerate(name.trim(), type) }) { Text("Generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var needsPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
            }
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "imported"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenSSH, PEM or PuTTY (.ppk). The key is encrypted on this device; its passphrase is not stored.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose file…") }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text, { text = it }, label = { Text("…or paste the key") }, minLines = 3, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsPassphrase) {
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = text.contains("PRIVATE KEY") || text.contains("PuTTY-User-Key-File"), onClick = {
                scope.launch {
                    try {
                        container.keyManager.import(name.ifBlank { "imported" }, text.trim() + "\n", passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
                        text = ""; passphrase = ""
                        onDismiss()
                    } catch (e: PassphraseRequiredException) {
                        needsPassphrase = true
                        error = if (passphrase.isEmpty()) "Enter the key's passphrase" else "Wrong passphrase"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Could not read this key: ${e.message}"
                    }
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { text = ""; onDismiss() }) { Text("Cancel") } },
    )
}

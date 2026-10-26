package app.shellscout.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.shellscout.ssh.ConflictAnswer
import app.shellscout.ssh.ConflictChoice
import app.shellscout.ssh.Prompt
import app.shellscout.ssh.PromptBroker
import app.shellscout.ssh.SecretAnswer
import app.shellscout.ui.common.formatBytes

/** Shows the oldest outstanding prompt from the SSH layer. Dismissing a dialog is always the safe answer. */
@Composable
fun PromptHost(broker: PromptBroker) {
    val pending by broker.pending.collectAsState()
    val prompt = pending.firstOrNull() ?: return
    when (prompt) {
        is Prompt.HostKeyNew -> HostKeyNewDialog(prompt) { broker.answer(prompt, it) }
        is Prompt.HostKeyChanged -> HostKeyChangedDialog(prompt) { broker.answer(prompt, it) }
        is Prompt.Password -> SecretDialog(
            key = prompt.id,
            title = "Password",
            message = "${prompt.username}@${prompt.host}",
            rememberLabel = if (prompt.offerSave) "Save password on this device" else null,
        ) { broker.answer(prompt, it) }
        is Prompt.Passphrase -> SecretDialog(
            key = prompt.id,
            title = "Key passphrase",
            message = "Unlock key \"${prompt.keyName}\"",
            rememberLabel = "Remember until the app is closed",
        ) { broker.answer(prompt, it) }
        is Prompt.Interactive -> InteractiveDialog(prompt) { broker.answer(prompt, it) }
        is Prompt.FileConflict -> ConflictDialog(prompt) { broker.answer(prompt, it) }
        is Prompt.SnippetVariables -> VariablesDialog(prompt) { broker.answer(prompt, it) }
    }
}

@Composable
private fun HostKeyNewDialog(p: Prompt.HostKeyNew, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text("Trust this host?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First connection to ${p.host}:${p.port}. Check the fingerprint matches the server before trusting it.")
                Text(p.keyType, style = MaterialTheme.typography.labelLarge)
                Text(p.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun HostKeyChangedDialog(p: Prompt.HostKeyChanged, answer: (Boolean) -> Unit) {
    var confirmText by remember(p.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { answer(false) },
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Host key has changed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The key for ${p.host}:${p.port} is different from the one you trusted. " +
                        "This can mean someone is intercepting the connection. Only continue if you know the server was reinstalled or re-keyed.",
                )
                Text("Previously trusted", style = MaterialTheme.typography.labelLarge)
                Text(p.oldFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Now presented (${p.keyType})", style = MaterialTheme.typography.labelLarge)
                Text(p.newFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = confirmText, onValueChange = { confirmText = it },
                    label = { Text("Type REPLACE to accept the new key") }, singleLine = true,
                )
            }
        },
        // Refusing is the prominent, default action (SC-03).
        confirmButton = { FilledTonalButton(onClick = { answer(false) }) { Text("Disconnect") } },
        dismissButton = {
            TextButton(
                onClick = { answer(true) },
                enabled = confirmText.trim() == "REPLACE",
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Replace key") }
        },
    )
}

@Composable
private fun SecretDialog(
    key: String,
    title: String,
    message: String,
    rememberLabel: String?,
    answer: (SecretAnswer?) -> Unit,
) {
    var value by remember(key) { mutableStateOf("") }
    var keep by remember(key) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { answer(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rememberLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keep, onCheckedChange = { keep = it })
                        Text(rememberLabel)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { answer(SecretAnswer(value.toCharArray(), keep)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { answer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun InteractiveDialog(p: Prompt.Interactive, answer: (String?) -> Unit) {
    var value by remember(p.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { answer(null) },
        title = { Text(p.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.instruction.isNotBlank()) Text(p.instruction)
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true, label = { Text(p.prompt.trim()) },
                    visualTransformation = if (p.echo) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { answer(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { answer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictDialog(p: Prompt.FileConflict, answer: (ConflictAnswer) -> Unit) {
    var applyAll by remember(p.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { answer(ConflictAnswer(ConflictChoice.CANCEL, false)) },
        title = { Text("\"${p.name}\" already exists") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Existing: ${formatBytes(p.existingSize)} · Incoming: ${formatBytes(p.incomingSize)}")
                listOfNotNull(
                    ConflictChoice.OVERWRITE to "Overwrite",
                    if (p.canResume) ConflictChoice.RESUME to "Resume (append the rest)" else null,
                    ConflictChoice.RENAME to "Keep both (rename)",
                    ConflictChoice.SKIP to "Skip",
                ).forEach { (choice, label) ->
                    TextButton(onClick = { answer(ConflictAnswer(choice, applyAll)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("Apply to all in this transfer")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { answer(ConflictAnswer(ConflictChoice.CANCEL, true)) }) { Text("Cancel all") } },
    )
}

@Composable
private fun VariablesDialog(p: Prompt.SnippetVariables, answer: (Map<String, String>?) -> Unit) {
    val values = remember(p.id) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = { answer(null) },
        title = { Text(p.snippetName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.variables.forEach { v ->
                    OutlinedTextField(
                        value = values[v].orEmpty(), onValueChange = { values[v] = it },
                        label = { Text(v) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { answer(values.toMap()) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { answer(null) }) { Text("Cancel") } },
    )
}

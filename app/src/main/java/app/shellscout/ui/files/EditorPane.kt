package app.shellscout.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.shellscout.session.SessionTab
import app.shellscout.ssh.EditableFile
import app.shellscout.ssh.RemoteChangedException
import app.shellscout.ssh.RemoteFs
import app.shellscout.ui.common.LocalContainer

/** ED-01..ED-06: edit a remote text file in place over SFTP. */
@Composable
fun EditorPane(tab: SessionTab, path: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val conn by tab.connectionFlow.collectAsState()
    val fs = remember(conn) { RemoteFs(conn) }

    var file by remember(path) { mutableStateOf<EditableFile?>(null) }
    var value by remember(path) { mutableStateOf(TextFieldValue("")) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(path, reloadTick) {
        try {
            val f = fs.readText(path)
            file = f
            value = TextFieldValue(f.text)
            loadError = null
        } catch (e: CancellationException) {
            // The pane closed or the path changed mid-load; not an error to show.
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Could not open file"
        }
    }

    val dirty = file != null && value.text != file!!.text

    fun save(force: Boolean) {
        val f = file ?: return
        saving = true
        scope.launch {
            try {
                file = fs.writeText(f, value.text, force)
                container.sessions.post("Saved ${path.substringAfterLast('/')}")
            } catch (e: RemoteChangedException) {
                conflict = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.sessions.post("Save failed: ${e.message}")
            } finally {
                saving = false
            }
        }
    }

    Column(modifier) {
        Surface(tonalElevation = 1.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (dirty) confirmClose = true else onClose() }) { Icon(Icons.Default.Close, "Close editor") }
                Text(
                    (if (dirty) "● " else "") + path, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Default.Search, "Find and replace") }
                if (saving) CircularProgressIndicator(Modifier.padding(12.dp).width(24.dp))
                else IconButton(onClick = { save(false) }, enabled = dirty) { Icon(Icons.Default.Save, "Save") }
            }
        }
        if (findOpen) FindBar(value, onChange = { value = it })
        HorizontalDivider()

        when {
            loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
            }
            file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val highlight = remember(path) { highlighterFor(path) }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                    // Line numbers.
                    val lines = value.text.count { it == '\n' } + 1
                    Text(
                        (1..lines).joinToString("\n"),
                        style = editorStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    )
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        textStyle = editorStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = if (value.text.length <= HIGHLIGHT_LIMIT) highlight else VisualTransformation.None,
                        modifier = Modifier.horizontalScroll(hScroll).padding(top = 8.dp, end = 16.dp, bottom = 64.dp),
                    )
                }
            }
        }
    }

    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("File changed on the server") },
            text = { Text("Someone or something modified this file after you opened it. Overwrite their changes, or reload and lose yours?") },
            confirmButton = { TextButton(onClick = { conflict = false; save(force = true) }) { Text("Overwrite") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { conflict = false; reloadTick++ }) { Text("Reload") }
                    TextButton(onClick = { conflict = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    }
}

private val editorStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
private const val HIGHLIGHT_LIMIT = 200_000

/** ED-06: find next, replace, replace all, go to line. */
@Composable
private fun FindBar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (query.isEmpty()) return@TextButton
                val from = value.selection.max
                val i = value.text.indexOf(query, from).takeIf { it >= 0 } ?: value.text.indexOf(query)
                if (i >= 0) onChange(value.copy(selection = TextRange(i, i + query.length)))
            }) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val sel = value.selection
                if (query.isNotEmpty() && value.text.substring(sel.min, sel.max) == query) {
                    val text = value.text.replaceRange(sel.min, sel.max, replacement)
                    onChange(TextFieldValue(text, TextRange(sel.min + replacement.length)))
                }
            }) { Text("Replace") }
            TextButton(onClick = {
                if (query.isNotEmpty()) onChange(TextFieldValue(value.text.replace(query, replacement), TextRange(0)))
            }) { Text("All") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(line, { line = it.filter(Char::isDigit) }, label = { Text("Go to line") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val n = line.toIntOrNull() ?: return@TextButton
                var offset = 0
                repeat((n - 1).coerceAtLeast(0)) {
                    val next = value.text.indexOf('\n', offset)
                    if (next < 0) return@repeat
                    offset = next + 1
                }
                onChange(value.copy(selection = TextRange(offset)))
            }) { Icon(Icons.Default.FindReplace, "Go") }
        }
    }
}

/** ED-03: light, regex-based highlighting for shell, YAML, JSON, INI and conf files. */
private fun highlighterFor(path: String): VisualTransformation {
    val name = path.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', "")
    val rules: List<Pair<Regex, SpanStyle>> = when {
        ext in setOf("sh", "bash", "zsh") || name in setOf(".bashrc", ".profile", ".zshrc") -> listOf(
            Regex("#.*") to comment, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'") to string,
            Regex("\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?") to variable,
            Regex("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|function|return|export|local|in)\\b") to keyword,
        )
        ext in setOf("yml", "yaml") -> listOf(
            Regex("#.*") to comment, Regex("^\\s*-?\\s*[A-Za-z0-9_.-]+(?=\\s*:)", RegexOption.MULTILINE) to keyword,
            Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'") to string,
        )
        ext == "json" -> listOf(
            Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)") to keyword, Regex(":\\s*\"(?:\\\\.|[^\"\\\\])*\"") to string,
            Regex("\\b(true|false|null)\\b|-?\\b\\d+(\\.\\d+)?\\b") to variable,
        )
        ext in setOf("ini", "conf", "cfg", "toml", "service", "env", "properties") || name.endsWith("config") -> listOf(
            Regex("^\\s*[#;].*", RegexOption.MULTILINE) to comment, Regex("^\\s*\\[[^\\]]+\\]", RegexOption.MULTILINE) to keyword,
            Regex("^\\s*[A-Za-z0-9_.-]+(?=\\s*[=:])", RegexOption.MULTILINE) to variable,
            Regex("\"(?:\\\\.|[^\"\\\\])*\"") to string,
        )
        else -> emptyList()
    }
    if (rules.isEmpty()) return VisualTransformation.None
    return VisualTransformation { text ->
        val styled = buildAnnotatedString {
            append(text.text)
            for ((regex, style) in rules) {
                regex.findAll(text.text).forEach { m -> addStyle(style, m.range.first, m.range.last + 1) }
            }
        }
        TransformedText(styled, OffsetMapping.Identity)
    }
}

private val comment = SpanStyle(color = Color(0xFF7A8A8F))
private val string = SpanStyle(color = Color(0xFF3E9A4E))
private val keyword = SpanStyle(color = Color(0xFF2F7FC1))
private val variable = SpanStyle(color = Color(0xFFB0662B))

package app.shellscout.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Licence texts shipped in assets/licences. Apache-2.0 4(a) and the MIT notice clauses need them in the app. */
enum class Licence(val title: String, val asset: String) {
    APACHE_2("Apache License 2.0", "licences/apache-2.0.txt"),
    BOUNCY_CASTLE("Bouncy Castle Licence", "licences/bouncy-castle.txt"),
    MIT_SLF4J("MIT License", "licences/mit-slf4j.txt"),
}

private data class Component(val name: String, val notice: String, val licence: Licence)

/** Keep in step with NOTICE.md. Copyright lines are as the projects publish them. */
private val COMPONENTS = listOf(
    Component("ShellScout", "Copyright 2026 James Kelly. Provided \"as is\", without warranty of any kind.", Licence.APACHE_2),
    Component(
        "Termux terminal-emulator and terminal-view",
        "From Termux v0.118.3, derived from Jack Palevich's Android Terminal Emulator. Modified for ShellScout.",
        Licence.APACHE_2,
    ),
    Component("sshj and asn-one", "Copyright (C) 2009 SSHJ Contributors", Licence.APACHE_2),
    Component("Bouncy Castle (bcprov, bcpkix, bcutil)", "Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.", Licence.BOUNCY_CASTLE),
    Component("SLF4J", "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)", Licence.MIT_SLF4J),
    Component("AndroidX and Jetpack Compose", "The Android Open Source Project", Licence.APACHE_2),
    Component("Kotlin standard library and kotlinx.coroutines", "JetBrains and Kotlin contributors", Licence.APACHE_2),
    Component("JetBrains annotations, JSpecify, Guava listenablefuture", "Their respective authors", Licence.APACHE_2),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Licence?>(null) }
    BackHandler { if (open != null) open = null else onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(open?.title ?: "Open-source licences") },
                navigationIcon = { TextButton(onClick = { if (open != null) open = null else onBack() }) { Text("Back") } },
            )
        },
    ) { pad ->
        val licence = open
        if (licence == null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(COMPONENTS) { c ->
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(c.notice + "\n" + c.licence.title, style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { open = c.licence },
                    )
                }
            }
        } else {
            LicenceText(licence, Modifier.padding(pad))
        }
    }
}

@Composable
private fun LicenceText(licence: Licence, modifier: Modifier) {
    val context = LocalContext.current
    var text by rememberSaveable(licence) { mutableStateOf("") }
    LaunchedEffect(licence) {
        text = withContext(Dispatchers.IO) { context.assets.open(licence.asset).bufferedReader().use { it.readText() } }
    }
    // The texts are hard-wrapped near 80 columns; scrolling sideways keeps that layout on a narrow screen.
    SelectionContainer(modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}

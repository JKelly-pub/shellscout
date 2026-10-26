package app.shellscout.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.shellscout.data.HostEntity
import app.shellscout.ui.common.FormFactor
import app.shellscout.ui.common.LocalContainer
import app.shellscout.ui.common.currentFormFactor
import app.shellscout.ui.hosts.HostsScreen
import app.shellscout.ui.keys.KeysScreen
import app.shellscout.ui.prompts.PromptHost
import app.shellscout.ui.session.SessionsScreen
import app.shellscout.ui.settings.SettingsScreen
import app.shellscout.ui.snippets.SnippetsScreen
import app.shellscout.ui.transfers.TransfersScreen

enum class Dest(val label: String, val icon: ImageVector) {
    HOSTS("Hosts", Icons.Default.Dns),
    SESSIONS("Sessions", Icons.Default.Terminal),
    TRANSFERS("Transfers", Icons.Default.SwapVert),
    SNIPPETS("Snippets", Icons.AutoMirrored.Filled.ShortText),
    KEYS("Keys", Icons.Default.Key),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** UI-03: four destinations in a bottom bar on phone. UI-04: everything on a navigation rail on tablet. */
private val PHONE_DESTS = listOf(Dest.HOSTS, Dest.SESSIONS, Dest.TRANSFERS, Dest.SNIPPETS)

@Composable
fun AppRoot(activity: FragmentActivity) {
    val container = LocalContainer.current
    val formFactor = currentFormFactor()
    val locked by container.appLock.locked.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val tabs by container.sessions.tabs.collectAsState()
    val message by container.sessions.messages.collectAsState()
    val activeTransfers by container.transfers.activeCount.collectAsState()
    val pendingShares by container.sessions.pendingShares.collectAsState()
    var dest by rememberSaveable { mutableStateOf(Dest.HOSTS) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); container.sessions.consumeMessage() }
    }
    // FT-09: shared files are uploaded from a session's file browser.
    LaunchedEffect(pendingShares) {
        if (pendingShares.isNotEmpty()) {
            dest = Dest.SESSIONS
            container.sessions.post(
                if (tabs.isEmpty()) "Connect to a host, then open Files to upload ${pendingShares.size} shared file(s)"
                else "Open Files in a session to upload ${pendingShares.size} shared file(s)",
            )
        }
    }

    // Back from a side screen returns to the open sessions (or the host list if none) instead of leaving the app.
    val home = if (tabs.isNotEmpty()) Dest.SESSIONS else Dest.HOSTS
    val goBack: () -> Unit = { dest = home }
    BackHandler(enabled = !locked && dest != Dest.SESSIONS && dest != Dest.HOSTS, onBack = goBack)

    val connect: (HostEntity) -> Unit = { host ->
        container.sessions.open(host)
        dest = Dest.SESSIONS
    }

    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (locked) {
            LockScreen(activity)
            return@Surface
        }
        if (!settings.appLockChosen) {
            AppLockChoice(activity)
            return@Surface
        }

        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                when (dest) {
                    Dest.HOSTS -> HostsScreen(formFactor, onConnect = connect) {
                        if (formFactor == FormFactor.PHONE) {
                            IconButton(onClick = { dest = Dest.KEYS }) { Icon(Icons.Default.Key, "Keys") }
                            IconButton(onClick = { dest = Dest.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    }
                    Dest.SESSIONS -> SessionsScreen(formFactor, onConnect = connect)
                    Dest.TRANSFERS -> TransfersScreen()
                    Dest.SNIPPETS -> SnippetsScreen()
                    Dest.KEYS -> KeysScreen(onBack = if (formFactor == FormFactor.PHONE) goBack else null)
                    Dest.SETTINGS -> SettingsScreen(
                        onOpenKeys = { dest = Dest.KEYS },
                        onBack = if (formFactor == FormFactor.PHONE) goBack else null,
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }

        val badge: @Composable (Dest) -> Unit = { d ->
            when {
                d == Dest.SESSIONS && tabs.isNotEmpty() -> Badge { Text("${tabs.size}") }
                d == Dest.TRANSFERS && activeTransfers > 0 -> Badge { Text("$activeTransfers") }
            }
        }

        if (formFactor == FormFactor.TABLET) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Dest.entries.forEach { d ->
                        NavigationRailItem(
                            selected = dest == d, onClick = { dest = d },
                            icon = { BadgedBox(badge = { badge(d) }) { Icon(d.icon, contentDescription = d.label) } },
                            label = { Text(d.label) },
                        )
                    }
                }
                VerticalDivider()
                content(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
                NavigationBar {
                    PHONE_DESTS.forEach { d ->
                        NavigationBarItem(
                            selected = dest == d || (d == Dest.HOSTS && (dest == Dest.KEYS || dest == Dest.SETTINGS)),
                            onClick = { dest = d },
                            icon = { BadgedBox(badge = { badge(d) }) { Icon(d.icon, contentDescription = d.label) } },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        }
    }

    // Prompts from the SSH layer appear over any screen, but never over the lock screen.
    if (!locked) PromptHost(container.prompts)
}

/** First run only: explain app lock (SC-02) and let the user turn it on or leave it off. */
@Composable
private fun AppLockChoice(activity: FragmentActivity) {
    val container = LocalContainer.current
    val canLock = remember { container.appLock.canAuthenticate(activity) }
    val choose: (Boolean) -> Unit = { on ->
        container.settings.update { it.copy(appLockEnabled = on, appLockChosen = true) }
        container.appLock.onSettingChanged(on)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
            Text("Use app lock?", style = MaterialTheme.typography.titleLarge)
            Text(
                "App lock asks for your fingerprint, face or phone PIN whenever you open ShellScout, and again " +
                    "after it has been in the background for a minute. Anyone who picks up your unlocked phone " +
                    "can't reach your servers, saved passwords or keys.",
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Your sessions and transfers keep running while the app is locked. You can change this, and " +
                    "how soon it locks, at any time in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!canLock) {
                Text(
                    "This phone has no screen lock. Set a PIN, pattern or fingerprint in Android settings to use app lock.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Button(onClick = { choose(true) }, enabled = canLock, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Turn on app lock")
            }
            OutlinedButton(onClick = { choose(false) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Leave off")
            }
        }
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity) {
    val container = LocalContainer.current
    var error by remember { mutableStateOf<String?>(null) }
    val onError: (String) -> Unit = { error = it; if (!container.appLock.locked.value) container.sessions.post(it) }
    LaunchedEffect(Unit) { container.appLock.unlock(activity, onError) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text("ShellScout is locked", style = MaterialTheme.typography.titleLarge)
        Text("Sessions keep running in the background.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Button(onClick = { error = null; container.appLock.unlock(activity, onError) }, modifier = Modifier.padding(top = 24.dp)) {
            Text("Unlock")
        }
    }
}

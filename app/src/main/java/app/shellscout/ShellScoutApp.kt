package app.shellscout

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import app.shellscout.data.AppDatabase
import app.shellscout.security.AppLock
import app.shellscout.security.KeyManager
import app.shellscout.security.Vault
import app.shellscout.service.SessionService
import app.shellscout.session.SessionManager
import app.shellscout.settings.AppSettings
import app.shellscout.ssh.PromptBroker
import app.shellscout.ssh.SshDeps
import app.shellscout.transfer.TransferEngine
import app.shellscout.vpn.WireGuardManager
import java.security.Security

/** Manual dependency container; small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(app)
    val settings = AppSettings(app)
    val vault = Vault()
    val keyManager = KeyManager(db.keys(), vault)
    val prompts = PromptBroker()
    val appLock = AppLock(settings)
    val sshDeps = SshDeps(
        vault = vault, keyManager = keyManager, keyDao = db.keys(), hostDao = db.hosts(),
        knownHostDao = db.knownHosts(), prompts = prompts,
        defaultKeepAliveSeconds = { settings.current.keepAliveSeconds },
    )
    val wireguard = WireGuardManager(app, db.wgConfigs(), db.hosts(), vault, prompts, scope)
    val sessions = SessionManager(app, sshDeps, settings, wireguard, scope)
    val transfers = TransferEngine(app, db.transfers(), prompts, scope, sessions::connectionForHost)
}

class ShellScoutApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
        container = AppContainer(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = container.appLock.onAppBackgrounded()
            override fun onStart(owner: LifecycleOwner) = container.appLock.onAppForegrounded()
        })

        // SE-02: start the foreground service whenever there is something to keep alive.
        container.scope.launch {
            kotlinx.coroutines.flow.combine(container.sessions.tabs, container.transfers.activeCount, container.wireguard.active) { t, a, wg ->
                t.isNotEmpty() || a > 0 || wg != null
            }.distinctUntilChanged().collect { busy ->
                if (busy) launch(Dispatchers.Main) { SessionService.ensureRunning(this@ShellScoutApp) }
            }
        }
    }

    /**
     * Android ships a cut-down BouncyCastle under the name "BC" that lacks algorithms sshj needs
     * (e.g. X25519, Ed25519). Replace it with the full provider, appended last so platform
     * providers (including AndroidKeyStore) keep priority for everything else.
     */
    private fun installBouncyCastle() {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }
}

package app.shellscout.security

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.shellscout.settings.AppSettings

/**
 * SC-02: gates the UI behind biometric or device PIN. Sessions keep running in the service while
 * locked; only the screens are hidden.
 */
class AppLock(private val settings: AppSettings) {

    private val _locked = MutableStateFlow(settings.current.let { it.appLockEnabled && it.appLockChosen })
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null

    fun onAppBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val s = settings.current
        if (s.appLockEnabled && s.appLockChosen && SystemClock.elapsedRealtime() - since >= s.lockTimeoutSeconds * 1000L) {
            _locked.value = true
        }
    }

    fun onSettingChanged(enabled: Boolean) {
        if (!enabled) _locked.value = false
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun unlock(activity: FragmentActivity, onError: (String) -> Unit) {
        if (!settings.current.appLockEnabled) {
            _locked.value = false
            return
        }
        if (!canAuthenticate(activity)) {
            // No biometric or screen lock is configured, so there is nothing to verify against.
            // Locking the user out entirely would leave no way to reach Settings, so open and warn.
            _locked.value = false
            onError("App lock needs a screen lock. Set a PIN, pattern or fingerprint in Android settings.")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock ShellScout")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    private companion object {
        // BIOMETRIC_WEAK covers strong sensors too; STRONG|DEVICE_CREDENTIAL is rejected on API 29.
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}

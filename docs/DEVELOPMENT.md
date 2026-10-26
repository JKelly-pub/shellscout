# Developing ShellScout

Notes for building, signing and testing the app. For what the app does, see the [README](../README.md).

The app was built against the requirements spec "Android SSH/SFTP Client — Requirements
Specification" (draft v0.1, 21 Oct 2026); the requirement IDs below (HM-01, UI-01, …) refer to it.

## Building

Requirements: an Android Studio release that supports AGP 9.4, JDK 17, Android SDK 35.

1. Open the project in Android Studio and let Gradle sync (it downloads Gradle 9.8.2, AGP 9.4.1,
   Kotlin 2.4.21, KSP 2.3.12 and the dependencies). AGP 9 compiles Kotlin itself ("built-in
   Kotlin"), so there is no `org.jetbrains.kotlin.android` plugin.
2. Build › Make Project, or `./gradlew assembleDebug`.
3. Run on a tablet and a phone, or one emulator of each (e.g. Pixel Tablet and Pixel 8).

## Signed release APK (CP-03)

Create a keystore once:

```
keytool -genkeypair -v -keystore shellscout-release.jks -alias shellscout -keyalg RSA -keysize 4096 -validity 10000
```

Keep it outside the repository, and point Gradle at it from your own `~/.gradle/gradle.properties`
(not the project's, and with no trailing spaces, which Gradle keeps as part of the value):

```
SHELLSCOUT_STORE_FILE=C:/path/to/shellscout-release.jks
SHELLSCOUT_STORE_PASSWORD=…
SHELLSCOUT_KEY_ALIAS=shellscout
SHELLSCOUT_KEY_PASSWORD=…
```

`./gradlew assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`.
Without these properties the release APK is built unsigned. Never commit the keystore, and back it up:
every update must be signed with the same key or it won't install over the previous version.

Official releases are signed with the certificate whose SHA-256 is
`76994532acda2e50b2d07d3130fea2bd579359a20e819c384048922bf5bf7714`
(check with `apksigner verify --print-certs app-release.apk`).

To publish a release, bump `versionCode` and `versionName` in `app/build.gradle.kts`, build, tag
`vX.Y.Z` and attach the APK to a GitHub release.

## Layout by window width (UI-01)

| Window width | Layout |
| --- | --- |
| < 600dp (phones, folded foldables) | Bottom bar, one pane at a time |
| 600–839dp (open foldables, tablets in portrait) | Navigation rail; hosts via "+", not kept beside the session |
| ≥ 840dp (12" tablets in landscape) | Host list kept beside the session |
| ≥ 1200dp | Wider side panels; text labels where there is room |

Split view keeps each pane at least 280dp wide, and the file browser shows local and remote side by
side only when its pane is at least 560dp. Terminal text follows the system font scale up to 1.3×;
beyond that, pinch-to-zoom or the font size setting make it larger.

## Architecture

```
Adaptive UI (Compose)  ──binds──▶  SessionManager (app-scoped)  ──▶  SshConnection (sshj)
  phone: bottom nav, one pane          tabs, prompts, clipboard          shell channel + SFTP
  tablet: rail, list-detail, split     TransferEngine (queue, resume)    on one connection
                                        SessionService keeps process alive (foreground, specialUse)
Vault (Android Keystore AES-GCM) · Room (hosts, keys, known_hosts, snippets, transfers)
```

* **Connections outlive screens.** `SessionManager` lives in the application object, not in any
  activity or ViewModel. `SessionService` is a foreground service whose only job is to keep the
  process alive while a tab is open or a transfer is running. The activity handles configuration
  changes itself, so rotation, split-screen and folding only recompose (UI-02).
* **Layout by window width, not device type.** `currentFormFactor()` switches at 600dp using the
  current window configuration, so a tablet in split-screen gets the phone layout (UI-01).
* **Terminal.** Termux's emulator and view are vendored, with `TerminalSession` rewritten to be
  fed by an SSH channel instead of a local process. No NDK needed.
* **Security defaults.** Strict algorithms (no SHA-1, CBC, RC4, 3DES, MD5), opt-in legacy per host;
  TOFU host keys with SHA-256 fingerprints and a typed confirmation to replace a changed key; app
  lock on by default; no backup; no analytics or other network traffic.

## Requirements traceability

| Requirement | Where |
| --- | --- |
| HM-01..05 hosts, groups, quick connect, per-host overrides | `data/Entities.kt`, `ui/hosts/HostsScreen.kt` |
| AU-01..06 password, keys (generate/import incl. .ppk), keyboard-interactive | `security/KeyManager.kt`, `ssh/SshConnection.kt`, `ui/keys/KeysScreen.kt` |
| SE-01..05 tabs, background, one connection per tab, keep-alive, reconnect | `session/SessionManager.kt`, `service/SessionService.kt`, `ssh/AlgorithmPolicy.kt` |
| TE-01..08 terminal, extra keys, hardware keyboard, PTY resize, themes | `ui/terminal/TerminalPane.kt`, `terminal-emulator/…/TerminalSession.java` |
| FT-01..09 SFTP browse, local SAF, recursive transfer, queue, resume, conflicts, share | `ssh/RemoteFs.kt`, `transfer/TransferEngine.kt`, `ui/files/FileBrowser.kt` |
| ED-01..06 remote editing, conflict detection, highlighting, find/replace | `ui/files/EditorPane.kt`, `ssh/RemoteFs.kt` |
| SN-01..05 snippets, scope, variables, two taps from terminal | `ui/snippets/Snippets.kt`, extra-keys bar |
| UI-01..11 adaptive layouts, split divider, dual-pane drag and drop, shortcuts | `ui/AppRoot.kt`, `ui/session/SessionsScreen.kt`, `ui/files/FileBrowser.kt` |
| SC-01..11 vault, app lock, TOFU, algorithms, permissions, FLAG_SECURE, clipboard | `security/*`, `ssh/TofuHostKeyVerifier.kt`, `MainActivity.kt`, manifest |
| RP-01..04 responsiveness, isolation, process death | `TerminalSession` output coalescing, per-tab connections, `TransferDao.failInterrupted` |
| CP-01..06 Android 10+, no Play Services, sideload, SBOM | `gradle/libs.versions.toml`, `NOTICE.md` |

### Not yet implemented, or partial

* **FT-10 (MAY)** "cd here" between terminal and file browser.
* **SC-11 (SHOULD)** encrypted backup and import of hosts, keys and snippets.
* **TE-04** scrollback is set per new session; changing it doesn't resize open sessions.
* **SE-05** network changes are detected by keep-alive (3 missed replies), not by listening for
  connectivity changes, so detection takes up to 3× the keep-alive interval.
* **CP-04 (MAY)** Work Profile/MDM managed configuration is untested.
* Drag and drop on tablet moves single items; multi-select is not implemented.

## Device test checklist

1. Generate an Ed25519 key, copy the public key to a test server's `authorized_keys`, connect.
2. First connect shows the fingerprint; compare with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.
3. Run `htop`, `vim` and `tmux`; rotate and split-screen the tablet mid-session.
4. Lock the screen for 5 minutes; the session should still be connected.
5. Upload a folder, download a large file, kill Wi-Fi mid-transfer, retry (should resume).
6. Edit `/tmp/test.conf`, change it on the server with another shell, save in the app (conflict dialog).
7. Re-key the test server; reconnecting must show the "Host key has changed" warning.

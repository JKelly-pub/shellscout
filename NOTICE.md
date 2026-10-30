# ShellScout

Copyright 2026 James Kelly

Licensed under the Apache License, Version 2.0 (see `LICENSE`).

This product includes software developed by third parties, listed below.

## Vendored source

| Component | Version | Licence | Location | Changes |
| --- | --- | --- | --- | --- |
| Termux `terminal-emulator` | v0.118.3 | Apache-2.0 | `terminal-emulator/` | `TerminalSession` rewritten to take a byte `Transport` (an SSH channel) instead of forking a local process; `JNI.java` and all native code removed. Manifest/namespace updated for AGP 8. |
| Termux `terminal-view` | v0.118.3 | Apache-2.0 | `terminal-view/` | Mouse text selection (drag, double-click, right-click Copy / Paste) in `TerminalView` and `TextSelectionCursorController`; the selection menu's More… item removed. Manifest/namespace updated for AGP 8. |

Upstream: https://github.com/termux/termux-app. These libraries derive from Jack Palevich's
Android Terminal Emulator and are Apache-2.0, an exception to the Termux app's own GPLv3 licence.
Modified files say so in their header.

## Dependencies (resolved by Gradle)

| Artifact | Version | Licence |
| --- | --- | --- |
| com.hierynomus:sshj | 0.41.2 | Apache-2.0 |
| com.hierynomus:asn-one (via sshj) | 0.6.0 | Apache-2.0 |
| commons-net:commons-net (Apache Commons Net, used for FTP and FTPS) | 3.13.0 | Apache-2.0 |
| com.wireguard.android:tunnel (WireGuard userspace backend) | 1.0.20260102 | Apache-2.0 |
| wireguard-go and the Go runtime, inside the tunnel library's `libwg-go.so` | as built by WireGuard | MIT / BSD-3-Clause |
| com.journeyapps:zxing-android-embedded, com.google.zxing:core (QR scanning) | 4.3.0 / 3.5.4 | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on / bcpkix-jdk18on / bcutil-jdk18on | 1.85 | Bouncy Castle Licence (MIT-style) |
| org.slf4j:slf4j-api / slf4j-nop | 2.0.17 / 2.0.16 | MIT |
| AndroidX (Compose, Material 3, Room, Lifecycle, Activity, Fragment, Biometric, DocumentFile, Core and their dependencies) | see `gradle/libs.versions.toml` | Apache-2.0 |
| Kotlin stdlib, kotlinx-coroutines | 2.4.21 / 1.9.0 | Apache-2.0 |
| org.jetbrains:annotations | 23.0.0 | Apache-2.0 |
| org.jspecify:jspecify | 1.0.0 | Apache-2.0 |
| com.google.guava:listenablefuture | 1.0 | Apache-2.0 |

Apache Commons Net
Copyright 2001-2026 The Apache Software Foundation

This product includes software developed at The Apache Software Foundation
(https://www.apache.org/).

The tunnel library also ships `libwg.so` and `libwg-quick.so` (wireguard-tools, GPL-2.0) for its root
backend. ShellScout doesn't use that backend and leaves both out of the APK (`packaging.jniLibs` in
`app/build.gradle.kts`).

Regenerate the full list, including transitive dependencies, with
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.

The app shows these components and their licence texts (`app/src/main/assets/licences/`) under
Settings → Open-source licences. Keep that list (`LicencesScreen.kt`) in step with this file.

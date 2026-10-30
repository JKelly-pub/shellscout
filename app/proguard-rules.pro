# sshj and BouncyCastle use reflection for algorithm lookup.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn org.slf4j.**

# Commons Net picks FTP listing parsers by name; keep them all.
-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**

# The WireGuard backend is reached from native code (JNI) and its VpnService by name.
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.config.** { *; }
-keep class com.wireguard.crypto.** { *; }

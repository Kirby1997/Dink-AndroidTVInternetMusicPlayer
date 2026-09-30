# Dink R8 / ProGuard rules.
#
# R8 is enabled for release to SHRINK (drop unused code) + shrink resources. Obfuscation
# is intentionally OFF (-dontobfuscate) so stack traces and the APK stay readable — the
# user wants the app reversible. We still keep the reflection-driven libraries below,
# which shrinking would otherwise strip.

-dontobfuscate
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# ---- kotlinx.serialization ----
# Models are (de)serialized by generated $$serializer classes looked up reflectively.
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.example.dink_smb_player.**$$serializer { *; }
-keepclassmembers class com.example.dink_smb_player.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.dink_smb_player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# ---- smbj (SMB client) ----
# smbj resolves SMB dialects / providers reflectively and rides on the mbassador event
# bus (also reflection). Keep them whole.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
# Bouncy Castle: no keep. smbj's BCSecurityProvider (the default on Android) only
# `new`s lightweight-API classes (MD4/MD5/SHA-256/512 digests, HMac, CMac, AES/DES/RC4
# engines, CCM/GCM modes, KDFCounterBytesGenerator, *Parameters) and neither it nor
# those classes use reflection or the JCA provider, so R8 traces everything it needs
# from the kept com.hierynomus.** code. The rest of bcprov (JCA provider, PQC, X.509,
# ASN.1) is unreachable and shrunk away. NTLM's ASN.1/SPNEGO is com.hierynomus.asn1.
-dontwarn org.bouncycastle.**

# ---- slf4j (smbj transitive; no-op binding at runtime) ----
-dontwarn org.slf4j.**

# ---- jaudiotagger (tag library) ----
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# ---- Media3 / ExoPlayer ----
# No rules needed: the core extractors/renderers are constructed directly, and the only
# reflective lookups (optional FLAC/Opus/MIDI/FFmpeg extension classes, which we don't
# ship) are covered by the consumer rules bundled in each media3 AAR. PlayerService and
# MediaButtonReceiver are kept via the manifest.

# ---- WorkManager + Room (MonitorWorker / LocalSyncWorker) ----
# WorkManager is bootstrapped by androidx.startup and backed by a Room database
# (WorkDatabase) whose generated *_Impl + DAOs are loaded reflectively. Shrinking these
# crashes at startup ("Failed to create an instance of class WorkDatabase"). Keep them.
-keep class androidx.startup.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class com.example.dink_smb_player.data.source.**Worker { *; }
-dontwarn androidx.work.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**

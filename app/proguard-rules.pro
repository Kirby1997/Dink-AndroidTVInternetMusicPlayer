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
# bus (also reflection) + Bouncy Castle for crypto / ASN.1. Keep them whole.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**

# ---- slf4j (smbj transitive; no-op binding at runtime) ----
-dontwarn org.slf4j.**

# ---- jaudiotagger (tag library) ----
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# ---- Media3 / ExoPlayer ----
# Media3 ships consumer rules, but the extractor classes are instantiated reflectively
# by DefaultExtractorsFactory — keep them so audio still plays in a shrunk build.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

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

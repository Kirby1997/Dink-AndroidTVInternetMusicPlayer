import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing secrets live in keystore.properties (gitignored), NEVER in source.
// Copy keystore.properties.template → keystore.properties and fill it after running the
// keytool command in RELEASE.md. If the file is absent the release variant falls back to
// the debug key so local `assembleRelease` still works — but a debug-signed build CANNOT
// be uploaded to Play.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasUploadKey = keystorePropsFile.exists()

android {
    namespace = "com.example.dink_smb_player"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Store-facing identity. applicationId is permanent once published — it differs
        // from the code `namespace` above (com.example.*) on purpose; Play only governs
        // applicationId, and renaming 100 source packages buys nothing.
        applicationId = "com.dink.player"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.2"

        // Google OAuth client for the "TVs and Limited Input devices" client type,
        // used by the Phase 8 cloud device-flow. Kept out of source control: set
        // DINK_GOOGLE_CLIENT_ID / DINK_GOOGLE_CLIENT_SECRET in ~/.gradle/gradle.properties
        // (or pass -P). Empty default → CloudScreen surfaces a "not configured" notice
        // instead of crashing, so the build is green without secrets.
        val googleClientId = (project.findProperty("DINK_GOOGLE_CLIENT_ID") as String?).orEmpty()
        val googleClientSecret = (project.findProperty("DINK_GOOGLE_CLIENT_SECRET") as String?).orEmpty()
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_SECRET", "\"$googleClientSecret\"")
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrink + resource shrink (NO obfuscation — see proguard-rules.pro
            // -dontobfuscate). Cuts dead code + unused resources for a smaller APK while
            // keeping the build reversible.
            isMinifyEnabled = true
            isShrinkResources = true
            // Real upload key when keystore.properties exists, else debug key so local
            // verification builds still succeed (debug-signed = NOT uploadable to Play).
            signingConfig = if (hasUploadKey) signingConfigs.getByName("upload")
                            else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // JVM unit tests touch media3 classes whose constructors call android.os
        // statics (e.g. PlaybackException → SystemClock); return defaults instead
        // of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.jaudiotagger)

    implementation(libs.okhttp)

    implementation(libs.smbj)
    // smbj pulls slf4j-api 1.7.36 transitively. Without a binding it logs
    // "Failed to load class StaticLoggerBinder" once at startup; slf4j-nop is the
    // no-op binding that silences it — we don't want library logs in logcat.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.katanaclaude.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.katanaclaude.app"
        minSdk = 26
        // 34 keeps the classic (non edge-to-edge) window so the keyboard resizes the terminal.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed signing key, so every build (Android Studio or GitHub Actions) can be
    // installed over the previous one without uninstalling. Uninstalling would wipe the
    // SSH key stored inside the app. This key only signs your sideloaded copy.
    signingConfigs {
        create("sideload") {
            storeFile = file("katana-claude.keystore")
            storePassword = "katanaclaude"
            keyAlias = "katanaclaude"
            keyPassword = "katanaclaude"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            // No minification: JSch loads its crypto classes by name via reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**/module-info.class",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    // Maintained fork of JSch (OpenSSH-compatible keys, ed25519, modern ciphers).
    implementation("com.github.mwiede:jsch:2.28.8")
    // Android has no built-in Ed25519/X25519 for JSch; it uses BouncyCastle for those.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
}

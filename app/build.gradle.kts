plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.atv.nasplayer"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.atv.nasplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    // The key the installed app was first signed with (sardonyx's debug key, standard debug passwords).
    // Android only accepts updates signed with the same key, so every computer that builds for the
    // TV needs a copy at this path; without it, builds fall back to this computer's own debug key.
    val sharedKey = File(System.getProperty("user.home"), ".android/nas-player.keystore")
    signingConfigs {
        create("shared") {
            storeFile = sharedKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play upload key (backed up in 1Password). Its password comes from the macOS Keychain item
        // "nas-player-upload" (or NASPLAYER_UPLOAD_PASSWORD), never a file, and only when a Play build runs.
        val uploadPassword = if (gradle.startParameter.taskNames.none { "Play" in it }) null
            else System.getenv("NASPLAYER_UPLOAD_PASSWORD") ?: runCatching {
                providers.exec { commandLine("security", "find-generic-password", "-s", "nas-player-upload", "-w") }
                    .standardOutput.asText.get().trim()
            }.getOrNull()
        create("upload") {
            storeFile = File(System.getProperty("user.home"), ".android/nas-player-upload.jks")
            storePassword = uploadPassword
            keyAlias = "upload"
            keyPassword = uploadPassword
        }
    }

    buildTypes {
        release {
            // smbj relies on reflection (event bus, ASN.1); keep the release build unminified.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (sharedKey.exists()) "shared" else "debug")
        }
        // Same build as release, signed with the upload key: `./gradlew bundlePlay` → app/build/outputs/bundle/play/.
        // Its own app ID (set below), so it installs beside the sideloaded dev.atv.nasplayer.
        create("play") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("upload")
            matchingFallbacks += "release"
        }
    }

    // One APK per ABI keeps each download small (libVLC + FFmpeg are mostly native code).
    // armeabi-v7a: Google TV Streamer, Chromecast with Google TV (32-bit userspace) and older boxes.
    // arm64-v8a:   Shield, most current TVs.  x86_64: emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/BCKEY.*",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
    implementation(libs.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ffmpeg)
    implementation(libs.libvlc)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.smbj)
    implementation(libs.smbj.rpc)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.nop)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

// The Play listing's permanent ID. Sideload builds keep dev.atv.nasplayer so existing installs keep their data.
androidComponents {
    onVariants(selector().withBuildType("play")) { it.applicationId.set("me.stephanis.nasplayer") }
}

tasks.matching { it.name == "validateSigningPlay" }.configureEach {
    doFirst {
        check(android.signingConfigs.getByName("upload").storePassword != null) {
            "No upload key password. Add it once: security add-generic-password -a \"\$USER\" -s nas-player-upload -w"
        }
    }
}

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

    buildTypes {
        release {
            // smbj relies on reflection (event bus, ASN.1); keep the release build unminified.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per ABI keeps each download small (libVLC + FFmpeg are mostly native code).
    // armeabi-v7a: Chromecast with Google TV (32-bit userspace) and older boxes.
    // arm64-v8a:   Google TV Streamer, Shield, most current TVs.  x86_64: emulator.
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

    implementation(libs.smbj)
    implementation(libs.smbj.rpc)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.nop)

    debugImplementation(libs.compose.ui.tooling)
}

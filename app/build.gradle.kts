plugins {
    id("com.android.application")
}

android {
    namespace = "app.bighorn"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bighorn"
        minSdk = 24
        // 34 keeps the classic (non edge-to-edge) window, so the keyboard never covers the sign-in fields.
        targetSdk = 34
        versionCode = 19
        versionName = "2.0.0"
    }

    // One fixed signing key for every build, so each new version installs over the old one
    // (Android refuses an update signed with a different key). The key file is written at build
    // time from the private BIGHORN_KEYSTORE_B64 repository secret; it is not stored in the code.
    signingConfigs {
        create("bighorn") {
            storeFile = file("bighorn.keystore")
            storePassword = "bighorn-tv"
            keyAlias = "bighorn"
            keyPassword = "bighorn-tv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bighorn")
        }
        debug {
            signingConfig = signingConfigs.getByName("bighorn")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Android's video player (ExoPlayer), its HLS support, and its on-screen controls
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}

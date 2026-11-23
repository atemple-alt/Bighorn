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
        versionCode = 6
        versionName = "1.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signs release builds with the debug key so they install by sideloading without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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

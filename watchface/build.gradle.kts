plugins {
    id("com.android.application")
}

// Watch Face Format: XML and resources only, no code in this APK.
android {
    namespace = "app.muxway.android.watchface"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.muxway.android.watchface"
        minSdk = 33
        targetSdk = 34
        versionCode = 10
        versionName = "0.6.0"
    }

    buildFeatures {
        buildConfig = false
    }
    buildTypes {
        // Minified so no class is left in an APK that declares it has no code;
        // the face is resources, so those are never shrunk.
        debug {
            isMinifyEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
        }
    }
}

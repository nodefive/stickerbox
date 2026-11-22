plugins {
    id("com.android.application")
}

android {
    namespace = "com.unl0cker.stickerbox"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.unl0cker.stickerbox"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed outside Gradle with scripts/sign-release.sh, so no key or password lives in the build.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

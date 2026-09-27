plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alienbooster.claimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alienbooster.claimer"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.5.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

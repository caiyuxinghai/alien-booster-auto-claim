plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "com.alienbooster.claimer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.alienbooster.claimer"
        minSdk = 26
        targetSdk = 34
        versionCode = 51
        versionName = "0.12.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

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
        versionCode = 43
        versionName = "0.8.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shizuku SDK. With Maven resolution the transitive aidl/shared artifacts come in
    // automatically — do NOT mix these with local files() copies of the AARs: local
    // files() do not resolve transitive dependencies and runtime class loading fails
    // (rikka.shizuku.Shizuku NoClassDefFound).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}

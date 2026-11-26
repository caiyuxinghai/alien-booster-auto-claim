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
        versionCode = 54
        versionName = "0.14.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val helperSources = rootProject.layout.projectDirectory.dir("../phonehelper")
val helperAssets = layout.buildDirectory.dir("generated/helperAssets")
val sdkFolder = androidComponents.sdkComponents.sdkDirectory
val packagePhoneHelper by tasks.registering {
    inputs.files(helperSources.asFileTree.matching { include("*.java", "*.sh") })
    outputs.dir(helperAssets)
    doLast {
        val sdk = sdkFolder.get().asFile
        val platform = sdk.resolve("platforms/android-35/android.jar")
        val d8 = sdk.resolve("build-tools/35.0.0/lib/d8.jar")
        val jdk = file(System.getProperty("java.home")).resolve("bin")
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val classes = temporaryDir.resolve("classes").apply { mkdirs() }
        val output = helperAssets.get().asFile.resolve("helper").apply { mkdirs() }
        val javaSources = helperSources.asFile.listFiles { file -> file.extension == "java" }!!.sortedBy { it.name }
        exec {
            executable = jdk.resolve("javac$suffix").path
            args("--release", "8", "-encoding", "UTF-8", "-cp", platform.path, "-d", classes.path)
            args(javaSources.map { it.path })
        }
        exec {
            executable = jdk.resolve("java$suffix").path
            args("-cp", d8.path, "com.android.tools.r8.D8", "--min-api", "34", "--lib", platform.path)
            args("--output", output.resolve("dual-helper.jar").path)
            args(classes.walkTopDown().filter { it.extension == "class" }.sortedBy { it.path }.map { it.path }.toList())
        }
        for (name in listOf("market_lease.sh", "supervise.sh", "bootstrap.sh")) {
            output.resolve(name).writeText(helperSources.file(name).asFile.readText().replace("\r\n", "\n"))
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(helperAssets)
tasks.named("preBuild").configure { dependsOn(packagePhoneHelper) }

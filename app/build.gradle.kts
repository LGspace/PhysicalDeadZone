import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myphysicaldeadzone"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.myphysicaldeadzone"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "1.1.0"

    }

    // ★★★ 把之前的 buildFeatures { viewBinding = true } 删掉也可以，留着也无所谓 ★★★
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // ★★★ 关键：必须有这个 Material 库，否则 XML 会崩溃 ★★★
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val sdkDir = localProperties.getProperty("sdk.dir")
    ?.replace("\\:", ":")
    ?.let(::file)
    ?: error("sdk.dir is missing in local.properties")

val isWindows = System.getProperty("os.name").toLowerCase().contains("windows")
val hostTag = if (isWindows) "windows-x86_64" else "linux-x86_64"
val ndkDir = file(sdkDir.resolve("ndk"))
    .listFiles()
    ?.filter { it.isDirectory }
    ?.maxByOrNull { it.name }
    ?: error("Android NDK is required to build deadzone_daemon")

val llvmBin = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
val clang = llvmBin.resolve(if (isWindows) "aarch64-linux-android26-clang++.cmd" else "aarch64-linux-android26-clang++")
val strip = llvmBin.resolve(if (isWindows) "llvm-strip.exe" else "llvm-strip")
val daemonSource = file("src/main/cpp/deadzone_daemon.cpp")
val daemonBuildOutput = layout.buildDirectory.file("deadzone/deadzone_daemon_arm64")
val daemonAssetOutput = file("src/main/assets/backend/arm64-v8a/deadzone_daemon")

val buildDeadzoneDaemonArm64 by tasks.registering(Exec::class) {
    inputs.file(daemonSource)
    outputs.file(daemonBuildOutput)
    doFirst {
        daemonBuildOutput.get().asFile.parentFile.mkdirs()
        check(clang.exists()) { "Missing NDK compiler: ${clang.absolutePath}" }
    }
    commandLine(
        clang.absolutePath,
        "-std=c++17",
        "-O2",
        "-Wall",
        "-Wextra",
        "-static-libstdc++",
        daemonSource.absolutePath,
        "-o",
        daemonBuildOutput.get().asFile.absolutePath
    )
    doLast {
        if (strip.exists()) {
            exec {
                commandLine(strip.absolutePath, daemonBuildOutput.get().asFile.absolutePath)
            }
        }
    }
}

val syncDeadzoneDaemonAsset by tasks.registering(Copy::class) {
    dependsOn(buildDeadzoneDaemonArm64)
    from(daemonBuildOutput)
    into(daemonAssetOutput.parentFile)
    rename { "deadzone_daemon" }
}

tasks.named("preBuild") {
    dependsOn(syncDeadzoneDaemonAsset)
}

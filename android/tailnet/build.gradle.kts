import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.follow.clash.tailnet"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { keepDebugSymbols += "**/libtailnet.so" } }
    sourceSets { getByName("test").java.setSrcDirs(listOf("../tests/tailnet")) }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":common"))
    implementation(libs.gson)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
}

val tailnetPlatforms = mapOf("android-arm64" to "arm64-v8a", "android-arm" to "armeabi-v7a", "android-x64" to "x86_64")
val requestedPlatforms = (rootProject.findProperty("target-platform") as String?)?.split(",") ?: listOf("android-arm64")
val nativeTasks = requestedPlatforms.map { platform ->
    val abi = tailnetPlatforms[platform.trim()] ?: error("Unsupported Tailnet platform $platform")
    tasks.register<Exec>("buildTailnet_${abi.replace('-', '_')}") {
        workingDir(rootProject.file(".."))
        commandLine("python3", "tool/build_tailnet.py", "--abi", abi)
        inputs.files(rootProject.file("../tool/build_tailnet.py"), rootProject.file("../native/tailnet/platform_android.go"), rootProject.file("../native/tailnet/backend.lock.json"))
        outputs.file(file("src/main/jniLibs/$abi/libtailnet.so"))
    }
}
tasks.named("preBuild") { dependsOn(nativeTasks) }

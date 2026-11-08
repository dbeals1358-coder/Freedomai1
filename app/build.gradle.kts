plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.unboundaistudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.unboundaistudio"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 34
        versionName = "2.14"
    }
    // The build injects this into the APK; it can be extracted from the APK.
    val hfToken = System.getenv("HF_ACCESS_TOKEN").orEmpty()
    val nightmareBuildToken = System.getenv("NIGHTMARE_API_TOKEN").orEmpty()
    require(nightmareBuildToken.none { it.code == 10 || it.code == 13 || it.code == 34 || it.code == 92 }) { "Invalid Nightmare token characters" }
    require(hfToken.isEmpty() || Regex("hf_[A-Za-z0-9]+").matches(hfToken)) { "Invalid HF token format" }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig {
        buildConfigField("String", "HF_ACCESS_TOKEN", "\"$hfToken\"")
        buildConfigField("String", "NIGHTMARE_API_TOKEN", "\"$nightmareBuildToken\"")
    }
    packaging { jniLibs { pickFirsts += "lib/arm64-v8a/libc++_shared.so" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // GitHub Actions pins and fetches the official native Qwen Android AAR before compiling.
    implementation(files("libs/qwenimage21.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}

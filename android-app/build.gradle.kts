plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ng.pidgingpt"
    compileSdk = providers.gradleProperty("vint.compileSdk").get().toInt()
    buildToolsVersion = providers.gradleProperty("vint.buildTools").get()
    ndkVersion = providers.gradleProperty("vint.ndk").get()
    defaultConfig {
        applicationId = "ng.pidgingpt"; minSdk = 26; targetSdk = 34
        versionCode = 4; versionName = "0.4.0-m4"
        ndk { abiFilters += listOf("arm64-v8a") }   // 64-bit ARM phones only; add x86_64 for emulator testing
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += listOf("-DGGML_OPENMP=OFF") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = providers.gradleProperty("vint.cmake").get() } }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("db", "gguf") }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Offline speech recognition + synthesis (sherpa-onnx, Apache-2.0). Local AAR, NOT fetched from Maven: run `python tools/fetch_voice_models.py --runtime` first (SHA-256 verified).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // NO okhttp / retrofit / firebase / analytics: networking is plain HTTPS in ng/pidgingpt/net only. Enforced by tools/audit_android_offline.py
}

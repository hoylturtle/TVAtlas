import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val signingFile = System.getenv("TVATLAS_SIGNING_PROPERTIES")?.let { file(it) }
val releaseSigning = Properties().apply {
    if (signingFile != null) signingFile.inputStream().use { load(it) }
}
android {
    namespace = "com.tvatlas.player"
    compileSdk = 35
    defaultConfig { applicationId = "com.tvatlas.player"; minSdk = 23; targetSdk = 35; versionCode = providers.gradleProperty("tvatlasVersionCode").orElse("10").get().toInt(); versionName = "0.1.9"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    signingConfigs {
        if (signingFile != null) create("tvatlasRelease") {
            storeFile = file(requireNotNull(releaseSigning.getProperty("storeFile")))
            storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (signingFile != null) signingConfig = signingConfigs.getByName("tvatlasRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libmihomo.so" } }
}
 ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}


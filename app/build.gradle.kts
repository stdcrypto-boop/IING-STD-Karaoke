plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.iingstd.karaoke"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.iingstd.karaoke"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions builds must advance their versionCode for in-place updates.
        // Signing certificate must ALSO stay identical across builds.
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.0-rc2"
    }
    // Use one persistent signing identity supplied by GitHub Actions secrets.
    // Without these values the debug build uses Android's ephemeral debug key
    // and is NOT guaranteed to install over the previous Actions APK.
    val signingPath = System.getenv("IING_SIGNING_KEYSTORE")
    val signingStorePassword = System.getenv("IING_SIGNING_STORE_PASSWORD")
    val signingAlias = System.getenv("IING_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("IING_SIGNING_KEY_PASSWORD")
    if (!signingPath.isNullOrBlank() && !signingStorePassword.isNullOrBlank() &&
        !signingAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()) {
        signingConfigs {
            create("persistent") {
                storeFile = file(signingPath)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("persistent") }
            getByName("release") { signingConfig = signingConfigs.getByName("persistent") }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.x4flasher"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.x4flasher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Release signing comes from env vars so the keystore itself never lives in the repo.
    // Locally: export them yourself. In CI: set them as GitHub secrets (see workflow).
    val ksFile = System.getenv("X4_KEYSTORE_PATH")
    if (ksFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("X4_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("X4_KEY_ALIAS")
                keyPassword = System.getenv("X4_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}

import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hotsharefile.hotsharefile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hotsharefile.hotsharefile"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release-key.jks")

            if (!keystoreFile.exists()) {
                println("Generating temporary keystore at: ${keystoreFile.absolutePath}")
                val storePass = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                val keyPass = System.getenv("KEY_PASSWORD") ?: "android"
                val aliasName = System.getenv("KEY_ALIAS") ?: "key0"

                providers.exec {
                    commandLine(
                        "keytool", "-genkey", "-v",
                        "-keystore", keystoreFile.absolutePath,
                        "-alias", aliasName,
                        "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000",
                        "-storepass", storePass,
                        "-keypass", keyPass,
                        "-dname", "CN=HotShareFile, OU=Dev, O=HotShare, L=Tehran, S=Tehran, C=IR"
                    )
                }
            }

            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core Kotlin & AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Jetpack Compose Foundation & UI ONLY (No Material/Material3)
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.0")
}
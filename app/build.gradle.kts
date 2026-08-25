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
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release-key.jks")
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            val alias = System.getenv("KEY_ALIAS") ?: "key0"
            val keyPass = System.getenv("KEY_PASSWORD") ?: "android"

            if (!keystoreFile.exists()) {
                println("Generating temporary keystore at: ${keystoreFile.absolutePath}")
                project.exec {
                    commandLine(
                        "keytool", "-genkey", "-v",
                        "-keystore", keystoreFile.absolutePath,
                        "-alias", alias,
                        "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000",
                        "-storepass", storePass,
                        "-keypass", keyPass,
                        "-dname", "CN=HotShareFile, OU=Dev, O=HotShare, L=Tehran, S=Tehran, C=IR"
                    )
                }
            }

            storeFile = keystoreFile
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
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
    // Core & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Jetpack Compose (Pure Foundation UI)
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}

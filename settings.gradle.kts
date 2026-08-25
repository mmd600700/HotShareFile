pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.8.0"
        id("org.jetbrains.kotlin.android") version "2.1.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HotShareFile"
include(":app")
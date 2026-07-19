pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.7.2" apply false
        id("com.android.library") version "8.7.2" apply false
        id("org.jetbrains.kotlin.android") version "2.0.21" apply false
        id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
        id("com.google.devtools.ksp") version "2.0.21-1.0.14" apply false
        id("kotlin-kapt") version "2.0.21" apply false
        id("com.android.cmake") version "3.22.1" apply false
        id("com.google.dagger.hilt.android") version "2.51.1" apply false
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:8.7.2")
            }
            if (requested.id.id == "org.jetbrains.kotlin.android" || requested.id.id == "org.jetbrains.kotlin.multiplatform" || requested.id.id == "org.jetbrains.kotlin.plugin.compose" || requested.id.id == "org.jetbrains.kotlin.plugin.serialization") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
            }
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.14")
            }
            if (requested.id.id == "kotlin-kapt") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
            }
            if (requested.id.id == "com.android.cmake") {
                useModule("com.android.tools.build:gradle:8.7.2")
            }
            if (requested.id.id == "com.google.dagger.hilt.android") {
                useModule("com.google.dagger:hilt-android-gradle-plugin:2.51.1")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "SceneEditor"

include(":app")
include(":core:engine")
include(":core:common")
include(":feature:viewport")
include(":feature:ui")
include(":feature:scene")
include(":feature:timeline")
include(":feature:import_export")
include(":feature:modeling")
include(":feature:rendering")

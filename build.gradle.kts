import org.gradle.api.tasks.Delete

// Root build.gradle.kts
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("kotlin-kapt") apply false
    id("com.google.devtools.ksp") apply false
    id("com.android.cmake") apply false
    id("com.google.dagger.hilt.android") apply false
}

allprojects {
    group = "com.example"
    version = "1.0.0"

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

subprojects {
    // Apply plugins convention
    plugins.withId("com.android.library") {
        // Android library specific config if needed
    }
    plugins.withId("com.android.application") {
        // Android application specific config if needed
    }

    // Common Android & Kotlin Configuration
    afterEvaluate {
        if (plugins.hasPlugin("com.android.library") || plugins.hasPlugin("com.android.application")) {
            val androidExt = the<com.android.build.api.dsl.BaseExtension>()

            androidExt.compileSdk = 34
            androidExt.defaultConfig.minSdk = 24
            androidExt.defaultConfig.targetSdk = 34

            androidExt.compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            androidExt.kotlinOptions {
                jvmTarget = "17"
            }

            // Ensure kotlin-android and kotlin-kapt are applied if not already
            if (!plugins.hasPlugin("org.jetbrains.kotlin.android")) {
                pluginManager.apply("org.jetbrains.kotlin.android")
            }
            if (!plugins.hasPlugin("kotlin-kapt")) {
                pluginManager.apply("kotlin-kapt")
            }
        }
    }
}

// Define clean task
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
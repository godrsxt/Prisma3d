plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.core.engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
                cppFlags("-std=c++20", "-frtti", "-fexceptions")
            }
        }

        ndk {
            abiFilters("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = "src/main/cpp/CMakeLists.txt"
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    buildFeatures {
        prefab = true
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation("com.google.android.filament:filament-android:1.55.0")
    implementation("com.google.android.filament:gltfio-android:1.55.0")
    implementation("com.google.android.filament:filamat-android:1.55.0")
    implementation("org.lwjgl:glslang:1.3.280.0")
    implementation("org.lwjgl:spirv-cross:1.3.280.0")
    implementation("org.assimp:assimp:5.4.3")
}
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.android.kotlin.multiplatform") // Optional if sharing logic, but standard Android lib is fine.
    // Plugin for Filament matc compilation (hypothetical/custom plugin or task setup)
    // id("com.google.filament.matc") // Assuming a plugin exists or we define tasks manually
}

android {
    namespace = "com.example.rendering"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Configure NDK for Filament/glslang/spirv-cross native build steps
        ndk {
            abiFilters.clear()
            abiFilters.addAll("arm64-v8a", "x86_64") // Standard for modern rendering
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DFILAMENT_MATC_ENABLED=ON",
                    "-DGLSLANG_VALIDATE=ON",
                    "-DSPRV_CROSS_REFLECT=ON"
                )
                cppFlags += listOf("-std=c++20", "-frtti", "-fexceptions")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ensure native libs are stripped correctly
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
    }

    externalNativeBuild {
        cmake {
            path = "src/main/cpp/CMakeLists.txt"
            version = "3.22.1"
        }
    }

    sourceSets {
        main {
            jniLibs.srcDirs += "build/libs/${buildDir.name}/jni" // Output from matc/native builds
            // Shader source sets for build-time compilation
            // resources.srcDirs += "src/main/materials" // .mat files
        }
    }

    // Custom Task: Compile Filament Materials (.mat -> .matc) at Build Time
    tasks.register("compileFilamentMaterials", Exec::class) {
        group = "Filament"
        description = "Compiles .mat material files to .matc binaries using matc tool"

        // Assumes matc binary is available in PATH or downloaded via CMake/gradle script
        // For CI/CD, matc should be prebuilt for host OS (Linux/Mac/Windows)
        val matcBinary = project.rootProject.file("prebuilt/tools/matc/matc${if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""}")
        val inputDir = project.file("src/main/materials")
        val outputDir = project.file("build/generated/assets/filament/materials")

        doLast {
            if (!inputDir.exists()) {
                logger.lifecycle("Material source dir not found: $inputDir, skipping.")
                return@doLast
            }
            outputDir.mkdirs()

            val materials = fileTree(inputDir) { include("**/*.mat") }
            materials.forEach { matFile ->
                val relativePath = inputDir.toPath().relativize(matFile.toPath())
                val outputFile = outputDir.resolve(relativePath.toString().replace(".mat", ".matc"))
                outputFile.parentFile?.mkdirs()

                val targetApi = "android" // Filament backend target
                val args = listOf(
                    "-a", targetApi,
                    "-o", outputFile.absolutePath,
                    "--optimize=size", // or speed
                    matFile.absolutePath
                )

                logger.lifecycle("Compiling Material: ${matFile.name}")
                // Use project.exec for direct execution or commandLine
                // Here using commandLine for Exec task type
                commandLine(matcBinary.absolutePath, *args.toTypedArray())
            }
        }
    }

    // Ensure material compilation runs before asset merging / packaging
    tasks.named("mergeDebugAssets") { dependsOn("compileFilamentMaterials") }
    tasks.named("mergeReleaseAssets") { dependsOn("compileFilamentMaterials") }

    // Packaging options for native libs and .matc files
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("**/*.mat") // Exclude source materials from APK/AAR
        }
    }
}

dependencies {
    // Core Engine Dependency
    implementation(project(":core:engine"))

    // Filament Runtime Dependencies (AARs or JARs)
    // Assuming Filament is consumed via Maven or local AARs in core:engine or here directly.
    // If core:engine exposes Filament transitively, this might not be needed explicitly.
    // implementation("com.google.android.filament:filament-android:1.55.0") // Example version
    // implementation("com.google.android.filament:gltfio-android:1.55.0")
    // implementation("com.google.android.filament:filament-utils-android:1.55.0")
    // implementation("com.google.android.filament:matdbg-android:1.55.0")

    // SPIR-V Cross / glslang (usually static linked in native CMake, but if Java bindings exist)
    // implementation("org.shaderc:shaderc:1.3.0") // If using shaderc for runtime compilation fallback

    // Kotlin Coroutines for Async Render Pipeline Setup
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Math Library (likely in core:engine, but ensure availability)
    // implementation("com.github.dbrizov:kt-math:0.5.0") // Example

    // Serialization for Pipeline Config / Material Definitions (JSON/Proto)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // AndroidX Core / Lifecycle for Renderer Lifecycle management
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// --- CMakeLists.txt (src/main/cpp/CMakeLists.txt) Content Reference ---
// This is not the build.gradle.kts but required for the NDK build configuration mentioned above.
// Included here as comment for context on how native compilation (glslang/spirv-cross/matc) is wired.
/*
cmake_minimum_required(VERSION 3.22.1)
project("rendering_native" LANGUAGES CXX)

# Find/Build Filament, glslang, spirv-cross, shaderc
# Typically fetched via FetchContent or prebuilt via CI
# find_package(filament CONFIG REQUIRED)
# find_package(glslang CONFIG REQUIRED)
# find_package(spirv-cross CONFIG REQUIRED)

# Custom Target: Runtime Shader Compiler (Optional fallback if not using prebuilt matc)
add_library(shader_compiler SHARED
    src/main/cpp/shader_compiler.cpp
    src/main/cpp/spirv_reflect_wrapper.cpp
)
target_link_libraries(shader_compiler
    filament::filament
    glslang::glslang
    SPIRV-Cross::spirv-cross-c-shared
    shaderc::shaderc_shared
    android
    log
)

# Generate JNI Headers for Kotlin Native Interop
# task generateJniHeaders ... (handled by Android Gradle Plugin)
*/
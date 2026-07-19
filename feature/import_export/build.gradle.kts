@OptIn(ExperimentalKotlinGradlePluginApi::class)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
    id("com.github.beresfordt.gradle.plugins.flatbuffers") version "1.0.0" // Hypothetical plugin ID for FlatBuffers Gradle plugin
    kotlin("native.cocoapods") // If sharing native logic, though primarily Android here
}

android {
    namespace = "com.prisma.import_export"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // NDK Configuration for Assimp, BasisU, KTX, Filament JNI
        externalNativeBuild {
            cmake {
                version "3.22.1"
                arguments "-DANDROID_STL=c++_shared",
                    "-DCMAKE_CXX_STANDARD=20",
                    "-DCMAKE_CXX_STANDARD_REQUIRED=ON",
                    "-DPRISMA_ENABLE_ASSIMP=ON",
                    "-DPRISMA_ENABLE_BASISU=ON",
                    "-DPRISMA_ENABLE_KTX=ON"
                targets "prisma_import_export_jni"
            }
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64")) // Restrict for CI/Release size if needed
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
        // Ensure native libs are not stripped/compressed unnecessarily
        doNotStrip.addAll(listOf("**/libprisma_import_export_jni.so", "**/libassimp.so", "**/libbasisu.so", "**/libktx.so"))
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xopt-in=com.prisma.common.ExperimentalPrismaApi"
        )
    }

    // Protobuf Generation Config
    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:3.21.12"
        }
        plugins {
            id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.1" } // If gRPC needed, else just lite/javalite
            id("javalite") { artifact = "com.google.protobuf:protoc-gen-javalite:3.21.12" }
        }
        generateProtoTasks {
            all().forEach { task ->
                task.builtins {
                    id("java") { option("lite") } // Use lite runtime for Android
                    // id("grpckt") // Enable if gRPC stubs needed
                }
            }
        }
    }

    // FlatBuffers Generation Config (using community plugin or manual task)
    // Assuming com.github.beresfordt.gradle.plugins.flatbuffers plugin applied
    tasks.withType<com.github.beresfordt.gradle.plugins.flatbuffers.FlatBuffersGenerateTask>().configureEach {
        // schemaDir = file("src/main/flatbuffers")
        // outputDir = file("build/generated/flatbuffers")
    }
}

dependencies {
    // Core Modules
    implementation(project(":core:common"))
    implementation(project(":core:engine")) // Provides Filament, Assimp native bindings, Math primitives

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.6.3")
    // implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.3") // Optional

    // I/O & Streams
    implementation("com.squareup.okio:okio:3.5.0")
    implementation("com.squareup.okio:okio-jvm:3.5.0") // Explicit JVM artifact if needed

    // Protobuf Runtime (Lite for Android)
    implementation("com.google.protobuf:protobuf-javalite:3.21.12")
    // gRPC if used for streaming assets
    // implementation("io.grpc:grpc-kotlin-stub:1.5.1")

    // FlatBuffers Runtime
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")

    // Image/Texture Processing (Java/Kotlin side wrappers)
    implementation("com.github.kornelski:ktx-java:1.0") // Hypothetical KTX parser, usually native
    // BasisU/KTX handled via Native (CMake) -> JNI Bridge in core:engine or here

    // glTF Parsing (Alternative to full native: use a Kotlin parser for JSON/Binary structure, delegate heavy lifting to native)
    // implementation("com.google.code.gson:gson:2.10.1") // If not using kotlinx-serialization for glTF JSON
    // We use kotlinx-serialization for glTF JSON schema mapping.

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-android:2.0.9")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Task to copy generated Protobuf/FlatBuffers sources to shared location if needed by other modules (unlikely for library)
tasks.named("generateProto") {
    group = "build"
    description = "Generates Protobuf sources for Prisma Native Format"
}

tasks.named("generateFlatBuffers") {
    group = "build"
    description = "Generates FlatBuffers sources for Prisma Native Format (Binary)"
}

// Ensure native libs are packaged
tasks.named("mergeDebugJniLibFolders").configure {
    doLast {
        // Verify Assimp/BasisU libs exist in jniLibs
    }
}
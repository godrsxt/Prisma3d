cmake_minimum_required(VERSION 3.22)
project(prisma_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Find Filament via Prefab (Android NDK)
find_package(filament REQUIRED CONFIG)

# Find glslang
find_package(glslang REQUIRED CONFIG)

# Find spirv-cross
find_package(spirv-cross REQUIRED CONFIG)

# Find assimp
find_package(assimp REQUIRED CONFIG)

# Find glm (header-only)
find_package(glm REQUIRED CONFIG)

# Android NDK libraries
find_library(android-lib android)
find_library(log-lib log)
find_library(EGL-lib EGL)
find_library(GLESv3-lib GLESv3)

# JNI header generation
set(JAVA_CLASSES
    "com.prisma.engine.render.Renderer"
    "com.prisma.engine.scene.SceneManager"
    "com.prisma.engine.asset.AssetLoader"
    "com.prisma.engine.shader.ShaderCompiler"
    "com.prisma.engine.physics.PhysicsBridge"
)

set(JNI_HEADERS_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated/jni)
file(MAKE_DIRECTORY ${JNI_HEADERS_DIR})

add_custom_command(
    OUTPUT ${JNI_HEADERS_DIR}/com_prisma_engine_render_Renderer.h
           ${JNI_HEADERS_DIR}/com_prisma_engine_scene_SceneManager.h
           ${JNI_HEADERS_DIR}/com_prisma_engine_asset_AssetLoader.h
           ${JNI_HEADERS_DIR}/com_prisma_engine_shader_ShaderCompiler.h
           ${JNI_HEADERS_DIR}/com_prisma_engine_physics_PhysicsBridge.h
    COMMAND ${CMAKE_COMMAND} -E env
            JAVA_HOME=$ENV{JAVA_HOME}
            ${CMAKE_COMMAND} -E make_directory ${JNI_HEADERS_DIR}
    COMMAND javah -d ${JNI_HEADERS_DIR} -classpath ${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/intermediates/javac/debug/compileDebugJavaWithJavac/classes ${JAVA_CLASSES}
    COMMENT "Generating JNI headers for Java classes"
    VERBATIM
)

add_custom_target(generate_jni_headers DEPENDS
    ${JNI_HEADERS_DIR}/com_prisma_engine_render_Renderer.h
    ${JNI_HEADERS_DIR}/com_prisma_engine_scene_SceneManager.h
    ${JNI_HEADERS_DIR}/com_prisma_engine_asset_AssetLoader.h
    ${JNI_HEADERS_DIR}/com_prisma_engine_shader_ShaderCompiler.h
    ${JNI_HEADERS_DIR}/com_prisma_engine_physics_PhysicsBridge.h
)

# Sources
set(SOURCES
    JniBridge.cpp
    Renderer.cpp
    SceneManager.cpp
    AssetLoader.cpp
    ShaderCompiler.cpp
    PhysicsBridge.cpp
)

# Include directories
include_directories(${JNI_HEADERS_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Shared library
add_library(prisma_native SHARED ${SOURCES})

# Dependencies
add_dependencies(prisma_native generate_jni_headers)

# Link libraries
target_link_libraries(prisma_native
    PRIVATE
        filament::filament
        filament::backend
        filament::gltfio
        glslang::glslang
        spirv-cross::spirv-cross-core
        spirv-cross::spirv-cross-glsl
        spirv-cross::spirv-cross-msl
        spirv-cross::spirv-cross-hlsl
        spirv-cross::spirv-cross-reflect
        spirv-cross::spirv-cross-util
        assimp::assimp
        ${android-lib}
        ${log-lib}
        ${EGL-lib}
        ${GLESv3-lib}
)

# Compile options
target_compile_options(prisma_native PRIVATE
    -Wall -Wextra -Wpedantic
    -Wno-unused-parameter
    -fvisibility=hidden
)

# Android specific
if(ANDROID)
    target_compile_definitions(prisma_native PRIVATE ANDROID)
    target_link_libraries(prisma_native PRIVATE android native_app_glue)
endif()

# Install (optional)
install(TARGETS prisma_native
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)
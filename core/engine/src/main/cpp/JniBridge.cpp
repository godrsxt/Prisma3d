#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <unordered_map>
#include "Engine.h"
#include "SceneManager.h"
#include "InputManager.h"
#include "CommandProcessor.h"

#define LOG_TAG "JniBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* gJavaVM = nullptr;
static std::mutex gJniMutex;

static jclass gJniBridgeClass = nullptr;
static jmethodID gOnRenderCompleteMethod = nullptr;
static jmethodID gOnLogMessageMethod = nullptr;
static jmethodID gOnSceneLoadedMethod = nullptr;
static jmethodID gOnCommandResultMethod = nullptr;

static jobject gJniBridgeInstance = nullptr;

struct ThreadAttachment {
    JNIEnv* env;
    bool attached;
    
    ThreadAttachment() : env(nullptr), attached(false) {
        jint result = gJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            JavaVMAttachArgs args = {JNI_VERSION_1_6, nullptr, nullptr};
            result = gJavaVM->AttachCurrentThread(&env, &args);
            if (result == JNI_OK) {
                attached = true;
            }
        }
    }
    
    ~ThreadAttachment() {
        if (attached && gJavaVM) {
            gJavaVM->DetachCurrentThread();
        }
    }
    
    operator JNIEnv*() const { return env; }
    JNIEnv* operator->() const { return env; }
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJavaVM = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv in JNI_OnLoad");
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/example/engine/JniBridge");
    if (!localClass) {
        LOGE("Failed to find JniBridge class");
        return JNI_ERR;
    }
    
    gJniBridgeClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    
    gOnRenderCompleteMethod = env->GetMethodID(gJniBridgeClass, "onRenderComplete", "(J)V");
    gOnLogMessageMethod = env->GetMethodID(gJniBridgeClass, "onLogMessage", "(ILjava/lang/String;)V");
    gOnSceneLoadedMethod = env->GetMethodID(gJniBridgeClass, "onSceneLoaded", "(ZLjava/lang/String;)V");
    gOnCommandResultMethod = env->GetMethodID(gJniBridgeClass, "onCommandResult", "(Ljava/lang/String;)V");
    
    if (!gOnRenderCompleteMethod || !gOnLogMessageMethod || 
        !gOnSceneLoadedMethod || !gOnCommandResultMethod) {
        LOGE("Failed to get method IDs");
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
        {"nativeInitEngine", "(Landroid/content/res/AssetManager;)Z", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeInitEngine)},
        {"nativeResizeViewport", "(II)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeResizeViewport)},
        {"nativeRenderFrame", "(F)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeRenderFrame)},
        {"nativeOnTouchEvent", "(IFFF)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeOnTouchEvent)},
        {"nativeLoadScene", "(Ljava/lang/String;)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeLoadScene)},
        {"nativeExportScene", "(Ljava/lang/String;)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeExportScene)},
        {"nativeExecuteCommand", "(Ljava/lang/String;)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeExecuteCommand)},
        {"nativeShutdown", "()V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeShutdown)},
        {"nativeSetCallbackInstance", "(Lcom/example/engine/JniBridge;)V", reinterpret_cast<void*>(Java_com_example_engine_JniBridge_nativeSetCallbackInstance)}
    };

    if (env->RegisterNatives(gJniBridgeClass, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        LOGE("Failed to register native methods");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad completed successfully");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    std::lock_guard<std::mutex> lock(gJniMutex);
    
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (gJniBridgeInstance) {
            env->DeleteGlobalRef(gJniBridgeInstance);
            gJniBridgeInstance = nullptr;
        }
        if (gJniBridgeClass) {
            env->DeleteGlobalRef(gJniBridgeClass);
            gJniBridgeClass = nullptr;
        }
    }
    
    Engine::getInstance().shutdown();
    gJavaVM = nullptr;
    LOGI("JNI_OnUnload completed");
}

static void sendLogMessage(int level, const std::string& message) {
    ThreadAttachment attachment;
    if (attachment && gJniBridgeInstance && gOnLogMessageMethod) {
        jstring jMessage = attachment->NewStringUTF(message.c_str());
        attachment->CallVoidMethod(gJniBridgeInstance, gOnLogMessageMethod, level, jMessage);
        attachment->DeleteLocalRef(jMessage);
        if (attachment->ExceptionCheck()) {
            attachment->ExceptionClear();
            LOGE("Exception in onLogMessage callback");
        }
    }
}

static void sendRenderComplete(int64_t frameTimeNs) {
    ThreadAttachment attachment;
    if (attachment && gJniBridgeInstance && gOnRenderCompleteMethod) {
        attachment->CallVoidMethod(gJniBridgeInstance, gOnRenderCompleteMethod, frameTimeNs);
        if (attachment->ExceptionCheck()) {
            attachment->ExceptionClear();
            LOGE("Exception in onRenderComplete callback");
        }
    }
}

static void sendSceneLoaded(bool success, const std::string& path) {
    ThreadAttachment attachment;
    if (attachment && gJniBridgeInstance && gOnSceneLoadedMethod) {
        jstring jPath = attachment->NewStringUTF(path.c_str());
        attachment->CallVoidMethod(gJniBridgeInstance, gOnSceneLoadedMethod, success, jPath);
        attachment->DeleteLocalRef(jPath);
        if (attachment->ExceptionCheck()) {
            attachment->ExceptionClear();
            LOGE("Exception in onSceneLoaded callback");
        }
    }
}

static void sendCommandResult(const std::string& result) {
    ThreadAttachment attachment;
    if (attachment && gJniBridgeInstance && gOnCommandResultMethod) {
        jstring jResult = attachment->NewStringUTF(result.c_str());
        attachment->CallVoidMethod(gJniBridgeInstance, gOnCommandResultMethod, jResult);
        attachment->DeleteLocalRef(jResult);
        if (attachment->ExceptionCheck()) {
            attachment->ExceptionClear();
            LOGE("Exception in onCommandResult callback");
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_engine_JniBridge_nativeInitEngine(
    JNIEnv* env, jobject thiz, jobject assetManager) {
    
    std::lock_guard<std::mutex> lock(gJniMutex);
    
    if (gJniBridgeInstance) {
        env->DeleteGlobalRef(gJniBridgeInstance);
    }
    gJniBridgeInstance = env->NewGlobalRef(thiz);
    
    AAssetManager* assetMgr = AAssetManager_fromJava(env, assetManager);
    if (!assetMgr) {
        LOGE("Failed to get AssetManager");
        return JNI_FALSE;
    }
    
    Engine& engine = Engine::getInstance();
    engine.setAssetManager(assetMgr);
    engine.setLogCallback(sendLogMessage);
    engine.setRenderCompleteCallback(sendRenderComplete);
    
    if (!engine.initialize()) {
        LOGE("Engine initialization failed");
        return JNI_FALSE;
    }
    
    LOGI("Engine initialized successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeResizeViewport(
    JNIEnv* env, jobject thiz, jint width, jint height) {
    
    Engine& engine = Engine::getInstance();
    engine.resizeViewport(width, height);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeRenderFrame(
    JNIEnv* env, jobject thiz, jfloat deltaTime) {
    
    Engine& engine = Engine::getInstance();
    engine.renderFrame(deltaTime);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeOnTouchEvent(
    JNIEnv* env, jobject thiz, jint action, jfloat x, jfloat y, jfloat pressure) {
    
    InputManager& input = InputManager::getInstance();
    TouchAction touchAction;
    switch (action) {
        case 0: touchAction = TouchAction::DOWN; break;
        case 1: touchAction = TouchAction::UP; break;
        case 2: touchAction = TouchAction::MOVE; break;
        case 3: touchAction = TouchAction::CANCEL; break;
        default: touchAction = TouchAction::UNKNOWN; break;
    }
    input.processTouchEvent(touchAction, x, y, pressure);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeLoadScene(
    JNIEnv* env, jobject thiz, jstring path) {
    
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    std::string scenePath(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);
    
    SceneManager& sceneManager = SceneManager::getInstance();
    sceneManager.loadSceneAsync(scenePath, [scenePath](bool success) {
        sendSceneLoaded(success, scenePath);
    });
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeExportScene(
    JNIEnv* env, jobject thiz, jstring path) {
    
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    std::string scenePath(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);
    
    SceneManager& sceneManager = SceneManager::getInstance();
    sceneManager.exportSceneAsync(scenePath, [scenePath](bool success, const std::string& message) {
        sendLogMessage(success ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, 
                      "Scene export " + std::string(success ? "succeeded" : "failed") + ": " + message);
    });
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeExecuteCommand(
    JNIEnv* env, jobject thiz, jstring jsonCommand) {
    
    const char* cmdStr = env->GetStringUTFChars(jsonCommand, nullptr);
    std::string command(cmdStr);
    env->ReleaseStringUTFChars(jsonCommand, cmdStr);
    
    CommandProcessor& processor = CommandProcessor::getInstance();
    processor.executeCommandAsync(command, [](const std::string& result) {
        sendCommandResult(result);
    });
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeShutdown(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(gJniMutex);
    
    Engine::getInstance().shutdown();
    
    if (gJniBridgeInstance) {
        env->DeleteGlobalRef(gJniBridgeInstance);
        gJniBridgeInstance = nullptr;
    }
    
    LOGI("Engine shutdown complete");
}

extern "C" JNIEXPORT void JNICALL Java_com_example_engine_JniBridge_nativeSetCallbackInstance(
    JNIEnv* env, jobject thiz, jobject callbackInstance) {
    
    std::lock_guard<std::mutex> lock(gJniMutex);
    
    if (gJniBridgeInstance) {
        env->DeleteGlobalRef(gJniBridgeInstance);
    }
    gJniBridgeInstance = env->NewGlobalRef(callbackInstance);
    
    LOGI("Callback instance updated");
}

JNIEnv* getJNIEnv() {
    JNIEnv* env;
    jint result = gJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        JavaVMAttachArgs args = {JNI_VERSION_1_6, nullptr, nullptr};
        result = gJavaVM->AttachCurrentThread(&env, &args);
        if (result != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

void detachCurrentThread() {
    if (gJavaVM) {
        gJavaVM->DetachCurrentThread();
    }
}

JavaVM* getJavaVM() {
    return gJavaVM;
}

jclass getJniBridgeClass() {
    return gJniBridgeClass;
}

jobject getJniBridgeInstance() {
    return gJniBridgeInstance;
}
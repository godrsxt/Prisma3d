#include "Renderer.h"

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/SwapChain.h>
#include <filament/Camera.h>
#include <filament/Texture.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/RenderableManager.h>
#include <filament/TransformManager.h>
#include <filament/LightManager.h>
#include <filament/IndirectLight.h>
#include <filament/Skybox.h>
#include <filament/TextureSampler.h>
#include <filament/Fence.h>
#include <filament/VertexAttribute.h>

#include <utils/EntityManager.h>
#include <utils/Path.h>
#include <utils/Log.h>

#include <math/mat4.h>
#include <math/vec3.h>
#include <math/vec4.h>

#include <unordered_map>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <iostream>

using namespace filament;
using namespace utils;
using namespace math;

namespace thermion {

// Internal Resource Cache Implementation
class Renderer::ResourceCache {
public:
    explicit ResourceCache(Engine* engine) : mEngine(engine) {}
    ~ResourceCache() { clear(); }

    // Textures
    Texture* getTexture(const std::string& name) {
        std::lock_guard<std::mutex> lock(mMutex);
        auto it = mTextures.find(name);
        return it != mTextures.end() ? it->second : nullptr;
    }

    Texture* createTexture(const std::string& name, Texture::PixelBufferDescriptor&& buffer, Texture::Format format, uint32_t width, uint32_t height, uint8_t levels, bool srgb) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (auto it = mTextures.find(name); it != mTextures.end()) return it->second;

        Texture::Builder builder;
        builder.width(width).height(height).levels(levels).format(format);
        if (srgb) builder.srgb(true);
        Texture* texture = builder.build(*mEngine);
        texture->setImage(mEngine, 0, std::move(buffer));
        mTextures[name] = texture;
        return texture;
    }

    Texture* createCubemap(const std::string& name, std::array<Texture::PixelBufferDescriptor, 6>&& buffers, uint32_t size, Texture::Format format, uint8_t levels, bool srgb) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (auto it = mTextures.find(name); it != mTextures.end()) return it->second;

        Texture::Builder builder;
        builder.width(size).height(size).levels(levels).format(format).sampler(Texture::Sampler::SAMPLER_CUBEMAP);
        if (srgb) builder.srgb(true);
        Texture* texture = builder.build(*mEngine);
        for (uint8_t face = 0; face < 6; ++face) {
            texture->setImage(mEngine, 0, face, std::move(buffers[face]));
        }
        mTextures[name] = texture;
        return texture;
    }

    // Materials
    Material* getMaterial(const std::string& name) {
        std::lock_guard<std::mutex> lock(mMutex);
        auto it = mMaterials.find(name);
        return it != mMaterials.end() ? it->second : nullptr;
    }

    Material* createMaterial(const std::string& name, const void* data, size_t size) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (auto it = mMaterials.find(name); it != mMaterials.end()) return it->second;
        Material* material = Material::Builder().package(data, size).build(*mEngine);
        mMaterials[name] = material;
        return material;
    }

    // Vertex Buffers
    VertexBuffer* createVertexBuffer(const VertexBuffer::Builder& builder) {
        return builder.build(*mEngine);
    }

    // Index Buffers
    IndexBuffer* createIndexBuffer(const IndexBuffer::Builder& builder) {
        return builder.build(*mEngine);
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mMutex);
        for (auto& [_, t] : mTextures) mEngine->destroy(t);
        for (auto& [_, m] : mMaterials) mEngine->destroy(m);
        mTextures.clear();
        mMaterials.clear();
    }

private:
    Engine* mEngine;
    std::mutex mMutex;
    std::unordered_map<std::string, Texture*> mTextures;
    std::unordered_map<std::string, Material*> mMaterials;
};

// Renderer Implementation
Renderer::Renderer(void* nativeWindow, uint32_t width, uint32_t height)
    : mWidth(width), mHeight(height), mNativeWindow(nativeWindow) {
    initializeFilament();
    setupPBRPipeline();
}

Renderer::~Renderer() {
    shutdown();
}

void Renderer::initializeFilament() {
    // 1. Create Engine
    Engine::Backend backend = Engine::Backend::DEFAULT; // Vulkan/Metal/OpenGL auto-select
    mEngine = Engine::create(backend);
    if (!mEngine) {
        utils::slog.e << "Failed to create Filament Engine" << utils::io::endl;
        return;
    }

    // 2. Create Resource Cache
    mResourceCache = std::make_unique<ResourceCache>(mEngine);

    // 3. Create SwapChain
    mSwapChain = mEngine->createSwapChain(nativeWindow);
    if (!mSwapChain) {
        utils::slog.e << "Failed to create SwapChain" << utils::io::endl;
        return;
    }

    // 4. Create Renderer
    mFilamentRenderer = mEngine->createRenderer();
    if (!mFilamentRenderer) {
        utils::slog.e << "Failed to create Filament Renderer" << utils::io::endl;
        return;
    }

    // 5. Create Scene
    mScene = mEngine->createScene();
    mScene->setAmbientOcclusionEnabled(true); // Enable AO if supported

    // 6. Create View
    mView = mEngine->createView();
    mView->setScene(mScene);
    mView->setViewport({0, 0, mWidth, mHeight});

    // 7. Create Camera Entity
    mCameraEntity = utils::EntityManager::get().create();
    Camera* camera = mEngine->createCamera(mCameraEntity);
    camera->setExposure(16.0f, 1.0f/125.0f, 100.0f); // EV16, 1/125s, ISO100
    mView->setCamera(camera);

    // 8. Setup Default Light (Sun for Cascaded Shadows)
    mSunEntity = utils::EntityManager::get().create();
    LightManager::Builder(LightManager::Type::SUN)
        .color(Color::srgbToLinear(0.98f, 0.92f, 0.89f))
        .intensity(110000.0f) // Lux
        .direction({0.6f, -1.0f, -0.3f})
        .castShadows(true)
        .sunAngularRadius(0.53f)
        .sunHaloSize(10.0f)
        .sunHaloFalloff(80.0f)
        .build(*mEngine, mSunEntity);
    mScene->addEntity(mSunEntity);

    // 9. Configure Cascaded Shadow Maps (CSM) on View
    mView->setShadowCascadeCount(4); // 4 Cascades
    // Splits: [Near, Split1, Split2, Split3, Far] normalized 0..1
    mView->setShadowCascadeSplit({0.0f, 0.05f, 0.15f, 0.4f, 1.0f});
    mView->setShadowFarDistance(1000.0f); // World units

    // 10. Configure Post Processing
    configurePostProcessing();
}

void Renderer::configurePostProcessing() {
    // Tonemapping: ACES Filmic
    mView->setTonemap(filament::View::Tonemap::ACES_FILMIC);

    // Anti-Aliasing: FXAA (Fast Approximate AA)
    mView->setAntiAliasing(filament::View::AntiAliasing::FXAA);

    // Bloom
    View::BloomOptions bloomOptions;
    bloomOptions.enabled = true;
    bloomOptions.intensity = 0.8f;
    bloomOptions.threshold = 1.0f; // Luminance threshold
    bloomOptions.knee = 0.5f;
    // Default resolution scales are usually fine (1/2, 1/4, 1/8)
    mView->setBloomOptions(bloomOptions);

    // Vignette (Optional subtle)
    // mView->setVignetteOptions({true, 0.5f, 0.8f, Color::srgbToLinear(0.0f, 0.0f, 0.0f)});

    // Depth of Field (Disabled by default)
    // mView->setDepthOfFieldOptions({false, ...});
}

void Renderer::setupPBRPipeline() {
    // IBL Setup requires loading KTX cubemaps.
    // In a real engine, these are loaded from assets. Here we define the setup logic.
    // The actual texture loading (KTX parsing) is omitted for brevity but assumed in loadIBL().
    
    // Create a default "Empty" IBL so the renderer doesn't crash if none set.
    IndirectLight::Builder iblBuilder;
    // Create a 1x1 white texture for empty irradiance/specular
    Texture* dummyTex = createDummyTexture();
    if (dummyTex) {
        IndirectLight* indirectLight = iblBuilder
            .reflections(dummyTex)
            .irradiance(3, dummyTex) // 3 bands SH
            .intensity(1.0f)
            .build(*mEngine);
        mScene->setIndirectLight(indirectLight);
        mDummyTexture = dummyTex; // Keep alive
    }

    // Skybox
    Skybox* skybox = Skybox::Builder().environment(dummyTex).showSun(true).build(*mEngine);
    mScene->setSkybox(skybox);
    mDummySkyboxTexture = dummyTex; // Reuse or separate
}

Texture* Renderer::createDummyTexture() {
    // 1x1 White RGBA8
    uint32_t white = 0xFFFFFFFF;
    Texture::PixelBufferDescriptor buffer(&white, 4, Texture::Format::R8G8B8A8_SRGB, 
        [](void*, size_t, void*){}, nullptr);
    return mResourceCache->createTexture("__dummy_white__", std::move(buffer), Texture::Format::R8G8B8A8_SRGB, 1, 1, 1, true);
}

void Renderer::loadIBL(const std::string& skyboxPath, const std::string& irradiancePath, const std::string& specularPath) {
    // NOTE: Actual implementation requires KTX2 loader (ktx.h) to parse mip levels and cubemap faces.
    // This is a placeholder for the API calls.
    /*
    Texture* skyboxTex = loadKTXCubemap(skyboxPath, true);  // sRGB
    Texture* irradianceTex = loadKTXCubemap(irradiancePath, false); // Linear
    Texture* specularTex = loadKTXCubemap(specularPath, false); // Linear, Mipmapped

    if (skyboxTex && irradianceTex && specularTex) {
        // Update Skybox
        Skybox* skybox = Skybox::Builder().environment(skyboxTex).showSun(true).build(*mEngine);
        mScene->setSkybox(skybox);
        if (mSkyboxTexture) mEngine->destroy(mSkyboxTexture);
        mSkyboxTexture = skyboxTex;

        // Update Indirect Light
        // Irradiance is usually 3 bands Spherical Harmonics (SH), Filament expects a cubemap for SH coefficients 
        // or a texture with specific layout. Filament's IndirectLight::Builder::irradiance takes (bands, texture).
        // Typically irradiance map is a 32x32 cubemap.
        IndirectLight* indirectLight = IndirectLight::Builder()
            .reflections(specularTex)
            .irradiance(3, irradianceTex) // 3 bands
            .intensity(30000.0f) // Adjust based on scene units
            .build(*mEngine);
        mScene->setIndirectLight(indirectLight);
        if (mIndirectLight) mEngine->destroy(mIndirectLight);
        mIndirectLight = indirectLight;
        if (mIrradianceTexture) mEngine->destroy(mIrradianceTexture);
        mIrradianceTexture = irradianceTex;
        if (mSpecularTexture) mEngine->destroy(mSpecularTexture);
        mSpecularTexture = specularTex;
    }
    */
    (void)skyboxPath; (void)irradiancePath; (void)specularPath;
}

void Renderer::resize(uint32_t width, uint32_t height) {
    if (mSwapChain && (width != mWidth || height != mHeight)) {
        mWidth = width;
        mHeight = height;
        mSwapChain->resize(width, height);
        mView->setViewport({0, 0, width, height});
        
        // Update Camera Aspect Ratio
        Camera* camera = mEngine->getCameraComponent(mCameraEntity);
        if (camera) {
            camera->setProjectionFov(45.0f, float(width) / float(height), 0.1f, 10000.0f);
        }
    }
}

void Renderer::renderFrame() {
    if (!mFilamentRenderer || !mSwapChain || !mView) return;

    // 1. Begin Frame
    // Filament handles triple buffering internally. beginFrame returns a fence to wait on if GPU is behind.
    Fence::Ptr fence = mFilamentRenderer->beginFrame(mSwapChain);
    if (fence) {
        // Optional: Wait on CPU if we are submitting too fast (vsync usually handles this in swapchain present)
        // fence->wait(Fence::WAIT_FOREVER); 
    }

    // 2. Update Animation & Transforms (Game Thread Logic)
    // This is where you'd call AnimationSystem::update(dt) which writes to TransformManager.
    updateTransforms(1.0f / 60.0f); // Placeholder dt

    // 3. Frustum Culling
    // Filament performs frustum culling internally during render(View) using the View's camera.
    // No explicit manual culling call needed unless using custom RenderableManager::CullingContext.
    // However, we can update the view's culling parameters if needed.
    
    // 4. Render
    // This submits commands to the GPU.
    mFilamentRenderer->render(mView);

    // 5. End Frame & Present
    mFilamentRenderer->endFrame();
}

void Renderer::updateTransforms(float dt) {
    // Example: Update a rotating entity if exists
    // In a real engine, this iterates an ECS Animation System.
    (void)dt;
    
    // Ensure TransformManager updates are flushed if modified directly.
    // Filament's TransformManager is immediate usually, but hierarchy changes need notification.
    // mEngine->getTransformManager().updateGlobalTransforms(); // Not strictly needed per frame unless hierarchy changed.
}

void Renderer::setCameraTransform(const mat4f& worldTransform) {
    // Filament Camera looks down -Z, Up +Y.
    // worldTransform is Model Matrix (Local -> World).
    // View Matrix = Inverse(WorldTransform).
    mat4f viewMatrix = inverse(worldTransform);
    Camera* camera = mEngine->getCameraComponent(mCameraEntity);
    if (camera) {
        camera->setModelMatrix(worldTransform); // Filament Camera uses Model Matrix (World transform of camera entity)
        // Alternatively: camera->setViewMatrix(viewMatrix); 
        // But setModelMatrix is preferred for hierarchy consistency.
    }
}

void Renderer::setCameraProjection(float fovDegrees, float nearPlane, float farPlane) {
    Camera* camera = mEngine->getCameraComponent(mCameraEntity);
    if (camera) {
        float aspect = float(mWidth) / float(mHeight);
        camera->setProjectionFov(fovDegrees, aspect, nearPlane, farPlane);
    }
}

void Renderer::setSunDirection(const float3& direction, float intensity) {
    LightManager* lm = mEngine->getLightManager();
    if (lm->hasComponent(mSunEntity)) {
        LightManager::Instance instance = lm->getInstance(mSunEntity);
        lm->setDirection(instance, normalize(direction));
        lm->setIntensity(instance, intensity);
        lm->setColor(instance, Color::srgbToLinear(0.98f, 0.92f, 0.89f));
    }
}

void Renderer::addRenderable(Entity entity, RenderableManager::Builder& builder, MaterialInstance* materialInstance) {
    // Build renderable (geometry + material binding)
    builder.build(*mEngine, entity);
    if (materialInstance) {
        RenderableManager* rm = mEngine->getRenderableManager();
        rm->setMaterialInstanceAt(entity, 0, materialInstance);
    }
    mScene->addEntity(entity);
}

void Renderer::removeRenderable(Entity entity) {
    mScene->remove(entity);
    // Note: Engine destroys renderable component automatically on entity destruction 
    // or explicitly via RenderableManager::destroy.
}

MaterialInstance* Renderer::createMaterialInstance(const std::string& materialName) {
    Material* mat = mResourceCache->getMaterial(materialName);
    if (!mat) {
        // Load default material package (compiled .matc/.filamat)
        // mat = mResourceCache->createMaterial(materialName, defaultMaterialData, defaultMaterialSize);
        return nullptr;
    }
    return mat->createInstance();
}

VertexBuffer* Renderer::createVertexBuffer(const VertexBuffer::Builder& builder) {
    return mResourceCache->createVertexBuffer(builder);
}

IndexBuffer* Renderer::createIndexBuffer(const IndexBuffer::Builder& builder) {
    return mResourceCache->createIndexBuffer(builder);
}

Texture* Renderer::createTexture2D(const std::string& name, const void* data, size_t size, uint32_t width, uint32_t height, Texture::Format format, bool srgb, uint8_t levels) {
    Texture::PixelBufferDescriptor buffer(data, size, format, [](void*, size_t, void*){}, nullptr);
    return mResourceCache->createTexture(name, std::move(buffer), format, width, height, levels, srgb);
}

void Renderer::shutdown() {
    if (mFilamentRenderer) {
        mEngine->destroy(mFilamentRenderer);
        mFilamentRenderer = nullptr;
    }
    if (mView) {
        mEngine->destroy(mView);
        mView = nullptr;
    }
    if (mScene) {
        mEngine->destroy(mScene);
        mScene = nullptr;
    }
    if (mSwapChain) {
        mEngine->destroy(mSwapChain);
        mSwapChain = nullptr;
    }
    
    // Destroy Camera Entity components
    if (mCameraEntity) {
        Camera* cam = mEngine->getCameraComponent(mCameraEntity);
        if (cam) mEngine->destroy(cam);
        utils::EntityManager::get().destroy(mCameraEntity);
    }
    if (mSunEntity) {
        LightManager* lm = mEngine->getLightManager();
        if (lm->hasComponent(mSunEntity)) lm->destroy(lm->getInstance(mSunEntity));
        utils::EntityManager::get().destroy(mSunEntity);
    }

    // Cleanup Resources
    if (mResourceCache) {
        mResourceCache->clear();
        mResourceCache.reset();
    }
    
    // Destroy dummy textures held by Renderer
    if (mDummyTexture) mEngine->destroy(mDummyTexture);
    if (mDummySkyboxTexture) mEngine->destroy(mDummySkyboxTexture);
    if (mIrradianceTexture) mEngine->destroy(mIrradianceTexture);
    if (mSpecularTexture) mEngine->destroy(mSpecularTexture);
    if (mSkyboxTexture) mEngine->destroy(mSkyboxTexture);
    if (mIndirectLight) mEngine->destroy(mIndirectLight);

    if (mEngine) {
        Engine::destroy(&mEngine);
        mEngine = nullptr;
    }
}

} // namespace thermion
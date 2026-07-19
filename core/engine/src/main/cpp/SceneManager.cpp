#include "SceneManager.h"
#include "JniHelpers.h"
#include "FilamentEngine.h"
#include "AnimationSystem.h"
#include "PhysicsSystem.h"

#include <filament/Engine.h>
#include <filament/EntityManager.h>
#include <filament/TransformManager.h>
#include <filament/RenderableManager.h>
#include <filament/LightManager.h>
#include <filament/CameraManager.h>
#include <filament/SkinningBuffer.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/Texture.h>
#include <filament/Skybox.h>
#include <filament/IndirectLight.h>
#include <filament/TextureSampler.h>
#include <filament/Animation.h>
#include <filament/Animator.h>

#include <utils/EntityManager.h>
#include <math/mat4.h>
#include <math/vec3.h>
#include <math/quat.h>

#include <jni.h>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <memory>
#include <mutex>

using namespace filament;
using namespace math;
using namespace utils;

namespace thermion {

struct SceneManager::NodeData {
    Entity entity = EntityManager::get().create();
    Entity parent = EntityManager::get().create();
    std::vector<Entity> children;
    mat4f localTransform = mat4f::identity();
    mat4f worldTransform = mat4f::identity();
    bool dirty = true;
    bool visible = true;
    
    // Components
    RenderableManager::Instance renderableInstance;
    LightManager::Instance lightInstance;
    CameraManager::Instance cameraInstance;
    SkinningBuffer* skinningBuffer = nullptr;
    Animator* animator = nullptr;
    
    // Skinning
    std::vector<mat4f> jointMatrices;
    std::vector<std::string> jointNames;
    std::unordered_map<std::string, size_t> jointIndexMap;
    
    // Physics
    uint64_t physicsBodyId = 0;
    bool hasPhysicsBody = false;
};

struct SceneManager::AnimationLayer {
    std::string name;
    float weight = 1.0f;
    bool additive = false;
    std::vector<AnimationSampler> samplers;
    double currentTime = 0.0;
    bool playing = false;
    bool loop = true;
};

struct SceneManager::AnimationSampler {
    std::string jointName;
    size_t jointIndex = SIZE_MAX;
    std::vector<Keyframe<vec3>> translationKeys;
    std::vector<Keyframe<quat>> rotationKeys;
    std::vector<Keyframe<vec3>> scaleKeys;
};

template<typename T>
struct SceneManager::Keyframe {
    double time;
    T value;
    InterpolationType interpolation = InterpolationType::LINEAR;
};

SceneManager::SceneManager(FilamentEngine* engine, JNIEnv* env, jobject javaSceneManager)
    : mEngine(engine)
    , mFilamentEngine(engine->getFilamentEngine())
    , mEntityManager(EntityManager::get())
    , mTransformManager(mFilamentEngine.getTransformManager())
    , mRenderableManager(mFilamentEngine.getRenderableManager())
    , mLightManager(mFilamentEngine.getLightManager())
    , mCameraManager(mFilamentEngine.getCameraManager())
    , mAnimationSystem(std::make_unique<AnimationSystem>())
    , mPhysicsSystem(std::make_unique<PhysicsSystem>())
    , mJavaSceneManager(env->NewGlobalRef(javaSceneManager))
    , mNextNodeId(1)
    , mRootNode(EntityManager::get().create()) {
    
    mTransformManager.create(mRootNode);
    mTransformManager.setTransform(mRootNode, mat4f::identity());
    
    mAnimationSystem->initialize(mFilamentEngine);
    mPhysicsSystem->initialize();
}

SceneManager::~SceneManager() {
    if (mJavaSceneManager) {
        JNIEnv* env = getJniEnv();
        if (env) {
            env->DeleteGlobalRef(mJavaSceneManager);
        }
    }
    
    for (auto& [id, node] : mNodes) {
        destroyNodeResources(node);
    }
    mNodes.clear();
    
    mAnimationSystem->shutdown();
    mPhysicsSystem->shutdown();
    
    mEntityManager.destroy(mRootNode);
}

Entity SceneManager::createNode(jlong parentId) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    uint64_t nodeId = mNextNodeId++;
    Entity entity = mEntityManager.create();
    mTransformManager.create(entity);
    
    NodeData node;
    node.entity = entity;
    node.parent = (parentId == 0) ? mRootNode : mNodes[parentId].entity;
    
    if (parentId != 0) {
        auto& parentNode = mNodes[parentId];
        parentNode.children.push_back(entity);
        mTransformManager.setParent(entity, parentNode.entity);
    } else {
        mTransformManager.setParent(entity, mRootNode);
    }
    
    mNodes[nodeId] = std::move(node);
    mDirtyNodes.insert(nodeId);
    
    return entity;
}

void SceneManager::destroyNode(jlong nodeId) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    
    for (Entity child : node.children) {
        destroyNodeRecursive(child);
    }
    
    destroyNodeResources(node);
    
    if (node.parent != EntityManager::get().create()) {
        auto& parentNode = mNodes[getNodeId(node.parent)];
        parentNode.children.erase(
            std::remove(parentNode.children.begin(), parentNode.children.end(), node.entity),
            parentNode.children.end()
        );
    }
    
    mEntityManager.destroy(node.entity);
    mNodes.erase(it);
    mDirtyNodes.erase(nodeId);
}

void SceneManager::destroyNodeRecursive(Entity entity) {
    auto it = std::find_if(mNodes.begin(), mNodes.end(),
        [entity](const auto& pair) { return pair.second.entity == entity; });
    
    if (it != mNodes.end()) {
        destroyNode(it->first);
    }
}

uint64_t SceneManager::getNodeId(Entity entity) const {
    for (const auto& [id, node] : mNodes) {
        if (node.entity == entity) return id;
    }
    return 0;
}

void SceneManager::destroyNodeResources(NodeData& node) {
    if (node.renderableInstance) {
        mRenderableManager.destroy(node.renderableInstance);
    }
    if (node.lightInstance) {
        mLightManager.destroy(node.lightInstance);
    }
    if (node.cameraInstance) {
        mCameraManager.destroy(node.cameraInstance);
    }
    if (node.skinningBuffer) {
        mFilamentEngine.destroy(node.skinningBuffer);
        node.skinningBuffer = nullptr;
    }
    if (node.animator) {
        mAnimationSystem->destroyAnimator(node.animator);
        node.animator = nullptr;
    }
    if (node.hasPhysicsBody) {
        mPhysicsSystem->removeBody(node.physicsBodyId);
        node.hasPhysicsBody = false;
    }
}

void SceneManager::setLocalTransform(jlong nodeId, const mat4f& transform) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    node.localTransform = transform;
    node.dirty = true;
    mDirtyNodes.insert(nodeId);
    markChildrenDirty(nodeId);
}

void SceneManager::markChildrenDirty(uint64_t nodeId) {
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    for (Entity childEntity : it->second.children) {
        uint64_t childId = getNodeId(childEntity);
        if (childId != 0) {
            mNodes[childId].dirty = true;
            mDirtyNodes.insert(childId);
            markChildrenDirty(childId);
        }
    }
}

void SceneManager::setVisibility(jlong nodeId, bool visible) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    node.visible = visible;
    
    if (node.renderableInstance) {
        mRenderableManager.setCulling(node.renderableInstance, visible);
    }
}

void SceneManager::setRenderable(jlong nodeId, RenderableManager::Builder&& builder) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    
    if (node.renderableInstance) {
        mRenderableManager.destroy(node.renderableInstance);
    }
    
    node.renderableInstance = builder.build(mFilamentEngine, node.entity);
}

void SceneManager::setLight(jlong nodeId, LightManager::Builder&& builder) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    
    if (node.lightInstance) {
        mLightManager.destroy(node.lightInstance);
    }
    
    node.lightInstance = builder.build(mFilamentEngine, node.entity);
}

void SceneManager::setCamera(jlong nodeId, CameraManager::Builder&& builder) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    
    if (node.cameraInstance) {
        mCameraManager.destroy(node.cameraInstance);
    }
    
    node.cameraInstance = builder.build(mFilamentEngine, node.entity);
}

void SceneManager::setSkinningData(jlong nodeId, const std::vector<std::string>& jointNames,
                                   const std::vector<mat4f>& inverseBindMatrices) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    node.jointNames = jointNames;
    node.jointMatrices.resize(jointNames.size());
    node.jointIndexMap.clear();
    
    for (size_t i = 0; i < jointNames.size(); ++i) {
        node.jointIndexMap[jointNames[i]] = i;
        node.jointMatrices[i] = inverseBindMatrices[i];
    }
    
    if (node.skinningBuffer) {
        mFilamentEngine.destroy(node.skinningBuffer);
    }
    
    node.skinningBuffer = mFilamentEngine.createSkinningBuffer(jointNames.size());
    node.skinningBuffer->setBones(inverseBindMatrices.data(), jointNames.size());
    
    if (node.renderableInstance) {
        mRenderableManager.setSkinningBuffer(node.renderableInstance, node.skinningBuffer);
    }
}

void SceneManager::updateJointMatrices(jlong nodeId, const std::vector<mat4f>& matrices) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end() || !it->second.skinningBuffer) return;
    
    NodeData& node = it->second;
    if (matrices.size() != node.jointMatrices.size()) return;
    
    node.jointMatrices = matrices;
    node.skinningBuffer->setBones(matrices.data(), matrices.size());
}

void SceneManager::addAnimationLayer(jlong nodeId, const std::string& name, float weight,
                                     bool additive, bool loop) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    if (!node.animator) {
        node.animator = mAnimationSystem->createAnimator(node.entity);
    }
    
    AnimationLayer layer;
    layer.name = name;
    layer.weight = weight;
    layer.additive = additive;
    layer.loop = loop;
    node.animationLayers.push_back(std::move(layer));
}

void SceneManager::removeAnimationLayer(jlong nodeId, const std::string& name) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    node.animationLayers.erase(
        std::remove_if(node.animationLayers.begin(), node.animationLayers.end(),
            [&name](const AnimationLayer& layer) { return layer.name == name; }),
        node.animationLayers.end()
    );
}

void SceneManager::setAnimationLayerWeight(jlong nodeId, const std::string& name, float weight) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    for (auto& layer : node.animationLayers) {
        if (layer.name == name) {
            layer.weight = weight;
            break;
        }
    }
}

void SceneManager::playAnimation(jlong nodeId, const std::string& layerName, double time) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    for (auto& layer : node.animationLayers) {
        if (layer.name == layerName) {
            layer.playing = true;
            layer.currentTime = time;
            break;
        }
    }
}

void SceneManager::pauseAnimation(jlong nodeId, const std::string& layerName) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    for (auto& layer : node.animationLayers) {
        if (layer.name == layerName) {
            layer.playing = false;
            break;
        }
    }
}

void SceneManager::stopAnimation(jlong nodeId, const std::string& layerName) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    for (auto& layer : node.animationLayers) {
        if (layer.name == layerName) {
            layer.playing = false;
            layer.currentTime = 0.0;
            break;
        }
    }
}

void SceneManager::loadAnimationClip(jlong nodeId, const std::string& layerName,
                                     const AnimationClipData& clipData) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    for (auto& layer : node.animationLayers) {
        if (layer.name == layerName) {
            layer.samplers.clear();
            layer.samplers.reserve(clipData.channels.size());
            
            for (const auto& channel : clipData.channels) {
                AnimationSampler sampler;
                sampler.jointName = channel.jointName;
                auto jointIt = node.jointIndexMap.find(channel.jointName);
                if (jointIt != node.jointIndexMap.end()) {
                    sampler.jointIndex = jointIt->second;
                }
                
                sampler.translationKeys = channel.translationKeys;
                sampler.rotationKeys = channel.rotationKeys;
                sampler.scaleKeys = channel.scaleKeys;
                
                layer.samplers.push_back(std::move(sampler));
            }
            break;
        }
    }
}

void SceneManager::updateTransforms() {
    std::lock_guard<std::mutex> lock(mMutex);
    
    std::vector<uint64_t> sortedNodes;
    sortedNodes.reserve(mDirtyNodes.size());
    
    for (uint64_t id : mDirtyNodes) {
        sortedNodes.push_back(id);
    }
    
    std::sort(sortedNodes.begin(), sortedNodes.end(),
        [this](uint64_t a, uint64_t b) {
            return getDepth(a) < getDepth(b);
        });
    
    for (uint64_t nodeId : sortedNodes) {
        auto it = mNodes.find(nodeId);
        if (it == mNodes.end()) continue;
        
        NodeData& node = it->second;
        if (!node.dirty) continue;
        
        mat4f parentWorld = mat4f::identity();
        if (node.parent != mRootNode && node.parent != EntityManager::get().create()) {
            uint64_t parentId = getNodeId(node.parent);
            if (parentId != 0) {
                parentWorld = mNodes[parentId].worldTransform;
            }
        }
        
        node.worldTransform = parentWorld * node.localTransform;
        mTransformManager.setTransform(node.entity, node.worldTransform);
        node.dirty = false;
        
        if (node.lightInstance) {
            updateLightTransform(node);
        }
        if (node.cameraInstance) {
            updateCameraTransform(node);
        }
        if (node.hasPhysicsBody) {
            syncPhysicsTransform(node);
        }
    }
    
    mDirtyNodes.clear();
}

int SceneManager::getDepth(uint64_t nodeId) const {
    int depth = 0;
    auto it = mNodes.find(nodeId);
    while (it != mNodes.end() && it->second.parent != mRootNode) {
        uint64_t parentId = getNodeId(it->second.parent);
        if (parentId == 0) break;
        it = mNodes.find(parentId);
        depth++;
    }
    return depth;
}

void SceneManager::updateLightTransform(NodeData& node) {
    if (!node.lightInstance) return;
    
    const auto& transform = node.worldTransform;
    float3 direction = normalize(float3{transform[2].xyz});
    mLightManager.setDirection(node.lightInstance, direction);
    
    if (mLightManager.getType(node.lightInstance) == LightManager::Type::SPOT ||
        mLightManager.getType(node.lightInstance) == LightManager::Type::POINT) {
        float3 position = transform[3].xyz;
        mLightManager.setPosition(node.lightInstance, position);
    }
}

void SceneManager::updateCameraTransform(NodeData& node) {
    if (!node.cameraInstance) return;
    
    const auto& transform = node.worldTransform;
    float3 position = transform[3].xyz;
    float3 target = position + normalize(float3{transform[2].xyz});
    float3 up = normalize(float3{transform[1].xyz});
    
    mCameraManager.setPosition(node.cameraInstance, position);
    mCameraManager.setDirection(node.cameraInstance, target - position);
    mCameraManager.setUp(node.cameraInstance, up);
}

void SceneManager::syncPhysicsTransform(NodeData& node) {
    if (!node.hasPhysicsBody) return;
    
    auto physicsTransform = mPhysicsSystem->getBodyTransform(node.physicsBodyId);
    if (physicsTransform) {
        node.localTransform = *physicsTransform;
        node.dirty = true;
        mDirtyNodes.insert(getNodeId(node.entity));
    }
}

void SceneManager::updateAnimations(double deltaTime) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    for (auto& [nodeId, node] : mNodes) {
        if (!node.animator || node.animationLayers.empty()) continue;
        
        std::vector<mat4f> blendedMatrices(node.jointMatrices.size(), mat4f::identity());
        float totalWeight = 0.0f;
        
        for (auto& layer : node.animationLayers) {
            if (!layer.playing || layer.weight <= 0.0f) continue;
            
            layer.currentTime += deltaTime;
            
            float duration = getLayerDuration(layer);
            if (layer.currentTime >= duration) {
                if (layer.loop) {
                    layer.currentTime = fmod(layer.currentTime, duration);
                } else {
                    layer.currentTime = duration;
                    layer.playing = false;
                }
            }
            
            sampleAnimationLayer(layer, node, blendedMatrices);
            totalWeight += layer.weight;
        }
        
        if (totalWeight > 0.0f) {
            for (auto& mat : blendedMatrices) {
                mat = mat * (1.0f / totalWeight);
            }
            node.skinningBuffer->setBones(blendedMatrices.data(), blendedMatrices.size());
        }
    }
}

float SceneManager::getLayerDuration(const AnimationLayer& layer) const {
    float maxTime = 0.0f;
    for (const auto& sampler : layer.samplers) {
        if (!sampler.translationKeys.empty()) {
            maxTime = std::max(maxTime, (float)sampler.translationKeys.back().time);
        }
        if (!sampler.rotationKeys.empty()) {
            maxTime = std::max(maxTime, (float)sampler.rotationKeys.back().time);
        }
        if (!sampler.scaleKeys.empty()) {
            maxTime = std::max(maxTime, (float)sampler.scaleKeys.back().time);
        }
    }
    return maxTime;
}

void SceneManager::sampleAnimationLayer(const AnimationLayer& layer, const NodeData& node,
                                        std::vector<mat4f>& outMatrices) const {
    for (const auto& sampler : layer.samplers) {
        if (sampler.jointIndex >= outMatrices.size()) continue;
        
        mat4f localTransform = mat4f::identity();
        
        if (!sampler.translationKeys.empty()) {
            vec3 translation = interpolateKeyframes(sampler.translationKeys, layer.currentTime);
            localTransform = translate(localTransform, translation);
        }
        
        if (!sampler.rotationKeys.empty()) {
            quat rotation = interpolateKeyframes(sampler.rotationKeys, layer.currentTime);
            localTransform = localTransform * mat4f::fromQuat(rotation);
        }
        
        if (!sampler.scaleKeys.empty()) {
            vec3 scale = interpolateKeyframes(sampler.scaleKeys, layer.currentTime);
            localTransform = scale(localTransform, scale);
        }
        
        if (layer.additive) {
            outMatrices[sampler.jointIndex] = outMatrices[sampler.jointIndex] * localTransform;
        } else {
            outMatrices[sampler.jointIndex] = lerp(outMatrices[sampler.jointIndex], localTransform, layer.weight);
        }
    }
}

template<typename T>
T SceneManager::interpolateKeyframes(const std::vector<Keyframe<T>>& keys, double time) const {
    if (keys.empty()) return T{};
    if (keys.size() == 1) return keys[0].value;
    
    if (time <= keys.front().time) return keys.front().value;
    if (time >= keys.back().time) return keys.back().value;
    
    auto it = std::lower_bound(keys.begin(), keys.end(), time,
        [](const Keyframe<T>& k, double t) { return k.time < t; });
    
    if (it == keys.begin()) return it->value;
    
    const Keyframe<T>& next = *it;
    const Keyframe<T>& prev = *(it - 1);
    
    double t = (time - prev.time) / (next.time - prev.time);
    
    switch (prev.interpolation) {
        case InterpolationType::LINEAR:
            return lerp(prev.value, next.value, (float)t);
        case InterpolationType::STEP:
            return prev.value;
        case InterpolationType::CUBICSPLINE:
            return cubicSplineInterpolate(prev, next, (float)t);
        default:
            return lerp(prev.value, next.value, (float)t);
    }
}

vec3 SceneManager::lerp(const vec3& a, const vec3& b, float t) const {
    return a + (b - a) * t;
}

quat SceneManager::lerp(const quat& a, const quat& b, float t) const {
    return slerp(a, b, t);
}

mat4f SceneManager::lerp(const mat4f& a, const mat4f& b, float t) const {
    mat4f result;
    for (int i = 0; i < 16; ++i) {
        result[i / 4][i % 4] = a[i / 4][i % 4] + (b[i / 4][i % 4] - a[i / 4][i % 4]) * t;
    }
    return result;
}

vec3 SceneManager::cubicSplineInterpolate(const Keyframe<vec3>& prev, const Keyframe<vec3>& next, float t) const {
    float t2 = t * t;
    float t3 = t2 * t;
    
    vec3 p0 = prev.value;
    vec3 p1 = next.value;
    vec3 m0 = prev.outTangent * (next.time - prev.time);
    vec3 m1 = next.inTangent * (next.time - prev.time);
    
    return (2 * t3 - 3 * t2 + 1) * p0 +
           (t3 - 2 * t2 + t) * m0 +
           (-2 * t3 + 3 * t2) * p1 +
           (t3 - t2) * m1;
}

quat SceneManager::cubicSplineInterpolate(const Keyframe<quat>& prev, const Keyframe<quat>& next, float t) const {
    return slerp(prev.value, next.value, t);
}

void SceneManager::stepPhysics(double deltaTime, int subSteps) {
    mPhysicsSystem->stepSimulation(deltaTime, subSteps);
    
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto& [nodeId, node] : mNodes) {
        if (node.hasPhysicsBody) {
            auto transform = mPhysicsSystem->getBodyTransform(node.physicsBodyId);
            if (transform) {
                node.localTransform = *transform;
                node.dirty = true;
                mDirtyNodes.insert(nodeId);
            }
        }
    }
}

void SceneManager::addPhysicsBody(jlong nodeId, const PhysicsBodyConfig& config) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    if (node.hasPhysicsBody) return;
    
    node.physicsBodyId = mPhysicsSystem->createBody(config, node.worldTransform);
    node.hasPhysicsBody = (node.physicsBodyId != 0);
}

void SceneManager::removePhysicsBody(jlong nodeId) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end()) return;
    
    NodeData& node = it->second;
    if (node.hasPhysicsBody) {
        mPhysicsSystem->removeBody(node.physicsBodyId);
        node.hasPhysicsBody = false;
        node.physicsBodyId = 0;
    }
}

void SceneManager::applyPhysicsForce(jlong nodeId, const float3& force, const float3& position) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end() || !it->second.hasPhysicsBody) return;
    
    mPhysicsSystem->applyForce(it->second.physicsBodyId, force, position);
}

void SceneManager::setPhysicsVelocity(jlong nodeId, const float3& linear, const float3& angular) {
    std::lock_guard<std::mutex> lock(mMutex);
    
    auto it = mNodes.find(nodeId);
    if (it == mNodes.end() || !it->second.hasPhysicsBody) return;
    
    mPhysicsSystem->setVelocity(it->second.physicsBodyId, linear, angular);
}

void SceneManager::raycast(const float3& from, const float3& to, RaycastCallback callback) {
    mPhysicsSystem->raycast(from, to, [this, callback](uint64_t bodyId, const float3& hitPoint, 
                                                        const float3& hitNormal, float distance) {
        for (auto& [nodeId, node] : mNodes) {
            if (node.physicsBodyId == bodyId) {
                callback(nodeId, hitPoint, hitNormal, distance);
                break;
            }
        }
    });
}

void SceneManager::update(double deltaTime) {
    updateAnimations(deltaTime);
    updateTransforms();
    stepPhysics(deltaTime);
}

void SceneManager::notifyJavaNodeTransformChanged(jlong nodeId, const mat4f& worldTransform) {
    JNIEnv* env = getJniEnv();
    if (!env || !mJavaSceneManager) return;
    
    static jmethodID method = nullptr;
    if (!method) {
        jclass clazz = env->GetObjectClass(mJavaSceneManager);
        method = env->GetMethodID(clazz, "onNativeTransformChanged", "(J[F)V");
    }
    
    if (method) {
        float matrix[16];
        for (int i = 0; i < 16; ++i) {
            matrix[i] = worldTransform[i / 4][i % 4];
        }
        
        jfloatArray jArray = env->NewFloatArray(16);
        env->SetFloatArrayRegion(jArray, 0, 16, matrix);
        env->CallVoidMethod(mJavaSceneManager, method, nodeId, jArray);
        env->DeleteLocalRef(jArray);
    }
}

JNIEnv* SceneManager::getJniEnv() {
    JavaVM* jvm = mEngine->getJavaVM();
    JNIEnv* env;
    jint result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

} // namespace thermion
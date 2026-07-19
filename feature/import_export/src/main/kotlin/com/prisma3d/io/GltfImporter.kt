package com.prisma3d.io

import com.prisma3d.common.math.Mat4
import com.prisma3d.common.math.Quat
import com.prisma3d.common.math.Vec3
import com.prisma3d.common.model.*
import com.prisma3d.common.render.Material
import com.prisma3d.common.render.Texture
import com.prisma3d.common.scene.Node
import com.prisma3d.common.scene.Scene
import com.prisma3d.core.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture

class GltfImporter private constructor() {

    companion object {
        @Volatile private var instance: GltfImporter? = null
        private val logger = Logger.getLogger(GltfImporter::class.java.name)

        fun getInstance(): GltfImporter = instance ?: synchronized(this) {
            instance ?: GltfImporter().also { instance = it }
        }

        init {
            System.loadLibrary("prisma3d_gltf")
        }
    }

    external fun nativeInit(): Long
    external fun nativeLoadAsset(nativePtr: Long, path: String, options: Int): Int
    external fun nativeLoadAssetFromMemory(nativePtr: Long, data: ByteArray, options: Int): Int
    external fun nativeGetSceneCount(nativePtr: Long): Int
    external fun nativeGetScene(nativePtr: Long, index: Int): Long
    external fun nativeGetRootNode(nativePtr: Long, scenePtr: Long): Long
    external fun nativeGetNodeCount(nativePtr: Long): Int
    external fun nativeGetNode(nativePtr: Long, index: Int): Long
    external fun nativeGetMeshCount(nativePtr: Long): Int
    external fun nativeGetMesh(nativePtr: Long, index: Int): Long
    external fun nativeGetMaterialCount(nativePtr: Long): Int
    external fun nativeGetMaterial(nativePtr: Long, index: Int): Long
    external fun nativeGetAnimationCount(nativePtr: Long): Int
    external fun nativeGetAnimation(nativePtr: Long, index: Int): Long
    external fun nativeGetSkinCount(nativePtr: Long): Int
    external fun nativeGetSkin(nativePtr: Long, index: Int): Long
    external fun nativeGetTextureCount(nativePtr: Long): Int
    external fun nativeGetTexture(nativePtr: Long, index: Int): Long
    external fun nativeGetImageCount(nativePtr: Long): Int
    external fun nativeGetImage(nativePtr: Long, index: Int): Long
    external fun nativeGetAccessorCount(nativePtr: Long): Int
    external fun nativeGetAccessor(nativePtr: Long, index: Int): Long
    external fun nativeGetBufferViewCount(nativePtr: Long): Int
    external fun nativeGetBufferView(nativePtr: Long, index: Int): Long
    external fun nativeGetBufferCount(nativePtr: Long): Int
    external fun nativeGetBuffer(nativePtr: Long, index: Int): Long
    external fun nativeDestroy(nativePtr: Long)

    external fun nativeNodeGetName(nativePtr: Long, nodePtr: Long): String
    external fun nativeNodeGetTransform(nativePtr: Long, nodePtr: Long): FloatArray
    external fun nativeNodeGetChildren(nativePtr: Long, nodePtr: Long): LongArray
    external fun nativeNodeGetMeshIndex(nativePtr: Long, nodePtr: Long): Int
    external fun nativeNodeGetSkinIndex(nativePtr: Long, nodePtr: Long): Int
    external fun nativeNodeGetCameraIndex(nativePtr: Long, nodePtr: Long): Int
    external fun nativeNodeGetWeights(nativePtr: Long, nodePtr: Long): FloatArray

    external fun nativeMeshGetName(nativePtr: Long, meshPtr: Long): String
    external fun nativeMeshGetPrimitiveCount(nativePtr: Long, meshPtr: Long): Int
    external fun nativeMeshGetPrimitive(nativePtr: Long, meshPtr: Long, index: Int): Long

    external fun nativePrimitiveGetAttributes(nativePtr: Long, primPtr: Long): LongArray
    external fun nativePrimitiveGetAttributeAccessor(nativePtr: Long, primPtr: Long, semantic: Int): Int
    external fun nativePrimitiveGetIndicesAccessor(nativePtr: Long, primPtr: Long): Int
    external fun nativePrimitiveGetMaterialIndex(nativePtr: Long, primPtr: Long): Int
    external fun nativePrimitiveGetMode(nativePtr: Long, primPtr: Long): Int
    external fun nativePrimitiveGetMorphTargetCount(nativePtr: Long, primPtr: Long): Int
    external fun nativePrimitiveGetMorphTarget(nativePtr: Long, primPtr: Long, index: Int): LongArray
    external fun nativePrimitiveGetTargets(nativePtr: Long, primPtr: Long): LongArray

    external fun nativeAccessorGetName(nativePtr: Long, accessorPtr: Long): String
    external fun nativeAccessorGetType(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetComponentType(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetCount(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetBufferViewIndex(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetByteOffset(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetMin(nativePtr: Long, accessorPtr: Long): FloatArray
    external fun nativeAccessorGetMax(nativePtr: Long, accessorPtr: Long): FloatArray
    external fun nativeAccessorIsSparse(nativePtr: Long, accessorPtr: Long): Boolean
    external fun nativeAccessorGetSparseCount(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetSparseIndicesAccessor(nativePtr: Long, accessorPtr: Long): Int
    external fun nativeAccessorGetSparseValuesAccessor(nativePtr: Long, accessorPtr: Long): Int

    external fun nativeBufferViewGetName(nativePtr: Long, bufferViewPtr: Long): String
    external fun nativeBufferViewGetBufferIndex(nativePtr: Long, bufferViewPtr: Long): Int
    external fun nativeBufferViewGetByteOffset(nativePtr: Long, bufferViewPtr: Long): Int
    external fun nativeBufferViewGetByteLength(nativePtr: Long, bufferViewPtr: Long): Int
    external fun nativeBufferViewGetByteStride(nativePtr: Long, bufferViewPtr: Long): Int
    external fun nativeBufferViewGetTarget(nativePtr: Long, bufferViewPtr: Long): Int

    external fun nativeBufferGetName(nativePtr: Long, bufferPtr: Long): String
    external fun nativeBufferGetByteLength(nativePtr: Long, bufferPtr: Long): Int
    external fun nativeBufferGetData(nativePtr: Long, bufferPtr: Long): ByteArray

    external fun nativeMaterialGetName(nativePtr: Long, materialPtr: Long): String
    external fun nativeMaterialGetPbrMetallicRoughness(nativePtr: Long, materialPtr: Long): Long
    external fun nativeMaterialGetNormalTexture(nativePtr: Long, materialPtr: Long): Long
    external fun nativeMaterialGetOcclusionTexture(nativePtr: Long, materialPtr: Long): Long
    external fun nativeMaterialGetEmissiveTexture(nativePtr: Long, materialPtr: Long): Long
    external fun nativeMaterialGetEmissiveFactor(nativePtr: Long, materialPtr: Long): FloatArray
    external fun nativeMaterialGetAlphaMode(nativePtr: Long, materialPtr: Long): Int
    external fun nativeMaterialGetAlphaCutoff(nativePtr: Long, materialPtr: Long): Float
    external fun nativeMaterialGetDoubleSided(nativePtr: Long, materialPtr: Long): Boolean
    external fun nativeMaterialGetExtensions(nativePtr: Long, materialPtr: Long): String

    external fun nativePbrGetBaseColorFactor(nativePtr: Long, pbrPtr: Long): FloatArray
    external fun nativePbrGetBaseColorTexture(nativePtr: Long, pbrPtr: Long): Long
    external fun nativePbrGetMetallicFactor(nativePtr: Long, pbrPtr: Long): Float
    external fun nativePbrGetRoughnessFactor(nativePtr: Long, pbrPtr: Long): Float
    external fun nativePbrGetMetallicRoughnessTexture(nativePtr: Long, pbrPtr: Long): Long

    external fun nativeTextureGetName(nativePtr: Long, texturePtr: Long): String
    external fun nativeTextureGetSamplerIndex(nativePtr: Long, texturePtr: Long): Int
    external fun nativeTextureGetSourceIndex(nativePtr: Long, texturePtr: Long): Int

    external fun nativeSamplerGetName(nativePtr: Long, samplerPtr: Long): String
    external fun nativeSamplerGetMagFilter(nativePtr: Long, samplerPtr: Long): Int
    external fun nativeSamplerGetMinFilter(nativePtr: Long, samplerPtr: Long): Int
    external fun nativeSamplerGetWrapS(nativePtr: Long, samplerPtr: Long): Int
    external fun nativeSamplerGetWrapT(nativePtr: Long, samplerPtr: Long): Int

    external fun nativeImageGetName(nativePtr: Long, imagePtr: Long): String
    external fun nativeImageGetUri(nativePtr: Long, imagePtr: Long): String
    external fun nativeImageGetMimeType(nativePtr: Long, imagePtr: Long): String
    external fun nativeImageGetBufferViewIndex(nativePtr: Long, imagePtr: Long): Int
    external fun nativeImageGetData(nativePtr: Long, imagePtr: Long): ByteArray

    external fun nativeSkinGetName(nativePtr: Long, skinPtr: Long): String
    external fun nativeSkinGetInverseBindMatricesAccessor(nativePtr: Long, skinPtr: Long): Int
    external fun nativeSkinGetJoints(nativePtr: Long, skinPtr: Long): IntArray

    external fun nativeAnimationGetName(nativePtr: Long, animationPtr: Long): String
    external fun nativeAnimationGetSamplerCount(nativePtr: Long, animationPtr: Long): Int
    external fun nativeAnimationGetSampler(nativePtr: Long, animationPtr: Long, index: Int): Long
    external fun nativeAnimationGetChannelCount(nativePtr: Long, animationPtr: Long): Int
    external fun nativeAnimationGetChannel(nativePtr: Long, animationPtr: Long, index: Int): Long

    external fun nativeSamplerGetAnimationInput(nativePtr: Long, samplerPtr: Long): Int
    external fun nativeSamplerGetAnimationOutput(nativePtr: Long, samplerPtr: Long): Int
    external fun nativeSamplerGetAnimationInterpolation(nativePtr: Long, samplerPtr: Long): Int

    external fun nativeChannelGetTargetNode(nativePtr: Long, channelPtr: Long): Int
    external fun nativeChannelGetTargetPath(nativePtr: Long, channelPtr: Long): Int

    sealed class ImportResult<out T> {
        data class Success<T>(val value: T) : ImportResult<T>()
        data class Failure(val exception: Throwable) : ImportResult<Nothing>()
    }

    enum class ImportFlags(val value: Int) {
        NONE(0),
        FLIP_V(1),
        GENERATE_TANGENTS(2),
        GENERATE_NORMALS(4),
        OPTIMIZE_MESHES(8),
        DRACO_DECOMPRESS(16),
        KHR_MATERIALS_UNLIT(32),
        KHR_MATERIALS_PBR_SPECULAR_GLOSSINESS(64),
        KHR_MATERIALS_CLEARCOAT(128),
        KHR_MATERIALS_TRANSMISSION(256),
        KHR_MATERIALS_VOLUME(512),
        KHR_MATERIALS_IOR(1024),
        KHR_MATERIALS_SPECULAR(2048),
        KHR_MATERIALS_SHEEN(4096),
        KHR_TEXTURE_TRANSFORM(8192),
        KHR_MESH_QUANTIZATION(16384),
        ALL(0x7FFFFFFF)
    }

    inline fun ImportFlags.or(other: ImportFlags): ImportFlags = ImportFlags(this.value or other.value)
    inline fun ImportFlags.and(other: ImportFlags): ImportFlags = ImportFlags(this.value and other.value)

    private var nativePtr: Long = 0
    private var initialized = false

    private fun ensureInitialized() {
        if (!initialized) {
            nativePtr = nativeInit()
            initialized = true
        }
    }

    suspend fun importFromFile(file: File, flags: ImportFlags = ImportFlags.ALL): ImportResult<Scene> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = nativeLoadAsset(nativePtr, file.absolutePath, flags.value)
            if (result != 0) {
                return@withContext ImportResult.Failure(GltfImportException("Failed to load glTF asset: error code $result"))
            }
            parseScene()
        } catch (e: Exception) {
            ImportResult.Failure(GltfImportException("Import failed", e))
        }
    }

    suspend fun importFromBytes(data: ByteArray, flags: ImportFlags = ImportFlags.ALL): ImportResult<Scene> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val result = nativeLoadAssetFromMemory(nativePtr, data, flags.value)
            if (result != 0) {
                return@withContext ImportResult.Failure(GltfImportException("Failed to load glTF from memory: error code $result"))
            }
            parseScene()
        } catch (e: Exception) {
            ImportResult.Failure(GltfImportException("Import failed", e))
        }
    }

    private fun parseScene(): ImportResult<Scene> {
        val sceneCount = nativeGetSceneCount(nativePtr)
        if (sceneCount == 0) {
            return ImportResult.Failure(GltfImportException("No scenes found in glTF file"))
        }

        val defaultScenePtr = nativeGetScene(nativePtr, 0)
        val rootNodePtr = nativeGetRootNode(nativePtr, defaultScenePtr)

        val nodes = parseNodes()
        val meshes = parseMeshes()
        val materials = parseMaterials()
        val textures = parseTextures()
        val images = parseImages()
        val skins = parseSkins()
        val animations = parseAnimations()

        val rootNode = buildNodeHierarchy(rootNodePtr, nodes, meshes, materials, skins)
        val scene = Scene(name = "imported_scene", rootNodes = listOf(rootNode))

        ImportResult.Success(scene)
    }

    private fun parseNodes(): Map<Int, NodeData> {
        val nodeCount = nativeGetNodeCount(nativePtr)
        val nodes = mutableMapOf<Int, NodeData>()

        for (i in 0 until nodeCount) {
            val nodePtr = nativeGetNode(nativePtr, i)
            val name = nativeNodeGetName(nativePtr, nodePtr)
            val transformArray = nativeNodeGetTransform(nativePtr, nodePtr)
            val children = nativeNodeGetChildren(nativePtr, nodePtr).map { it.toInt() }
            val meshIndex = nativeNodeGetMeshIndex(nativePtr, nodePtr)
            val skinIndex = nativeNodeGetSkinIndex(nativePtr, nodePtr)
            val cameraIndex = nativeNodeGetCameraIndex(nativePtr, nodePtr)
            val weights = nativeNodeGetWeights(nativePtr, nodePtr)

            val transform = if (transformArray.size == 16) {
                Mat4.fromArray(transformArray)
            } else {
                Mat4.identity()
            }

            nodes[i] = NodeData(
                index = i,
                name = name,
                transform = transform,
                children = children,
                meshIndex = if (meshIndex >= 0) meshIndex else null,
                skinIndex = if (skinIndex >= 0) skinIndex else null,
                cameraIndex = if (cameraIndex >= 0) cameraIndex else null,
                morphWeights = weights
            )
        }
        return nodes
    }

    private fun parseMeshes(): Map<Int, MeshData> {
        val meshCount = nativeGetMeshCount(nativePtr)
        val meshes = mutableMapOf<Int, MeshData>()

        for (i in 0 until meshCount) {
            val meshPtr = nativeGetMesh(nativePtr, i)
            val name = nativeMeshGetName(nativePtr, meshPtr)
            val primitiveCount = nativeMeshGetPrimitiveCount(nativePtr, meshPtr)
            val primitives = mutableListOf<PrimitiveData>()

            for (p in 0 until primitiveCount) {
                val primPtr = nativeMeshGetPrimitive(nativePtr, meshPtr, p)
                val attributes = parsePrimitiveAttributes(primPtr)
                val indicesAccessor = nativePrimitiveGetIndicesAccessor(nativePtr, primPtr)
                val materialIndex = nativePrimitiveGetMaterialIndex(nativePtr, primPtr)
                val mode = nativePrimitiveGetMode(nativePtr, primPtr)
                val morphTargets = parseMorphTargets(primPtr)

                primitives.add(PrimitiveData(
                    attributes = attributes,
                    indicesAccessorIndex = if (indicesAccessor >= 0) indicesAccessor else null,
                    materialIndex = if (materialIndex >= 0) materialIndex else null,
                    mode = PrimitiveMode.fromValue(mode),
                    morphTargets = morphTargets
                ))
            }

            meshes[i] = MeshData(name = name, primitives = primitives)
        }
        return meshes
    }

    private fun parsePrimitiveAttributes(primPtr: Long): Map<AttributeSemantic, Int> {
        val attributes = mutableMapOf<AttributeSemantic, Int>()
        val attributeAccessors = nativePrimitiveGetAttributes(nativePtr, primPtr)

        for (semanticValue in AttributeSemantic.values()) {
            val accessorIndex = nativePrimitiveGetAttributeAccessor(nativePtr, primPtr, semanticValue.value)
            if (accessorIndex >= 0) {
                attributes[semanticValue] = accessorIndex
            }
        }
        return attributes
    }

    private fun parseMorphTargets(primPtr: Long): List<MorphTargetData> {
        val targetCount = nativePrimitiveGetMorphTargetCount(nativePtr, primPtr)
        val targets = mutableListOf<MorphTargetData>()

        for (i in 0 until targetCount) {
            val targetPtr = nativePrimitiveGetMorphTarget(nativePtr, primPtr, i)
            val attributes = mutableMapOf<AttributeSemantic, Int>()

            for (semanticValue in AttributeSemantic.values()) {
                val accessorIndex = nativePrimitiveGetAttributeAccessor(nativePtr, targetPtr, semanticValue.value)
                if (accessorIndex >= 0) {
                    attributes[semanticValue] = accessorIndex
                }
            }
            targets.add(MorphTargetData(attributes = attributes))
        }
        return targets
    }

    private fun parseMaterials(): Map<Int, MaterialData> {
        val materialCount = nativeGetMaterialCount(nativePtr)
        val materials = mutableMapOf<Int, MaterialData>()

        for (i in 0 until materialCount) {
            val materialPtr = nativeGetMaterial(nativePtr, i)
            val name = nativeMaterialGetName(nativePtr, materialPtr)
            val pbrPtr = nativeMaterialGetPbrMetallicRoughness(nativePtr, materialPtr)
            val normalTexture = parseTextureInfo(nativeMaterialGetNormalTexture(nativePtr, materialPtr))
            val occlusionTexture = parseTextureInfo(nativeMaterialGetOcclusionTexture(nativePtr, materialPtr))
            val emissiveTexture = parseTextureInfo(nativeMaterialGetEmissiveTexture(nativePtr, materialPtr))
            val emissiveFactor = nativeMaterialGetEmissiveFactor(nativePtr, materialPtr)
            val alphaMode = AlphaMode.fromValue(nativeMaterialGetAlphaMode(nativePtr, materialPtr))
            val alphaCutoff = nativeMaterialGetAlphaCutoff(nativePtr, materialPtr)
            val doubleSided = nativeMaterialGetDoubleSided(nativePtr, materialPtr)
            val extensionsJson = nativeMaterialGetExtensions(nativePtr, materialPtr)

            val pbr = if (pbrPtr != 0L) {
                parsePbrMetallicRoughness(pbrPtr)
            } else {
                PbrMetallicRoughnessData()
            }

            materials[i] = MaterialData(
                name = name,
                pbrMetallicRoughness = pbr,
                normalTexture = normalTexture,
                occlusionTexture = occlusionTexture,
                emissiveTexture = emissiveTexture,
                emissiveFactor = Vec3(emissiveFactor[0], emissiveFactor[1], emissiveFactor[2]),
                alphaMode = alphaMode,
                alphaCutoff = alphaCutoff,
                doubleSided = doubleSided,
                extensions = extensionsJson
            )
        }
        return materials
    }

    private fun parsePbrMetallicRoughness(pbrPtr: Long): PbrMetallicRoughnessData {
        val baseColorFactor = nativePbrGetBaseColorFactor(nativePtr, pbrPtr)
        val baseColorTexture = parseTextureInfo(nativePbrGetBaseColorTexture(nativePtr, pbrPtr))
        val metallicFactor = nativePbrGetMetallicFactor(nativePtr, pbrPtr)
        val roughnessFactor = nativePbrGetRoughnessFactor(nativePtr, pbrPtr)
        val metallicRoughnessTexture = parseTextureInfo(nativePbrGetMetallicRoughnessTexture(nativePtr, pbrPtr))

        return PbrMetallicRoughnessData(
            baseColorFactor = Color(baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3]),
            baseColorTexture = baseColorTexture,
            metallicFactor = metallicFactor,
            roughnessFactor = roughnessFactor,
            metallicRoughnessTexture = metallicRoughnessTexture
        )
    }

    private fun parseTextureInfo(textureInfoPtr: Long): TextureInfoData? {
        if (textureInfoPtr == 0L) return null

        val textureIndex = nativeTextureGetSourceIndex(nativePtr, textureInfoPtr)
        val samplerIndex = nativeTextureGetSamplerIndex(nativePtr, textureInfoPtr)

        return TextureInfoData(
            textureIndex = if (textureIndex >= 0) textureIndex else null,
            samplerIndex = if (samplerIndex >= 0) samplerIndex else null
        )
    }

    private fun parseTextures(): Map<Int, TextureData> {
        val textureCount = nativeGetTextureCount(nativePtr)
        val textures = mutableMapOf<Int, TextureData>()

        for (i in 0 until textureCount) {
            val texturePtr = nativeGetTexture(nativePtr, i)
            val name = nativeTextureGetName(nativePtr, texturePtr)
            val samplerIndex = nativeTextureGetSamplerIndex(nativePtr, texturePtr)
            val sourceIndex = nativeTextureGetSourceIndex(nativePtr, texturePtr)

            textures[i] = TextureData(
                name = name,
                samplerIndex = if (samplerIndex >= 0) samplerIndex else null,
                sourceIndex = if (sourceIndex >= 0) sourceIndex else null
            )
        }
        return textures
    }

    private fun parseImages(): Map<Int, ImageData> {
        val imageCount = nativeGetImageCount(nativePtr)
        val images = mutableMapOf<Int, ImageData>()

        for (i in 0 until imageCount) {
            val imagePtr = nativeGetImage(nativePtr, i)
            val name = nativeImageGetName(nativePtr, imagePtr)
            val uri = nativeImageGetUri(nativePtr, imagePtr)
            val mimeType = nativeImageGetMimeType(nativePtr, imagePtr)
            val bufferViewIndex = nativeImageGetBufferViewIndex(nativePtr, imagePtr)
            val data = nativeImageGetData(nativePtr, imagePtr)

            images[i] = ImageData(
                name = name,
                uri = uri,
                mimeType = mimeType,
                bufferViewIndex = if (bufferViewIndex >= 0) bufferViewIndex else null,
                data = data
            )
        }
        return images
    }

    private fun parseSkins(): Map<Int, SkinData> {
        val skinCount = nativeGetSkinCount(nativePtr)
        val skins = mutableMapOf<Int, SkinData>()

        for (i in 0 until skinCount) {
            val skinPtr = nativeGetSkin(nativePtr, i)
            val name = nativeSkinGetName(nativePtr, skinPtr)
            val inverseBindMatricesAccessor = nativeSkinGetInverseBindMatricesAccessor(nativePtr, skinPtr)
            val joints = nativeSkinGetJoints(nativePtr, skinPtr)

            skins[i] = SkinData(
                name = name,
                inverseBindMatricesAccessorIndex = if (inverseBindMatricesAccessor >= 0) inverseBindMatricesAccessor else null,
                jointNodeIndices = joints
            )
        }
        return skins
    }

    private fun parseAnimations(): List<AnimationData> {
        val animationCount = nativeGetAnimationCount(nativePtr)
        val animations = mutableListOf<AnimationData>()

        for (i in 0 until animationCount) {
            val animationPtr = nativeGetAnimation(nativePtr, i)
            val name = nativeAnimationGetName(nativePtr, animationPtr)

            val samplers = mutableListOf<AnimationSamplerData>()
            val samplerCount = nativeAnimationGetSamplerCount(nativePtr, animationPtr)
            for (s in 0 until samplerCount) {
                val samplerPtr = nativeAnimationGetSampler(nativePtr, animationPtr, s)
                val inputAccessor = nativeSamplerGetAnimationInput(nativePtr, samplerPtr)
                val outputAccessor = nativeSamplerGetAnimationOutput(nativePtr, samplerPtr)
                val interpolation = InterpolationType.fromValue(nativeSamplerGetAnimationInterpolation(nativePtr, samplerPtr))

                samplers.add(AnimationSamplerData(
                    inputAccessorIndex = inputAccessor,
                    outputAccessorIndex = outputAccessor,
                    interpolation = interpolation
                ))
            }

            val channels = mutableListOf<AnimationChannelData>()
            val channelCount = nativeAnimationGetChannelCount(nativePtr, animationPtr)
            for (c in 0 until channelCount) {
                val channelPtr = nativeAnimationGetChannel(nativePtr, animationPtr, c)
                val targetNode = nativeChannelGetTargetNode(nativePtr, channelPtr)
                val targetPath = AnimationChannelTargetPath.fromValue(nativeChannelGetTargetPath(nativePtr, channelPtr))

                channels.add(AnimationChannelData(
                    samplerIndex = c,
                    targetNodeIndex = targetNode,
                    targetPath = targetPath
                ))
            }

            animations.add(AnimationData(name = name, samplers = samplers, channels = channels))
        }
        return animations
    }

    private fun buildNodeHierarchy(
        rootNodePtr: Long,
        nodes: Map<Int, NodeData>,
        meshes: Map<Int, MeshData>,
        materials: Map<Int, MaterialData>,
        skins: Map<Int, SkinData>
    ): Node {
        val rootNodeIndex = findNodeIndexByPointer(rootNodePtr)
        return buildNodeRecursive(rootNodeIndex, nodes, meshes, materials, skins)
    }

    private fun findNodeIndexByPointer(nodePtr: Long): Int {
        val nodeCount = nativeGetNodeCount(nativePtr)
        for (i in 0 until nodeCount) {
            if (nativeGetNode(nativePtr, i) == nodePtr) {
                return i
            }
        }
        return 0
    }

    private fun buildNodeRecursive(
        nodeIndex: Int,
        nodes: Map<Int, NodeData>,
        meshes: Map<Int, MeshData>,
        materials: Map<Int, MaterialData>,
        skins: Map<Int, SkinData>
    ): Node {
        val nodeData = nodes[nodeIndex]!!
        val children = nodeData.children.map { buildNodeRecursive(it, nodes, meshes, materials, skins) }

        val mesh = nodeData.meshIndex?.let { meshes[it] }?.let { buildMesh(it, materials) }
        val skin = nodeData.skinIndex?.let { skins[it] }?.let { buildSkin(it) }

        return Node(
            name = nodeData.name,
            transform = nodeData.transform,
            children = children,
            mesh = mesh,
            skin = skin,
            morphWeights = nodeData.morphWeights
        )
    }

    private fun buildMesh(meshData: MeshData, materials: Map<Int, MaterialData>): Mesh {
        val primitives = meshData.primitives.map { primData ->
            val material = primData.materialIndex?.let { materials[it] }?.let { buildMaterial(it) }
            buildPrimitive(primData, material)
        }
        return Mesh(name = meshData.name, primitives = primitives)
    }

    private fun buildPrimitive(primData: PrimitiveData, material: Material?): Primitive {
        val attributes = primData.attributes.mapValues { (semantic, accessorIndex) ->
            semantic to buildAttribute(accessorIndex)
        }
        val indices = primData.indicesAccessorIndex?.let { buildIndexBuffer(it) }
        val morphTargets = primData.morphTargets.map { buildMorphTarget(it) }

        return Primitive(
            attributes = attributes,
            indices = indices,
            material = material,
            mode = primData.mode,
            morphTargets = morphTargets
        )
    }

    private fun buildAttribute(accessorIndex: Int): Attribute {
        val accessorPtr = nativeGetAccessor(nativePtr, accessorIndex)
        val name = nativeAccessorGetName(nativePtr, accessorPtr)
        val type = AccessorType.fromValue(nativeAccessorGetType(nativePtr, accessorPtr))
        val componentType = ComponentType.fromValue(nativeAccessorGetComponentType(nativePtr, accessorPtr))
        val count = nativeAccessorGetCount(nativePtr, accessorPtr)
        val bufferViewIndex = nativeAccessorGetBufferViewIndex(nativePtr, accessorPtr)
        val byteOffset = nativeAccessorGetByteOffset(nativePtr, accessorPtr)
        val min = nativeAccessorGetMin(nativePtr, accessorPtr)
        val max = nativeAccessorGetMax(nativePtr, accessorPtr)
        val isSparse = nativeAccessorIsSparse(nativePtr, accessorPtr)

        val bufferView = if (bufferViewIndex >= 0) {
            val bvPtr = nativeGetBufferView(nativePtr, bufferViewIndex)
            val bufferIndex = nativeBufferViewGetBufferIndex(nativePtr, bvPtr)
            val bvOffset = nativeBufferViewGetByteOffset(nativePtr, bvPtr)
            val bvLength = nativeBufferViewGetByteLength(nativePtr, bvPtr)
            val bvStride = nativeBufferViewGetByteStride(nativePtr, bvPtr)
            val target = BufferViewTarget.fromValue(nativeBufferViewGetTarget(nativePtr, bvPtr))

            val bufferPtr = nativeGetBuffer(nativePtr, bufferIndex)
            val bufferData = nativeBufferGetData(nativePtr, bufferPtr)

            BufferView(
                bufferIndex = bufferIndex,
                byteOffset = bvOffset,
                byteLength = bvLength,
                byteStride = bvStride,
                target = target,
                data = bufferData
            )
        } else {
            null
        }

        val data = bufferView?.data?.let { bufferData ->
            ByteBuffer.wrap(bufferData, byteOffset, bufferView.byteLength - byteOffset).order(ByteOrder.LITTLE_ENDIAN)
        }

        return Attribute(
            name = name,
            type = type,
            componentType = componentType,
            count = count,
            bufferView = bufferView,
            byteOffset = byteOffset,
            min = if (min.size >= 3) Vec3(min[0], min[1], min[2]) else null,
            max = if (max.size >= 3) Vec3(max[0], max[1], max[2]) else null,
            isSparse = isSparse,
            data = data
        )
    }

    private fun buildIndexBuffer(accessorIndex: Int): IndexBuffer {
        val attribute = buildAttribute(accessorIndex)
        return IndexBuffer(
            componentType = attribute.componentType,
            count = attribute.count,
            data = attribute.data
        )
    }

    private fun buildMorphTarget(targetData: MorphTargetData): MorphTarget {
        val attributes = targetData.attributes.mapValues { (semantic, accessorIndex) ->
            semantic to buildAttribute(accessorIndex)
        }
        return MorphTarget(attributes = attributes)
    }

    private fun buildMaterial(materialData: MaterialData): Material {
        return Material(
            name = materialData.name,
            pbrMetallicRoughness = buildPbrMaterial(materialData.pbrMetallicRoughness),
            normalTexture = materialData.normalTexture?.let { buildTexture(it) },
            occlusionTexture = materialData.occlusionTexture?.let { buildTexture(it) },
            emissiveTexture = materialData.emissiveTexture?.let { buildTexture(it) },
            emissiveFactor = materialData.emissiveFactor,
            alphaMode = materialData.alphaMode,
            alphaCutoff = materialData.alphaCutoff,
            doubleSided = materialData.doubleSided
        )
    }

    private fun buildPbrMaterial(pbrData: PbrMetallicRoughnessData): Material.PbrMetallicRoughness {
        return Material.PbrMetallicRoughness(
            baseColorFactor = pbrData.baseColorFactor,
            baseColorTexture = pbrData.baseColorTexture?.let { buildTexture(it) },
            metallicFactor = pbrData.metallicFactor,
            roughnessFactor = pbrData.roughnessFactor,
            metallicRoughnessTexture = pbrData.metallicRoughnessTexture?.let { buildTexture(it) }
        )
    }

    private fun buildTexture(textureInfo: TextureInfoData): Material.TextureInfo {
        return Material.TextureInfo(
            textureIndex = textureInfo.textureIndex,
            samplerIndex = textureInfo.samplerIndex
        )
    }

    private fun buildSkin(skinData: SkinData): Skin {
        val inverseBindMatrices = skinData.inverseBindMatricesAccessorIndex?.let { accessorIndex ->
            val attribute = buildAttribute(accessorIndex)
            val matrices = mutableListOf<Mat4>()
            val data = attribute.data!!
            for (i in 0 until attribute.count) {
                val m = FloatArray(16)
                for (j in 0..15) {
                    m[j] = when (attribute.componentType) {
                        ComponentType.FLOAT -> data.getFloat()
                        ComponentType.UNSIGNED_SHORT -> data.getShort().toFloat() / 65535.0f
                        ComponentType.SHORT -> data.getShort().toFloat() / 32767.0f
                        else -> 0f
                    }
                }
                matrices.add(Mat4.fromArray(m))
            }
            matrices
        } ?: emptyList()

        return Skin(
            name = skinData.name,
            jointNodeIndices = skinData.jointNodeIndices,
            inverseBindMatrices = inverseBindMatrices
        )
    }

    fun destroy() {
        if (initialized) {
            nativeDestroy(nativePtr)
            initialized = false
        }
    }
}

class GltfImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class NodeData(
    val index: Int,
    val name: String,
    val transform: Mat4,
    val children: List<Int>,
    val meshIndex: Int?,
    val skinIndex: Int?,
    val cameraIndex: Int?,
    val morphWeights: FloatArray
)

data class MeshData(
    val name: String,
    val primitives: List<PrimitiveData>
)

data class PrimitiveData(
    val attributes: Map<AttributeSemantic, Int>,
    val indicesAccessorIndex: Int?,
    val materialIndex: Int?,
    val mode: PrimitiveMode,
    val morphTargets: List<MorphTargetData>
)

data class MorphTargetData(
    val attributes: Map<AttributeSemantic, Int>
)

data class MaterialData(
    val name: String,
    val pbrMetallicRoughness: PbrMetallicRoughnessData,
    val normalTexture: TextureInfoData?,
    val occlusionTexture: TextureInfoData?,
    val emissiveTexture: TextureInfoData?,
    val emissiveFactor: Vec3,
    val alphaMode: AlphaMode,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
    val extensions: String
)

data class PbrMetallicRoughnessData(
    val baseColorFactor: Color = Color(1f, 1f, 1f, 1f),
    val baseColorTexture: TextureInfoData? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
    val metallicRoughnessTexture: TextureInfoData? = null
)

data class TextureData(
    val name: String,
    val samplerIndex: Int?,
    val sourceIndex: Int?
)

data class TextureInfoData(
    val textureIndex: Int?,
    val samplerIndex: Int?
)

data class ImageData(
    val name: String,
    val uri: String,
    val mimeType: String,
    val bufferViewIndex: Int?,
    val data: ByteArray
)

data class SkinData(
    val name: String,
    val inverseBindMatricesAccessorIndex: Int?,
    val jointNodeIndices: IntArray
)

data class AnimationData(
    val name: String,
    val samplers: List<AnimationSamplerData>,
    val channels: List<AnimationChannelData>
)

data class AnimationSamplerData(
    val inputAccessorIndex: Int,
    val outputAccessorIndex: Int,
    val interpolation: InterpolationType
)

data class AnimationChannelData(
    val samplerIndex: Int,
    val targetNodeIndex: Int,
    val targetPath: AnimationChannelTargetPath
)

enum class AttributeSemantic(val value: Int) {
    POSITION(0), NORMAL(1), TANGENT(2), TEXCOORD_0(3), TEXCOORD_1(4),
    COLOR_0(5), JOINTS_0(6), WEIGHTS_0(7)
}

enum class PrimitiveMode(val value: Int) {
    POINTS(0), LINES(1), LINE_LOOP(2), LINE_STRIP(3),
    TRIANGLES(4), TRIANGLE_STRIP(5), TRIANGLE_FAN(6)

    companion object {
        fun fromValue(value: Int): PrimitiveMode = values().firstOrNull { it.value == value } ?: TRIANGLES
    }
}

enum class AccessorType(val value: Int) {
    SCALAR(0), VEC2(1), VEC3(2), VEC4(3), MAT2(4), MAT3(5), MAT4(6)

    companion object {
        fun fromValue(value: Int): AccessorType = values().firstOrNull { it.value == value } ?: SCALAR
    }

    val componentCount: Int
        get() = when (this) {
            SCALAR -> 1
            VEC2 -> 2
            VEC3 -> 3
            VEC4 -> 4
            MAT2 -> 4
            MAT3 -> 9
            MAT4 -> 16
        }
}

enum class ComponentType(val value: Int) {
    BYTE(5120), UNSIGNED_BYTE(5121), SHORT(5122), UNSIGNED_SHORT(5123),
    UNSIGNED_INT(5125), FLOAT(5126)

    companion object {
        fun fromValue(value: Int): ComponentType = values().firstOrNull { it.value == value } ?: FLOAT
    }

    val byteSize: Int
        get() = when (this) {
            BYTE, UNSIGNED_BYTE -> 1
            SHORT, UNSIGNED_SHORT -> 2
            UNSIGNED_INT, FLOAT -> 4
        }
}

enum class BufferViewTarget(val value: Int) {
    ARRAY_BUFFER(34962), ELEMENT_ARRAY_BUFFER(34963)

    companion object {
        fun fromValue(value: Int): BufferViewTarget = values().firstOrNull { it.value == value } ?: ARRAY_BUFFER
    }
}

enum class AlphaMode(val value: Int) {
    OPAQUE(0), MASK(1), BLEND(2)

    companion object {
        fun fromValue(value: Int): AlphaMode = values().firstOrNull { it.value == value } ?: OPAQUE
    }
}

enum class InterpolationType(val value: Int) {
    LINEAR(0), STEP(1), CUBICSPLINE(2)

    companion object {
        fun fromValue(value: Int): InterpolationType = values().firstOrNull { it.value == value } ?: LINEAR
    }
}

enum class AnimationChannelTargetPath(val value: Int) {
    TRANSLATION(0), ROTATION(1), SCALE(2), WEIGHTS(3)

    companion object {
        fun fromValue(value: Int): AnimationChannelTargetPath = values().firstOrNull { it.value == value } ?: TRANSLATION
    }
}

data class Attribute(
    val name: String,
    val type: AccessorType,
    val componentType: ComponentType,
    val count: Int,
    val bufferView: BufferView?,
    val byteOffset: Int,
    val min: Vec3?,
    val max: Vec3?,
    val isSparse: Boolean,
    val data: ByteBuffer?
)

data class BufferView(
    val bufferIndex: Int,
    val byteOffset: Int,
    val byteLength: Int,
    val byteStride: Int,
    val target: BufferViewTarget,
    val data: ByteArray
)

data class IndexBuffer(
    val componentType: ComponentType,
    val count: Int,
    val data: ByteBuffer?
)

data class MorphTarget(
    val attributes: Map<AttributeSemantic, Attribute>
)

data class Primitive(
    val attributes: Map<AttributeSemantic, Attribute>,
    val indices: IndexBuffer?,
    val material: Material?,
    val mode: PrimitiveMode,
    val morphTargets: List<MorphTarget>
)

data class Mesh(
    val name: String,
    val primitives: List<Primitive>
)

data class Skin(
    val name: String,
    val jointNodeIndices: IntArray,
    val inverseBindMatrices: List<Mat4>
)

data class Color(
    val r: Float, val g: Float, val b: Float, val a: Float
) {
    companion object {
        fun fromArray(array: FloatArray): Color = Color(array[0], array[1], array[2], array[3])
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    companion object {
        fun fromArray(array: FloatArray): Vec3 = Vec3(array[0], array[1], array[2])
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float)

data class Mat4(
    val m00: Float, val m01: Float, val m02: Float, val m03: Float,
    val m10: Float, val m11: Float, val m12: Float, val m13: Float,
    val m20: Float, val m21: Float, val m22: Float, val m23: Float,
    val m30: Float, val m31: Float, val m32: Float, val m33: Float
) {
    companion object {
        fun identity(): Mat4 = Mat4(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun fromArray(array: FloatArray): Mat4 = Mat4(
            array[0], array[1], array[2], array[3],
            array[4], array[5], array[6], array[7],
            array[8], array[9], array[10], array[11],
            array[12], array[13], array[14], array[15]
        )
    }
}

data class Node(
    val name: String,
    val transform: Mat4,
    val children: List<Node>,
    val mesh: Mesh?,
    val skin: Skin?,
    val morphWeights: FloatArray
)

data class Scene(
    val name: String,
    val rootNodes: List<Node>
)
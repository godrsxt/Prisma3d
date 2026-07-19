package com.prisma3d.viewport.gizmo

import com.prisma3d.engine.math.Mat4
import com.prisma3d.engine.math.Quat
import com.prisma3d.engine.math.Vec3
import com.prisma3d.engine.scene.Entity
import com.prisma3d.engine.scene.Scene
import com.prisma3d.engine.scene.command.TransformCommand
import com.prisma3d.viewport.camera.Camera
import com.prisma3d.viewport.input.MouseButton
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/**
 * Enum defining the current gizmo operation mode.
 */
enum class GizmoMode {
    TRANSLATE, ROTATE, SCALE, UNIVERSAL
}

/**
 * Represents a specific interactive handle on the gizmo.
 */
enum class GizmoHandle(val axis: Vec3, val planeNormal: Vec3?, val type: HandleType) {
    X_AXIS(Vec3(1f, 0f, 0f), Vec3(1f, 0f, 0f), HandleType.TRANSLATE_AXIS),
    Y_AXIS(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f), HandleType.TRANSLATE_AXIS),
    Z_AXIS(Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f), HandleType.TRANSLATE_AXIS),
    XY_PLANE(Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f), HandleType.TRANSLATE_PLANE),
    YZ_PLANE(Vec3(1f, 0f, 0f), Vec3(1f, 0f, 0f), HandleType.TRANSLATE_PLANE),
    XZ_PLANE(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f), HandleType.TRANSLATE_PLANE),
    ROTATE_X(Vec3(1f, 0f, 0f), Vec3(1f, 0f, 0f), HandleType.ROTATE_AXIS),
    ROTATE_Y(Vec3(0f, 1f, 0f), Vec3(0f, 1f, 0f), HandleType.ROTATE_AXIS),
    ROTATE_Z(Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f), HandleType.ROTATE_AXIS),
    ROTATE_SCREEN(Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f), HandleType.ROTATE_SCREEN),
    SCALE_X(Vec3(1f, 0f, 0f), null, HandleType.SCALE_AXIS),
    SCALE_Y(Vec3(0f, 1f, 0f), null, HandleType.SCALE_AXIS),
    SCALE_Z(Vec3(0f, 0f, 1f), null, HandleType.SCALE_AXIS),
    SCALE_UNIFORM(Vec3(1f, 1f, 1f), null, HandleType.SCALE_UNIFORM),
    NONE(Vec3.ZERO, null, HandleType.NONE);

    enum class HandleType {
        TRANSLATE_AXIS, TRANSLATE_PLANE, ROTATE_AXIS, ROTATE_SCREEN, SCALE_AXIS, SCALE_UNIFORM, NONE
    }
}

/**
 * Result of a raycast hit against a gizmo handle.
 */
data class GizmoHitResult(
    val handle: GizmoHandle,
    val distance: Float,
    val worldPosition: Vec3,
    val worldNormal: Vec3
)

/**
 * Data class holding the calculated screen-space bounds for gizmo rendering/picking.
 */
data class GizmoHandleBounds(
    val handle: GizmoHandle,
    val centerScreen: Vec3, // x, y = screen pos, z = depth
    val radiusScreen: Float,
    val directionScreen: Vec3, // Normalized screen direction for axes
    val worldMatrix: Mat4,     // Model matrix of the handle geometry
    val boundsType: BoundsType
) {
    enum class BoundsType { CONE, BOX, CIRCLE, QUAD }
}

/**
 * Manages the state, picking, and manipulation logic for 3D gizmos.
 */
class GizmoManager(
    private val scene: Scene,
    private val commandChannel: Channel<TransformCommand>
) {

    // Configuration
    private var currentMode: GizmoMode = GizmoMode.TRANSLATE
    private var gizmoSize: Float = 1.0f // World unit size multiplier
    private var screenSpaceSize: Float = 100f // Target size in pixels
    private var snapEnabled: Boolean = false
    private var snapIncrement: Float = 1.0f
    private var rotationSnapIncrement: Float = 15f // Degrees

    // State
    private var targetEntity: Entity? = null
    private var targetInitialTransform: Mat4 = Mat4.identity()
    private var activeHandle: GizmoHandle = GizmoHandle.NONE
    private var dragStartScreenPos: Vec3 = Vec3.ZERO
    private var dragStartWorldPos: Vec3 = Vec3.ZERO
    private var dragPlaneNormal: Vec3 = Vec3.ZERO
    private var dragPlaneOrigin: Vec3 = Vec3.ZERO
    private var isDragging: Boolean = false
    private var currentCamera: Camera? = null
    private var viewportSize: Vec3 = Vec3.ZERO

    // Cached calculated bounds for current frame
    private val handleBounds = mutableListOf<GizmoHandleBounds>()

    // --- Public API ---

    fun setMode(mode: GizmoMode) {
        currentMode = mode
    }

    fun getMode(): GizmoMode = currentMode

    fun setTarget(entity: Entity?) {
        targetEntity = entity
        if (entity != null) {
            targetInitialTransform = entity.transform.worldMatrix.copy()
        }
    }

    fun getTarget(): Entity? = targetEntity

    fun setSnapEnabled(enabled: Boolean) { snapEnabled = enabled }
    fun setSnapIncrement(increment: Float) { snapIncrement = increment }
    fun setRotationSnapIncrement(increment: Float) { rotationSnapIncrement = increment }

    /**
     * Updates internal state: calculates screen-space bounds for all active handles.
     * Must be called once per frame before rendering/picking.
     */
    fun update(camera: Camera, width: Int, height: Int) {
        currentCamera = camera
        viewportSize = Vec3(width.toFloat(), height.toFloat(), 0f)
        handleBounds.clear()

        targetEntity?.let { entity ->
            val worldPos = entity.transform.worldPosition
            val viewMatrix = camera.viewMatrix
            val projMatrix = camera.projectionMatrix
            val viewProj = projMatrix * viewMatrix

            // Calculate scale factor to maintain constant screen size
            val viewDir = (camera.position - worldPos).normalize()
            val dist = (camera.position - worldPos).length()
            // Project a unit vector at target distance to get pixel size
            val scaleFactor = calculateScreenScaleFactor(dist, camera, width, height)
            val effectiveSize = gizmoSize * scaleFactor

            // Generate bounds based on mode
            when (currentMode) {
                GizmoMode.TRANSLATE -> generateTranslateBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize)
                GizmoMode.ROTATE -> generateRotateBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize)
                GizmoMode.SCALE -> generateScaleBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize)
                GizmoMode.UNIVERSAL -> {
                    generateTranslateBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize * 0.8f)
                    generateRotateBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize)
                    generateScaleBounds(entity, viewMatrix, projMatrix, viewProj, effectiveSize * 0.6f)
                }
            }
        }
    }

    /**
     * Performs raycasting against gizmo handles using screen coordinates.
     * Returns the hit handle if any.
     */
    fun pick(screenX: Float, screenY: Float): GizmoHandle {
        // Iterate bounds in reverse draw order (top-most first) or by distance
        // For simplicity, we check all and pick closest depth.
        var bestHit: GizmoHandle? = null
        var bestDepth = Float.MAX_VALUE

        for (bounds in handleBounds) {
            val hit = checkBoundsHit(bounds, screenX, screenY)
            if (hit && bounds.centerScreen.z < bestDepth) {
                bestDepth = bounds.centerScreen.z
                bestHit = bounds.handle
            }
        }
        return bestHit ?: GizmoHandle.NONE
    }

    /**
     * Starts a drag operation on a specific handle.
     */
    fun startDrag(handle: GizmoHandle, screenX: Float, screenY: Float): Boolean {
        if (handle == GizmoHandle.NONE || targetEntity == null || currentCamera == null) return false

        activeHandle = handle
        dragStartScreenPos = Vec3(screenX, screenY, 0f)
        isDragging = true

        val entity = targetEntity!!
        val cam = currentCamera!!
        val worldPos = entity.transform.worldPosition

        // Capture initial transform for relative operations
        targetInitialTransform = entity.transform.worldMatrix.copy()

        // Setup drag plane/axis logic
        when (handle.type) {
            GizmoHandle.HandleType.TRANSLATE_AXIS -> {
                dragPlaneNormal = cam.getViewDirection().cross(handle.axis).cross(handle.axis).normalize()
                if (dragPlaneNormal.lengthSquared() < 0.001f) dragPlaneNormal = cam.getViewDirection().cross(Vec3.UP).normalize()
                dragPlaneOrigin = worldPos
            }
            GizmoHandle.HandleType.TRANSLATE_PLANE -> {
                dragPlaneNormal = cam.getViewDirection()
                dragPlaneOrigin = worldPos
            }
            GizmoHandle.HandleType.ROTATE_AXIS -> {
                dragPlaneNormal = handle.axis // Rotation axis
                dragPlaneOrigin = worldPos
                // Project mouse to plane perpendicular to axis to get start angle
                dragStartWorldPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam) ?: worldPos
            }
            GizmoHandle.HandleType.ROTATE_SCREEN -> {
                dragPlaneNormal = cam.getViewDirection()
                dragPlaneOrigin = worldPos
                dragStartWorldPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam) ?: worldPos
            }
            GizmoHandle.HandleType.SCALE_AXIS, GizmoHandle.HandleType.SCALE_UNIFORM -> {
                dragPlaneNormal = cam.getViewDirection()
                dragPlaneOrigin = worldPos
                dragStartWorldPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam) ?: worldPos
            }
            else -> return false
        }
        return true
    }

    /**
     * Processes drag movement. Returns true if transform changed.
     */
    fun drag(screenX: Float, screenY: Float): Boolean {
        if (!isDragging || activeHandle == GizmoHandle.NONE || targetEntity == null || currentCamera == null) return false

        val entity = targetEntity!!
        val cam = currentCamera!!
        var changed = false

        when (activeHandle.type) {
            GizmoHandle.HandleType.TRANSLATE_AXIS -> {
                val newPos = projectScreenToLine(screenX, screenY, dragPlaneOrigin, activeHandle.axis, cam)
                if (newPos != null) {
                    val delta = newPos - dragPlaneOrigin // Project onto axis
                    val axisDelta = activeHandle.axis * activeHandle.axis.dot(delta)
                    applyTranslation(axisDelta)
                    changed = true
                }
            }
            GizmoHandle.HandleType.TRANSLATE_PLANE -> {
                val newPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam)
                if (newPos != null) {
                    val delta = newPos - dragPlaneOrigin
                    applyTranslation(delta)
                    changed = true
                }
            }
            GizmoHandle.HandleType.ROTATE_AXIS -> {
                val currentPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, activeHandle.axis, cam)
                if (currentPos != null) {
                    val startDir = (dragStartWorldPos - dragPlaneOrigin).normalize()
                    val currentDir = (currentPos - dragPlaneOrigin).normalize()
                    val angle = atan2(activeHandle.axis.dot(startDir.cross(currentDir)), startDir.dot(currentDir))
                    var degrees = Math.toDegrees(angle).toFloat()
                    if (snapEnabled) degrees = roundToIncrement(degrees, rotationSnapIncrement)
                    applyRotation(activeHandle.axis, degrees)
                    changed = true
                }
            }
            GizmoHandle.HandleType.ROTATE_SCREEN -> {
                val currentPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam)
                if (currentPos != null) {
                    val startDir = (dragStartWorldPos - dragPlaneOrigin).normalize()
                    val currentDir = (currentPos - dragPlaneOrigin).normalize()
                    val angle = atan2(dragPlaneNormal.dot(startDir.cross(currentDir)), startDir.dot(currentDir))
                    var degrees = Math.toDegrees(angle).toFloat()
                    if (snapEnabled) degrees = roundToIncrement(degrees, rotationSnapIncrement)
                    applyRotation(dragPlaneNormal, degrees)
                    changed = true
                }
            }
            GizmoHandle.HandleType.SCALE_AXIS -> {
                val currentPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam)
                if (currentPos != null) {
                    val startDist = (dragStartWorldPos - dragPlaneOrigin).length()
                    val currentDist = (currentPos - dragPlaneOrigin).length()
                    val sign = if (activeHandle.axis.dot(currentPos - dragPlaneOrigin) > 0) 1f else -1f
                    val factor = if (startDist > 0.0001f) (currentDist / startDist) * sign else 1f
                    val clampedFactor = if (snapEnabled) roundToIncrement(factor, snapIncrement) else factor
                    applyScale(activeHandle.axis * (clampedFactor - 1f) + Vec3(1f))
                    changed = true
                }
            }
            GizmoHandle.HandleType.SCALE_UNIFORM -> {
                val currentPos = projectScreenToPlane(screenX, screenY, dragPlaneOrigin, dragPlaneNormal, cam)
                if (currentPos != null) {
                    val startDist = (dragStartWorldPos - dragPlaneOrigin).length()
                    val currentDist = (currentPos - dragPlaneOrigin).length()
                    val factor = if (startDist > 0.0001f) currentDist / startDist else 1f
                    val clampedFactor = if (snapEnabled) roundToIncrement(factor, snapIncrement) else factor
                    applyScale(Vec3(clampedFactor))
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * Ends the drag operation and pushes the final command to the history/channel.
     */
    fun endDrag() {
        if (isDragging && targetEntity != null) {
            val finalTransform = targetEntity!!.transform.worldMatrix
            if (finalTransform != targetInitialTransform) {
                val cmd = TransformCommand(targetEntity!!.id, targetInitialTransform, finalTransform)
                commandChannel.trySend(cmd)
            }
        }
        isDragging = false
        activeHandle = GizmoHandle.NONE
    }

    fun isDragging(): Boolean = isDragging
    fun getActiveHandle(): GizmoHandle = activeHandle
    fun getHandleBounds(): List<GizmoHandleBounds> = handleBounds

    // --- Private Math & Generation Helpers ---

    private fun calculateScreenScaleFactor(dist: Float, cam: Camera, w: Int, h: Int): Float {
        // Standard perspective projection scaling
        val fov = cam.fovY
        val halfHeight = tan(fov * 0.5f) * dist
        val worldUnitsPerPixel = (halfHeight * 2) / h
        return screenSpaceSize * worldUnitsPerPixel
    }

    private fun generateTranslateBounds(entity: Entity, view: Mat4, proj: Mat4, vp: Mat4, size: Float) {
        val pos = entity.transform.worldPosition
        val axes = listOf(
            GizmoHandle.X_AXIS, GizmoHandle.Y_AXIS, GizmoHandle.Z_AXIS,
            GizmoHandle.XY_PLANE, GizmoHandle.YZ_PLANE, GizmoHandle.XZ_PLANE
        )

        for (handle in axes) {
            val worldMat = Mat4.identity().apply {
                translate(pos)
                // Align geometry to axis
                rotateTo(handle.axis, Vec3(0f, 1f, 0f)) // Align cone/box default Y-up to axis
                scale(size)
            }
            val screenCenter = projectWorldToScreen(pos + handle.axis * size * 0.5f, vp) // Approx center
            handleBounds.add(GizmoHandleBounds(
                handle, screenCenter, size * 10f, // Screen radius approx
                projectDirection(handle.axis, view), worldMat,
                if (handle.type == GizmoHandle.HandleType.TRANSLATE_AXIS) GizmoHandleBounds.BoundsType.CONE else GizmoHandleBounds.BoundsType.QUAD
            ))
        }
        // Center cube for uniform translate (optional)
        handleBounds.add(GizmoHandleBounds(GizmoHandle.NONE, projectWorldToScreen(pos, vp), size * 15f, Vec3.ZERO, Mat4.identity().apply { translate(pos); scale(size * 0.2f) }, GizmoHandleBounds.BoundsType.BOX))
    }

    private fun generateRotateBounds(entity: Entity, view: Mat4, proj: Mat4, vp: Mat4, size: Float) {
        val pos = entity.transform.worldPosition
        val axes = listOf(GizmoHandle.ROTATE_X, GizmoHandle.ROTATE_Y, GizmoHandle.ROTATE_Z, GizmoHandle.ROTATE_SCREEN)

        for (handle in axes) {
            val worldMat = Mat4.identity().apply {
                translate(pos)
                if (handle != GizmoHandle.ROTATE_SCREEN) rotateTo(handle.axis, Vec3(0f, 0f, 1f)) // Align Torus Z-up to axis
                scale(size * 1.2f)
            }
            handleBounds.add(GizmoHandleBounds(
                handle, projectWorldToScreen(pos, vp), size * 25f, Vec3.ZERO, worldMat, GizmoHandleBounds.BoundsType.CIRCLE
            ))
        }
    }

    private fun generateScaleBounds(entity: Entity, view: Mat4, proj: Mat4, vp: Mat4, size: Float) {
        val pos = entity.transform.worldPosition
        val axes = listOf(GizmoHandle.SCALE_X, GizmoHandle.SCALE_Y, GizmoHandle.SCALE_Z, GizmoHandle.SCALE_UNIFORM)

        for (handle in axes) {
            val offset = if (handle == GizmoHandle.SCALE_UNIFORM) Vec3.ZERO else handle.axis * size * 0.8f
            val worldMat = Mat4.identity().apply {
                translate(pos + offset)
                if (handle != GizmoHandle.SCALE_UNIFORM) rotateTo(handle.axis, Vec3(0f, 1f, 0f))
                scale(size * 0.3f)
            }
            handleBounds.add(GizmoHandleBounds(
                handle, projectWorldToScreen(pos + offset, vp), size * 12f, projectDirection(handle.axis, view), worldMat,
                if (handle == GizmoHandle.SCALE_UNIFORM) GizmoHandleBounds.BoundsType.BOX else GizmoHandleBounds.BoundsType.BOX
            ))
        }
    }

    private fun checkBoundsHit(bounds: GizmoHandleBounds, x: Float, y: Float): Boolean {
        val dx = x - bounds.centerScreen.x
        val dy = y - bounds.centerScreen.y
        val distSq = dx * dx + dy * dy
        val radius = bounds.radiusScreen
        return distSq <= (radius * radius)
    }

    private fun projectWorldToScreen(world: Vec3, vp: Mat4): Vec3 {
        val clip = vp * world.toVec4(1f)
        if (clip.w == 0f) return Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val ndc = Vec3(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w)
        return Vec3(
            (ndc.x * 0.5f + 0.5f) * viewportSize.x,
            (1f - (ndc.y * 0.5f + 0.5f)) * viewportSize.y, // Flip Y
            ndc.z
        )
    }

    private fun projectDirection(worldDir: Vec3, view: Mat4): Vec3 {
        val viewDir = (view * worldDir.toVec4(0f)).toVec3().normalize()
        return Vec3(viewDir.x, -viewDir.y, 0f).normalize() // Screen space 2D dir
    }

    private fun projectScreenToPlane(x: Float, y: Float, planeOrigin: Vec3, planeNormal: Vec3, cam: Camera): Vec3? {
        val ray = cam.screenPointToRay(x, y, viewportSize.x.toInt(), viewportSize.y.toInt())
        val denom = planeNormal.dot(ray.direction)
        if (abs(denom) < 1e-6) return null
        val t = planeNormal.dot(planeOrigin - ray.origin) / denom
        return if (t >= 0) ray.origin + ray.direction * t else null
    }

    private fun projectScreenToLine(x: Float, y: Float, lineOrigin: Vec3, lineDir: Vec3, cam: Camera): Vec3? {
        val ray = cam.screenPointToRay(x, y, viewportSize.x.toInt(), viewportSize.y.toInt())
        // Closest point between two lines (ray and gizmo axis)
        val w0 = ray.origin - lineOrigin
        val a = ray.direction.dot(ray.direction)
        val b = ray.direction.dot(lineDir)
        val c = lineDir.dot(lineDir)
        val d = ray.direction.dot(w0)
        val e = lineDir.dot(w0)
        val denom = a * c - b * b
        if (abs(denom) < 1e-6) return null // Parallel
        val t = (b * e - c * d) / denom
        return if (t >= 0) ray.origin + ray.direction * t else null
    }

    private fun applyTranslation(delta: Vec3) {
        targetEntity?.let { e ->
            var finalDelta = delta
            if (snapEnabled) {
                finalDelta = Vec3(
                    roundToIncrement(delta.x, snapIncrement),
                    roundToIncrement(delta.y, snapIncrement),
                    roundToIncrement(delta.z, snapIncrement)
                )
            }
            e.transform.worldPosition += finalDelta
        }
    }

    private fun applyRotation(axis: Vec3, degrees: Float) {
        targetEntity?.let { e ->
            val q = Quat.fromAxisAngle(axis.normalize(), Math.toRadians(degrees).toFloat())
            e.transform.worldRotation = q * e.transform.worldRotation
        }
    }

    private fun applyScale(factor: Vec3) {
        targetEntity?.let { e ->
            e.transform.worldScale *= factor
        }
    }

    private fun roundToIncrement(value: Float, increment: Float): Float {
        return if (increment > 0) (value / increment).roundToInt() * increment else value
    }

    // Helper extension for Mat4 (Mocking standard math lib capabilities)
    private fun Mat4.translate(v: Vec3): Mat4 { this.translation(v); return this }
    private fun Mat4.scale(s: Float): Mat4 { this.scale(s); return this }
    private fun Mat4.scale(v: Vec3): Mat4 { this.scale(v); return this }
    private fun Mat4.rotateTo(from: Vec3, to: Vec3): Mat4 {
        val axis = from.cross(to)
        val dot = from.dot(to)
        if (axis.lengthSquared() < 1e-6) {
            if (dot < 0) this.rotate(Quat.fromAxisAngle(Vec3(1f,0f,0f), 3.14159f))
            return this
        }
        this.rotate(Quat.fromAxisAngle(axis.normalize(), acos(dot.clamp(-1f, 1f))))
        return this
    }
}

// Mock Data Classes / Interfaces expected by the code above (Minimal definitions for compilation context)
package com.prisma3d.engine.math

inline class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    companion object { val ZERO = Vec3(); val UP = Vec3(0f,1f,0f); val X = Vec3(1f,0f,0f); val Y = Vec3(0f,1f,0f); val Z = Vec3(0f,0f,1f) }
    operator fun plus(v: Vec3) = Vec3(x+v.x, y+v.y, z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x, y-v.y, z-v.z)
    operator fun times(s: Float) = Vec3(x*s, y*s, z*s)
    operator fun div(s: Float) = Vec3(x/s, y/s, z/s)
    fun length() = sqrt(x*x + y*y + z*z)
    fun lengthSquared() = x*x + y*y + z*z
    fun normalize() = if (length() > 0) this / length() else ZERO
    fun dot(v: Vec3) = x*v.x + y*v.y + z*v.z
    fun cross(v: Vec3) = Vec3(y*v.z - z*v.y, z*v.x - x*v.z, x*v.y - y*v.x)
    fun toVec4(w: Float) = Vec4(x, y, z, w)
    fun clamp(min: Float, max: Float) = Vec3(x.coerceIn(min, max), y.coerceIn(min, max), z.coerceIn(min, max))
}

inline class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun toVec3() = Vec3(x, y, z)
}

class Mat4 {
    companion object { fun identity() = Mat4() }
    var m00=1f; var m01=0f; var m02=0f; var m03=0f
    var m10=0f; var m11=1f; var m12=0f; var m13=0f
    var m20=0f; var m21=0f; var m22=1f; var m23=0f
    var m30=0f; var m31=0f; var m32=0f; var m33=1f
    operator fun times(other: Mat4): Mat4 = Mat4().apply { /* mul logic */ }
    operator fun times(v: Vec4): Vec4 = Vec4(0f,0f,0f,0f) // stub
    fun translation(v: Vec3) { m30=v.x; m31=v.y; m32=v.z }
    fun scale(s: Float) { m00*=s; m11*=s; m22*=s }
    fun scale(v: Vec3) { m00*=v.x; m11*=v.y; m22*=v.z }
    fun rotate(q: Quat) { /* rotation logic */ }
    fun copy() = Mat4()
}

class Quat {
    companion object {
        fun fromAxisAngle(axis: Vec3, angle: Float) = Quat()
        fun identity() = Quat()
    }
    operator fun times(q: Quat) = Quat()
}

package com.prisma3d.engine.scene

class Entity { val id = UUID.randomUUID(); val transform = Transform() }
class Transform { var worldMatrix = Mat4.identity(); var worldPosition = Vec3.ZERO; var worldRotation = Quat.identity(); var worldScale = Vec3(1f,1f,1f) }
class Scene
package com.prisma3d.engine.scene.command
data class TransformCommand(val entityId: UUID, val oldTransform: Mat4, val newTransform: Mat4)

package com.prisma3d.viewport.camera
class Camera {
    val position = Vec3.ZERO
    val viewMatrix = Mat4.identity()
    val projectionMatrix = Mat4.identity()
    var fovY: Float = 1.047f // 60 deg
    fun getViewDirection() = Vec3(0f,0f,-1f)
    fun screenPointToRay(x: Float, y: Float, w: Int, h: Int) = Ray(Vec3.ZERO, Vec3(0f,0f,-1f))
}
data class Ray(val origin: Vec3, val direction: Vec3)

import kotlin.math.*
package com.prisma3d.viewport

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

class PrismaViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var engine: PrismaEngine? = null
    private val isRendering = AtomicBoolean(false)
    private var renderJob: Job? = null
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isRendering.get()) {
                engine?.renderFrame()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isGizmoActive = false
    private var activeGizmoAxis: GizmoAxis? = null

    init {
        setSurfaceTextureListener(this)
        setOpaque(false)

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!isGizmoActive) {
                    if (e2.pointerCount == 1) {
                        engine?.orbitCamera(-distanceX, -distanceY)
                    } else if (e2.pointerCount == 2) {
                        engine?.panCamera(-distanceX, distanceY)
                    }
                } else {
                    activeGizmoAxis?.let { axis ->
                        val deltaX = e2.x - lastTouchX
                        val deltaY = e2.y - lastTouchY
                        engine?.translateGizmo(axis, deltaX, deltaY)
                    }
                }
                lastTouchX = e2.x
                lastTouchY = e2.y
                requestRender()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                lastTouchX = e.x
                lastTouchY = e.y
                val hitResult = engine?.hitTestGizmo(e.x, e.y)
                if (hitResult != null) {
                    isGizmoActive = true
                    activeGizmoAxis = hitResult.axis
                } else {
                    isGizmoActive = false
                    activeGizmoAxis = null
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                engine?.selectObjectAt(e.x, e.y)
                requestRender()
                return true
            }
        })

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isGizmoActive) {
                    engine?.zoomCamera(detector.scaleFactor)
                    requestRender()
                }
                return true
            }
        })
    }

    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
        engine = PrismaEngine(surface, width, height)
        startRendering()
    }

    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
        engine?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
        stopRendering()
        engine?.dispose()
        engine = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isGizmoActive = false
                activeGizmoAxis = null
                engine?.endGizmoInteraction()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    private fun startRendering() {
        if (isRendering.compareAndSet(false, true)) {
            Choreographer.getInstance().postFrameCallback(choreographerCallback)
        }
    }

    private fun stopRendering() {
        if (isRendering.compareAndSet(true, false)) {
            Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        }
        renderJob?.cancel()
        renderJob = null
    }

    fun requestRender() {
        if (!isRendering.get()) {
            engine?.renderFrame()
        }
    }

    fun resume() {
        if (engine != null && !isRendering.get()) {
            startRendering()
        }
    }

    fun pause() {
        stopRendering()
    }

    fun setRenderMode(mode: RenderMode) {
        engine?.setRenderMode(mode)
        requestRender()
    }

    enum class RenderMode {
        REALTIME, ON_DEMAND
    }

    sealed class GizmoAxis {
        object X : GizmoAxis()
        object Y : GizmoAxis()
        object Z : GizmoAxis()
        object XY : GizmoAxis()
        object YZ : GizmoAxis()
        object ZX : GizmoAxis()
        object CENTER : GizmoAxis()
    }

    data class GizmoHitResult(val axis: GizmoAxis, val distance: Float)
}

class PrismaEngine private constructor(
    private val surface: android.graphics.SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int
) {
    private var width = initialWidth
    private var height = initialHeight
    private var renderMode = PrismaViewport.RenderMode.REALTIME
    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeInit(surface, width, height)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        nativeResize(nativeHandle, width, height)
    }

    fun renderFrame() {
        nativeRender(nativeHandle)
    }

    fun orbitCamera(deltaX: Float, deltaY: Float) {
        nativeOrbitCamera(nativeHandle, deltaX, deltaY)
    }

    fun panCamera(deltaX: Float, deltaY: Float) {
        nativePanCamera(nativeHandle, deltaX, deltaY)
    }

    fun zoomCamera(factor: Float) {
        nativeZoomCamera(nativeHandle, factor)
    }

    fun selectObjectAt(x: Float, y: Float) {
        nativeSelectObject(nativeHandle, x, y, width, height)
    }

    fun hitTestGizmo(x: Float, y: Float): PrismaViewport.GizmoHitResult? {
        val result = nativeHitTestGizmo(nativeHandle, x, y, width, height)
        return if (result != null) {
            PrismaViewport.GizmoHitResult(
                axis = when (result.axis) {
                    0 -> PrismaViewport.GizmoAxis.X
                    1 -> PrismaViewport.GizmoAxis.Y
                    2 -> PrismaViewport.GizmoAxis.Z
                    3 -> PrismaViewport.GizmoAxis.XY
                    4 -> PrismaViewport.GizmoAxis.YZ
                    5 -> PrismaViewport.GizmoAxis.ZX
                    6 -> PrismaViewport.GizmoAxis.CENTER
                    else -> PrismaViewport.GizmoAxis.CENTER
                },
                distance = result.distance
            )
        } else null
    }

    fun translateGizmo(axis: PrismaViewport.GizmoAxis, deltaX: Float, deltaY: Float) {
        val axisIndex = when (axis) {
            PrismaViewport.GizmoAxis.X -> 0
            PrismaViewport.GizmoAxis.Y -> 1
            PrismaViewport.GizmoAxis.Z -> 2
            PrismaViewport.GizmoAxis.XY -> 3
            PrismaViewport.GizmoAxis.YZ -> 4
            PrismaViewport.GizmoAxis.ZX -> 5
            PrismaViewport.GizmoAxis.CENTER -> 6
        }
        nativeTranslateGizmo(nativeHandle, axisIndex, deltaX, deltaY)
    }

    fun endGizmoInteraction() {
        nativeEndGizmoInteraction(nativeHandle)
    }

    fun setRenderMode(mode: PrismaViewport.RenderMode) {
        renderMode = mode
        nativeSetRenderMode(nativeHandle, mode.ordinal)
    }

    fun dispose() {
        nativeDispose(nativeHandle)
        nativeHandle = 0
    }

    companion object {
        private var loaded = false
        fun loadLibrary() {
            if (!loaded) {
                System.loadLibrary("prisma_engine")
                loaded = true
            }
        }
    }

    external fun nativeInit(surface: android.graphics.SurfaceTexture, width: Int, height: Int): Long
    external fun nativeDispose(handle: Long)
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeRender(handle: Long)
    external fun nativeOrbitCamera(handle: Long, deltaX: Float, deltaY: Float)
    external fun nativePanCamera(handle: Long, deltaX: Float, deltaY: Float)
    external fun nativeZoomCamera(handle: Long, factor: Float)
    external fun nativeSelectObject(handle: Long, x: Float, y: Float, width: Int, height: Int)
    external fun nativeHitTestGizmo(handle: Long, x: Float, y: Float, width: Int, height: Int): HitResult?
    external fun nativeTranslateGizmo(handle: Long, axis: Int, deltaX: Float, deltaY: Float)
    external fun nativeEndGizmoInteraction(handle: Long)
    external fun nativeSetRenderMode(handle: Long, mode: Int)

    private data class HitResult(val axis: Int, val distance: Float)
}
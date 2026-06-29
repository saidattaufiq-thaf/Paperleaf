package com.paperleaf.sketchbook.pageflip.premium

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.jvm.Volatile
import kotlin.math.abs
import com.paperleaf.sketchbook.pageflip.engine.ShaderManager
import com.paperleaf.sketchbook.pageflip.premium.animation.AnimationController
import com.paperleaf.sketchbook.pageflip.premium.gesture.GestureController
import com.paperleaf.sketchbook.pageflip.premium.mesh.MeshGenerator
import com.paperleaf.sketchbook.pageflip.premium.physics.PagePhysics
import com.paperleaf.sketchbook.pageflip.premium.rendering.BookRenderer
import com.paperleaf.sketchbook.pageflip.premium.rendering.FrameProfiler
import com.paperleaf.sketchbook.pageflip.premium.rendering.GpuBuffer
import com.paperleaf.sketchbook.pageflip.premium.rendering.LightingRenderer
import com.paperleaf.sketchbook.pageflip.premium.rendering.PaperCurlRenderer
import com.paperleaf.sketchbook.pageflip.premium.rendering.ShadowRenderer
import com.paperleaf.sketchbook.pageflip.premium.rendering.TextureManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PremiumPageFlipView : GLSurfaceView, GLSurfaceView.Renderer {

    private val shaderManager = ShaderManager()
    private val meshGenerator = MeshGenerator()
    private val pagePhysics = PagePhysics()
    private val shadowRenderer = ShadowRenderer()
    private val lightingRenderer = LightingRenderer()
    private val curlRenderer = PaperCurlRenderer()
    private val bookRenderer = BookRenderer()
    private val gestureController = GestureController()
    private val animationController = AnimationController()
    private val textureManager = TextureManager()
    private val frameProfiler = FrameProfiler()
    private val gpuBuffer = GpuBuffer()

    private var mesh: MeshGenerator.MeshData? = null
    private var deformedVertices: FloatArray? = null
    private var noiseTextureId = 0

    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    private val emptyFloatBuffer = java.nio.ByteBuffer.allocateDirect(0).asFloatBuffer()
    private val emptyShortBuffer = java.nio.ByteBuffer.allocateDirect(0).asShortBuffer()

    private var pageTextureIds = IntArray(0)
    private var backTextureIds = IntArray(0)
    @Volatile private var hasTextures = false
    @Volatile private var isReady = false
    @Volatile private var flipProgress = 0f
    @Volatile private var pageThickness = PAGE_THICKNESS_DEFAULT
    @Volatile private var isDrawingMode = false
    private var lastCurl = -1f
    private var lastBend = -1f
    private var lastAxisX = Float.NaN
    private var lastAxisAngle = Float.NaN
    private var currentAxisX = 0f
    private var currentAxisAngle = 0f
    private var physicsDeltaMs = 16f

    var onFlipProgress: ((Float) -> Unit)? = null
    var onFlipComplete: ((Boolean) -> Unit)? = null

    companion object {
        private const val PAGE_WIDTH = 1.8f
        private const val PAGE_HEIGHT = 2.6f
        private const val PAGE_THICKNESS_DEFAULT = 0.02f
        private const val GRID_SIZE = 30
        private const val CAMERA_Z = 3.8f
        private const val SENSITIVITY = 0.002f
        private const val FLIP_COMPLETE_THRESHOLD = 0.5f

        private val LIGHT_DIRECTION = floatArrayOf(0.3f, 0.5f, 0.8f)
        private val AMBIENT_COLOR = floatArrayOf(0.40f, 0.38f, 0.42f)
        private val DIFFUSE_COLOR = floatArrayOf(0.70f, 0.68f, 0.65f)
        private val SPECULAR_COLOR = floatArrayOf(0.02f, 0.02f, 0.02f)
        private const val AXIS_ANGLE_MAX = 0.45f
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setPreserveEGLContextOnPause(true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isDrawingMode) return false
        gestureController.onTouchEvent(event)
        return true
    }

    fun setDrawingMode(drawing: Boolean) {
        isDrawingMode = drawing
    }

    fun setPages(front: Bitmap, back: Bitmap) {
        queueEvent {
            val ids = textureManager.allocate(2)
            pageTextureIds = ids
            textureManager.uploadMipChain(front, ids[0])
            textureManager.uploadMipChain(back, ids[1])
            hasTextures = true
            requestRender()
        }
    }

    fun setPageTextures(textures: List<Bitmap>) {
        queueEvent {
            val ids = textureManager.allocate(textures.size)
            pageTextureIds = ids
            textures.forEachIndexed { i, bmp ->
                if (i < ids.size) {
                    textureManager.uploadMipChain(bmp, ids[i])
                }
            }
            hasTextures = true
            requestRender()
        }
    }

    fun setBackTextures(backTextures: List<Bitmap>) {
        queueEvent {
            val ids = textureManager.allocate(backTextures.size)
            backTextureIds = ids
            backTextures.forEachIndexed { i, bmp ->
                if (i < ids.size) {
                    textureManager.uploadMipChain(bmp, ids[i])
                }
            }
        }
    }

    fun setFlipProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        queueEvent {
            flipProgress = p
            pagePhysics.setPosition(p)
            requestRender()
        }
    }

    fun animateFlip(target: Float, velocity: Float = 0f, onComplete: (() -> Unit)? = null) {
        animationController.startSpringAnimation(
            target = target,
            initialVelocity = velocity,
            onUpdate = { value ->
                flipProgress = value.coerceIn(0f, 1f)
                pagePhysics.setPosition(flipProgress)
                onFlipProgress?.invoke(flipProgress)
                requestRender()
            },
            onComplete = {
                onFlipComplete?.invoke(flipProgress >= 1f)
                onComplete?.invoke()
            }
        )
    }

    fun setPageThickness(thickness: Float) {
        val t = thickness.coerceIn(0.005f, 0.05f)
        queueEvent {
            pageThickness = t
        }
    }

    fun getFrameProfiler(): FrameProfiler = frameProfiler

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGl()

        GLES30.glClearColor(0.10f, 0.10f, 0.12f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shaderManager.initialize()
        curlRenderer.initialize(shaderManager)
        shadowRenderer.initialize(shaderManager)
        lightingRenderer.initialize(shaderManager)
        bookRenderer.initialize()

        val gridRes = frameProfiler.getGridResolution()
        mesh = meshGenerator.generatePlaneMesh(PAGE_WIDTH, PAGE_HEIGHT, gridRes)
        deformedVertices = FloatArray(mesh!!.vertices.size)

        gpuBuffer.initialize(GLES30.GL_DYNAMIC_DRAW)
        mesh?.let {
            gpuBuffer.uploadInterleaved(it.vertices, it.indices, GLES30.GL_DYNAMIC_DRAW)
        }

        textureManager.initialize()

        noiseTextureId = generateNoiseTexture()
        curlRenderer.setNoiseTexture(noiseTextureId)

        frameProfiler.reset()
        isReady = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)

        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 40f, aspect, 0.1f, 100f)

        val camDist = if (aspect > 1f) CAMERA_Z else CAMERA_Z * 1.3f
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, camDist,
            0f, 0f, 0f,
            0f, 1f, 0f)

        gestureController.initialize(context, width.toFloat(), height.toFloat()) { event ->
            queueEvent { handleGestureEvent(event) }
        }

        lightingRenderer.updateConfig(lightingRenderer.config.copy(
            viewPosition = floatArrayOf(0f, 0f, camDist)
        ))

        val supports120 = frameProfiler.supports120Fps(context)
        frameProfiler.request120Fps(supports120)
    }

    override fun onDrawFrame(gl: GL10?) {
        frameProfiler.beginFrame()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!isReady || mesh == null) {
            frameProfiler.endFrame()
            return
        }

        animationController.update()

        val now = SystemClock.uptimeMillis()
        physicsDeltaMs = 0.016f

        pagePhysics.update(physicsDeltaMs)

        val currentCurl = pagePhysics.getCurlFactor()
        val currentBend = pagePhysics.getBendPosition()
        val currentAngle = pagePhysics.getCurlAngle()
        val currentRadius = pagePhysics.getCurlRadius()

        if (abs(currentCurl - lastCurl) > 0.0001f ||
            abs(currentBend - lastBend) > 0.0001f ||
            abs(currentAxisX - lastAxisX) > 0.0001f ||
            abs(currentAxisAngle - lastAxisAngle) > 0.0001f) {
            meshGenerator.applyCurlDeformation(
                mesh!!,
                currentAngle,
                currentRadius,
                currentAxisX,
                currentAxisAngle,
                outVertices = deformedVertices!!
            )
            gpuBuffer.updateVertexSubData(deformedVertices!!)
            lastCurl = currentCurl
            lastBend = currentBend
            lastAxisX = currentAxisX
            lastAxisAngle = currentAxisAngle
            frameProfiler.markVboUpload()
        }

        Matrix.setIdentityM(modelMatrix, 0)
        val openAngle = flipProgress * 180f
        Matrix.rotateM(modelMatrix, 0, -openAngle * 0.3f, 0f, 1f, 0f)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        bookRenderer.render(mvpMatrix, flipProgress, pageThickness)

        renderFlippingPage(currentCurl, currentBend)

        renderShadows(currentCurl)

        if (!hasTextures) {
            renderPlaceholder()
        }

        frameProfiler.endFrame()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun renderFlippingPage(ignored0: Float, ignored1: Float) {
        if (mesh == null) return

        val frontTex = if (hasTextures && pageTextureIds.isNotEmpty()) {
            pageTextureIds[if (flipProgress > 0.5f && pageTextureIds.size > 1) 1 else 0]
        } else 0

        if (frontTex > 0 && lightingRenderer.isReady()) {
            lightingRenderer.render(
                mvpMatrix = mvpMatrix,
                textureId = frontTex,
                vertexBuffer = emptyFloatBuffer,
                texCoordBuffer = emptyFloatBuffer,
                normalBuffer = emptyFloatBuffer,
                indexBuffer = emptyShortBuffer,
                indexCount = mesh!!.indexCount,
                modelMatrix = modelMatrix,
                gpuBuffer = gpuBuffer
            )
            frameProfiler.markDrawCall()
        } else if (frontTex > 0) {
            curlRenderer.renderFront(
                mvpMatrix, frontTex,
                emptyFloatBuffer,
                emptyShortBuffer,
                mesh!!.indexCount,
                usePaperTexture = true,
                roughness = 0.3f,
                brightness = 0.95f,
                gpuBuffer = gpuBuffer
            )
            frameProfiler.markDrawCall()
        }

        GLES30.glCullFace(GLES30.GL_FRONT)
        if (frontTex > 0) {
            val backTex = if (hasTextures && backTextureIds.isNotEmpty()) {
                backTextureIds[0]
            } else frontTex

            curlRenderer.renderBack(
                mvpMatrix = mvpMatrix,
                frontTextureId = frontTex,
                backTextureId = backTex,
                vertexBuffer = emptyFloatBuffer,
                indexBuffer = emptyShortBuffer,
                indexCount = mesh!!.indexCount,
                lightDirection = LIGHT_DIRECTION,
                ambientColor = AMBIENT_COLOR,
                diffuseColor = DIFFUSE_COLOR,
                specularColor = SPECULAR_COLOR,
                warmth = 0.35f,
                roughness = 0.65f,
                brightness = 0.90f,
                translucency = 0.18f,
                rimIntensity = 0.08f,
                aoIntensity = 0.30f,
                gpuBuffer = gpuBuffer
            )
            frameProfiler.markDrawCall()
        }
        GLES30.glCullFace(GLES30.GL_BACK)
    }

    private fun renderShadows(curlFactor: Float) {
        if (mesh == null) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        val quality = frameProfiler.getQuality()
        val shadowStrength = when (quality) {
            FrameProfiler.QualityLevel.ULTRA -> 1.0f
            FrameProfiler.QualityLevel.HIGH -> 0.85f
            FrameProfiler.QualityLevel.MEDIUM -> 0.7f
            FrameProfiler.QualityLevel.LOW -> 0.5f
        }

        if (shadowStrength > 0.01f) {
            shadowRenderer.renderEdgeShadow(
                mvpMatrix,
                emptyFloatBuffer,
                emptyFloatBuffer,
                emptyShortBuffer,
                mesh!!.indexCount, curlFactor * shadowStrength,
                gpuBuffer
            )
            frameProfiler.markDrawCall()

            shadowRenderer.renderBaseShadow(
                mvpMatrix,
                emptyFloatBuffer,
                emptyFloatBuffer,
                emptyShortBuffer,
                mesh!!.indexCount, curlFactor * shadowStrength,
                gpuBuffer = gpuBuffer
            )
            frameProfiler.markDrawCall()
        }

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun renderPlaceholder() {
        curlRenderer.renderFront(
            mvpMatrix, 0,
            emptyFloatBuffer,
            emptyShortBuffer,
            mesh!!.indexCount,
            usePaperTexture = false,
            roughness = 0f,
            brightness = 0f,
            gpuBuffer = gpuBuffer
        )
        frameProfiler.markDrawCall()
    }

    private fun handleGestureEvent(event: GestureController.GestureEvent) {
        when (event) {
            is GestureController.GestureEvent.Pan -> {
                flipProgress = (flipProgress + event.deltaX * SENSITIVITY).coerceIn(0f, 1f)
                pagePhysics.setPosition(flipProgress)
                val halfWidth = PAGE_WIDTH / 2f
                currentAxisX = halfWidth * (1f - 2f * event.foldOriginX.coerceIn(0f, 1f))
                currentAxisAngle = -AXIS_ANGLE_MAX * (1f - 2f * event.foldOriginY.coerceIn(0f, 1f))
                onFlipProgress?.invoke(flipProgress)
                requestRender()
            }
            is GestureController.GestureEvent.Swipe -> {
                val target = if (event.direction == GestureController.SwipeDirection.LEFT) 1f else 0f
                val velocity = event.velocity * 0.002f
                animateFlip(target, velocity)
            }
            is GestureController.GestureEvent.FlipStart -> {
                currentAxisX = (PAGE_WIDTH / 2f) * (1f - 2f * pagePhysics.getBendPosition())
                currentAxisAngle = 0f
            }
            is GestureController.GestureEvent.FlipEnd -> {
                pagePhysics.clearManualFoldPosition()
                if (flipProgress > FLIP_COMPLETE_THRESHOLD) {
                    animateFlip(1f, pagePhysics.getState().velocity)
                } else {
                    animateFlip(0f)
                }
            }
            is GestureController.GestureEvent.Tap -> {}
            is GestureController.GestureEvent.LongPress -> {}
            is GestureController.GestureEvent.Scale -> {}
            is GestureController.GestureEvent.StylusPress -> {
                isDrawingMode = true
            }
        }
    }

    private fun generateNoiseTexture(size: Int = 128): Int {
        fun hashV(px: Int, py: Int): Float {
            val dot = px * 127.1f + py * 311.7f
            val h = (dot * 43758.5453123f) % 1f
            return if (h < 0) h + 1f else h
        }
        val pixels = IntArray(size * size)
        val invSize = 1.0f / size
        val octaves = 4
        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x.toFloat() * invSize
                val v = y.toFloat() * invSize
                var n = 0f
                var amp = 1f
                var freq = 3f
                var totalAmp = 0f
                for (o in 0 until octaves) {
                    val fu = u * freq
                    val fv = v * freq
                    val ix = fu.toInt()
                    val iy = fv.toInt()
                    val fx = fu - ix
                    val fy = fv - iy
                    val sx = fx * fx * (3f - 2f * fx)
                    val sy = fy * fy * (3f - 2f * fy)

                    val a = hashV(ix, iy)
                    val b = hashV(ix + 1, iy)
                    val c = hashV(ix, iy + 1)
                    val d = hashV(ix + 1, iy + 1)

                    val top = a + (b - a) * sx
                    val bottom = c + (d - c) * sx
                    val valNoise = top + (bottom - top) * sy

                    n += valNoise * amp
                    totalAmp += amp
                    amp *= 0.5f
                    freq *= 2f
                }
                n /= totalAmp
                val pixel = (n * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = -0x1000000 or (pixel shl 16) or (pixel shl 8) or pixel
            }
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])

        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(pixels)
        buf.flip()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun releaseGl() {
        isReady = false
        hasTextures = false
        pageTextureIds = IntArray(0)
        backTextureIds = IntArray(0)
        noiseTextureId = 0
        shaderManager.releaseGl()
        curlRenderer.releaseGl()
        shadowRenderer.releaseGl()
        lightingRenderer.releaseGl()
        bookRenderer.releaseGl()
        gpuBuffer.releaseGl()
        textureManager.releaseGl()
        mesh = null
    }

    fun cleanup() {
        queueEvent {
            isReady = false
            textureManager.releaseAll()
            shaderManager.cleanup()
            shadowRenderer.cleanup()
            lightingRenderer.cleanup()
            bookRenderer.cleanup()
            gpuBuffer.cleanup()
            animationController.cleanup()
            gestureController.cleanup()
        }
    }
}

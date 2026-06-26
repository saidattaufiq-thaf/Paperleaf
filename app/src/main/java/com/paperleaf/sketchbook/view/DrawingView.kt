package com.paperleaf.sketchbook.view

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.paperleaf.sketchbook.model.BrushSettings
import com.paperleaf.sketchbook.model.ClipboardManager
import com.paperleaf.sketchbook.selection.SelectionProcessor
import kotlin.math.*
import kotlinx.coroutines.*
import android.animation.ValueAnimator

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─── TOOL CONSTANTS ───────────────────────────────────────────
    companion object {
        const val TOOL_BRUSH   = 6
        const val TOOL_RULER   = 7
        const val TOOL_SCISSOR = 8
        const val TOOL_ROLLER  = 9
        const val TOOL_KEYBOARD = 10

        const val SCISSOR_NONE         = -1
        const val SCISSOR_LASSO        = 0
        const val SCISSOR_SMART_CUT    = 1
        const val SCISSOR_MAGIC_WAND   = 2
        const val SCISSOR_INVERSE      = 3
        const val SCISSOR_CUT          = 4
        const val SCISSOR_COPY         = 5
        const val SCISSOR_PASTE        = 6
        const val SCISSOR_DELETE       = 7
        const val SCISSOR_CANCEL       = 8
    }

    var brushSettings = BrushSettings()

    // ─── CORE BITMAP & CANVAS ─────────────────────────────────────
    private var workBitmap: Bitmap? = null
    private var workCanvas: Canvas? = null

    // ─── BACKGROUND TEXTURE ──────────────────────────────────────
    var backgroundTexture: Int = 0 // 0=plain, 1=textured, 2=watercolor, 3=canvas
        set(v) { field = v; applyBackgroundTexture(); invalidate() }

    var pageTemplate: Int = com.paperleaf.sketchbook.model.Page.TEMPLATE_BLANK
        set(v) { field = v; applyTemplate(); invalidate() }

    private fun applyTemplate() {
        val c = workCanvas ?: return
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        com.paperleaf.sketchbook.template.PaperTemplateEngine.drawTemplate(c, w, h, pageTemplate)
    }

    private fun applyBackgroundTexture() {
        val c = workCanvas ?: return
        when (backgroundTexture) {
            0 -> c.drawColor(Color.parseColor("#FAF9F0")) // plain
            1 -> { // textured - subtle noise effect
                c.drawColor(Color.parseColor("#F5F0E8"))
                val p = Paint().apply { alpha = 15 }
                for (i in 0 until 200) {
                    val rx = (Math.random() * 4200).toFloat()
                    val ry = (Math.random() * 2970).toFloat()
                    c.drawCircle(rx, ry, (Math.random() * 3 + 1).toFloat(), p)
                }
            }
            2 -> { // watercolor
                c.drawColor(Color.parseColor("#F0F5FA"))
                val p = Paint().apply { alpha = 20 }
                for (i in 0 until 50) {
                    val rx = (Math.random() * 4200).toFloat()
                    val ry = (Math.random() * 2970).toFloat()
                    val r = (Math.random() * 80 + 20).toFloat()
                    c.drawCircle(rx, ry, r, p)
                }
            }
            3 -> { // canvas
                c.drawColor(Color.parseColor("#EDE8E0"))
                val p = Paint().apply { alpha = 12 }
                for (i in 0 until 500) {
                    val rx = (Math.random() * 4200).toFloat()
                    val ry = (Math.random() * 2970).toFloat()
                    c.drawRect(rx, ry, rx + 4, ry + 1, p)
                }
            }
        }
    }

    // ─── UNDO / REDO ──────────────────────────────────────────────
    data class LayerSnapshot(
        val bitmap: Bitmap,
        val x: Float, val y: Float,
        val scale: Float, val rotation: Float,
        val isSelected: Boolean,
        val isVisible: Boolean, val isLocked: Boolean, val isMasking: Boolean,
        val isTextLayer: Boolean,
        val textContent: String?,
        val textFontSize: Float,
        val textIsHorizontal: Boolean, val textFlipH: Boolean, val textFlipV: Boolean,
        val textIsBold: Boolean, val textIsItalic: Boolean,
        val name: String,
        val nativeId: Int
    )

    data class CanvasSnapshot(
        val layers: List<LayerSnapshot>,
        val workBitmap: Bitmap
    )

    private val undoStack = ArrayDeque<CanvasSnapshot>()
    private val redoStack = ArrayDeque<CanvasSnapshot>()
    private val MAX_UNDO = 10
    private var undoInProgress = false

    // ─── LAYERS ───────────────────────────────────────────────────
    data class ImageLayer(
        var bitmap: Bitmap,
        var canvas: Canvas,
        var x: Float, var y: Float,
        var scale: Float = 1f,
        var rotation: Float = 0f,
        var isSelected: Boolean = true,
        var isVisible: Boolean = true,
        var isLocked: Boolean = false,
        var isMasking: Boolean = false,
        var isTextLayer: Boolean = false,
        var textContent: String? = null,
        var textFontSize: Float = 48f,
        var textAlign: Paint.Align = Paint.Align.LEFT,
        var textIsHorizontal: Boolean = true,
        var textFlipH: Boolean = false,
        var textFlipV: Boolean = false,
        var textIsBold: Boolean = false,
        var textIsItalic: Boolean = false,
        var textTypeface: Typeface? = null,
        var name: String = "Layer",
        var nativeId: Int = -1
    )

    val layers = mutableListOf<ImageLayer>()
    private var selectedLayer: ImageLayer? = null
    val selectedLayerOrNull: ImageLayer? get() = selectedLayer
    var isTransformMode = false
        set(v) {
            val prev = field
            field = v
            if (prev && !v) {
                // Exiting transform mode — bake visual transform into layer bitmap
                selectedLayer?.let { layer ->
                    if (!layer.isMasking) bakeLayerTransform(layer)
                }
            }
            invalidate()
        }

    var onLayersChanged: (() -> Unit)? = null

    // ─── CORNER RESIZE STATE ──────────────────────────────────────
    private enum class Corner { NONE, TL, TR, BL, BR }
    private var activeCorner = Corner.NONE
    private var cornerDragStart = PointF()
    private var cornerInitDst = RectF()
    private var cornerInitScale = 1f
    private var cornerLayerX = 0f
    private var cornerLayerY = 0f

    fun addEmptyLayer(): ImageLayer {
        saveState("addEmptyLayer")
        val w = workCanvas?.width ?: 3508
        val h = workCanvas?.height ?: 2480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val nativeId = if (nativeAvailable) nativeCreateLayer(w, h) else -1
        val layerNum = layers.size + 1
        val layer = ImageLayer(bmp, cv, 0f, 0f, name = "Layer $layerNum", nativeId = nativeId)
        layers.forEach { it.isSelected = false }
        layers.add(layer)
        selectedLayer = layer
        isTransformMode = false
        onLayersChanged?.invoke()
        invalidate()
        return layer
    }

    fun toggleLayerVisibility(layer: ImageLayer) {
        saveState("toggleLayerVisibility")
        layer.isVisible = !layer.isVisible
        if (nativeAvailable) nativeSetLayerVisibility(layer.nativeId, layer.isVisible)
        onLayersChanged?.invoke()
        invalidate()
    }

    fun isDefaultLayer(layer: ImageLayer): Boolean = layer === layers.firstOrNull()
    fun isLayerLocked(layer: ImageLayer): Boolean = layer.isLocked
    fun isLayerMasking(layer: ImageLayer): Boolean = layer.isMasking

    // ─── CANVAS TRANSFORM (PAN, ZOOM, ROTATE) ─────────────────────
    var canvasScale = 1f
    var canvasRotation = 0f
    var canvasTransX = 0f
    var canvasTransY = 0f
    val canvasMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private fun applyCanvasTransform() {
        canvasMatrix.reset()
        canvasMatrix.preScale(canvasScale, canvasScale)
        canvasMatrix.preTranslate(canvasTransX, canvasTransY)
        if (canvasRotation != 0f) {
            val cx = (workBitmap?.width ?: 3508) / 2f
            val cy = (workBitmap?.height ?: 2480) / 2f
            canvasMatrix.preRotate(canvasRotation, cx, cy)
        }
        canvasMatrix.invert(inverseMatrix)
    }

    // ─── LAYER TRANSFORM STATE ────────────────────────────────────
    private var initDist  = 0f; private var initScale = 1f
    private var initAngle = 0f; private var initRot   = 0f
    private var lastMidX  = 0f; private var lastMidY  = 0f
    private var dragStartX = 0f; private var dragStartY = 0f
    private var layerStartX = 0f; private var layerStartY = 0f

    // ─── RULER / SHAPE STATE ──────────────────────────────────────
    enum class ShapeType { LINE, RECT, CIRCLE }
    var activeShape: ShapeType? = null
    private var shapeStartX = 0f; private var shapeStartY = 0f
    private var shapeEndX   = 0f; private var shapeEndY   = 0f
    private var isDrawingShape = false

    // ─── SCISSOR / CROP STATE ─────────────────────────────────────
    private val scissorPath = Path()
    private var isScissoring = false
    private val scissorPoints = mutableListOf<PointF>()

    // ─── SELECTION STATE ──────────────────────────────────────────
    var scissorMode: Int = SCISSOR_NONE
    var hasSelection: Boolean = false
        private set
    private var isSelecting = false
    private val selectionPath = Path()
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    var onSelectionChanged: (() -> Unit)? = null
    private var magicWandJob: Job? = null
    private var marchingAntsPhase = 0f
    private var antsAnimator: ValueAnimator? = null

    // ─── PENDING IMAGE (PREVIEW SEBELUM DROP) ─────────────────────
    var pendingBitmap: Bitmap? = null
        private set
    private var pendingX = 0f; private var pendingY = 0f
    private var pendingScale = 1f

    // ─── IMAGE EDITING MODE (ADD IMAGE FLOW) ─────────────────────
    var isImageEditingMode = false
        private set
    var editingImageBitmap: Bitmap? = null
        private set
    var editImgX = 0f
    var editImgY = 0f
    var editImgScale = 1f
    private val dimOverlayPaint = Paint().apply {
        color = Color.parseColor("#80000000")
    }
    private val imageEditBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        color = Color.parseColor("#2196F3")
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    var onImageEditModeChanged: ((Boolean) -> Unit)? = null

    // ─── PAINTS ───────────────────────────────────────────────────
    private val layerPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or
        Paint.FILTER_BITMAP_FLAG or
        Paint.DITHER_FLAG
    )
    private val selectPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3"); style = Paint.Style.STROKE
        strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val handleFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val scissorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#FF5722")
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 200 }
    private val pendingBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#2196F3")
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val selectionOverlayPaint = Paint().apply {
        color = Color.parseColor("#80000000")
    }
    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val selectionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722"); style = Paint.Style.STROKE
        strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    // ─── ROTATION POPUP PAINTS (lazy agar tidak buat ulang tiap frame) ───
    private val rotationBgPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333"); style = Paint.Style.FILL
    }}
    private val rotationTextPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }}

    // ─── DRAWING STATE ────────────────────────────────────────────
    private var strokePaint = Paint()
    private var lastX = 0f; private var lastY = 0f; private var lastTime = 0L
    private var lastVel = 0f
    private val currentPath = Path()
    private var strokeLastMidX = 0f; private var strokeLastMidY = 0f
    private val strokePath = Path()

    // ─── IMPROVED STROKE PIPELINE ──────────────────────────────────
    private val strokePoints = mutableListOf<PointF>()
    private var smoothX = 0f
    private var smoothY = 0f
    private var strokeInitialized = false

    // ─── TEXT DRAG / TRANSFORM STATE ──────────────────────────────
    private var textDragStartX = 0f
    private var textDragStartY = 0f
    private var isTextDragging = false
    var textDragRect: RectF? = null
        private set

    var onTextLayerWarning: (() -> Unit)? = null

    // ─── GESTURE SYSTEM ───────────────────────────────────────────
    private enum class GestureState {
        IDLE, DRAWING, WAITING_TAP, WAITING_3_TAP, PINCH
    }
    private var gestureState = GestureState.IDLE
    private val density = context.resources.displayMetrics.density
    private val touchSlop = 20f * density
    private val pinchSlop = 16f
    private val doubleTapTime = 300L

    // Pinch / pan snapshot
    private var gStartDist = 0f; private var gStartScale = 1f
    private var gStartTransX = 0f; private var gStartTransY = 0f
    private var gStartMidX = 0f; private var gStartMidY = 0f
    private var gStartAngle = 0f; private var gStartRot = 0f

    // focal point in bitmap coordinates (updated on pinch start)
    private var gFocusBitmapX = 0f
    private var gFocusBitmapY = 0f

    // Double-tap tracking
    private var gLastTapUpTime = 0L
    private var gLastTapFingerCount = 0
    private var gLastTapDownXs = FloatArray(0)
    private var gLastTapDownYs = FloatArray(0)

    // Per-gesture finger origin
    private val gFingerStartX = FloatArray(10)
    private val gFingerStartY = FloatArray(10)
    private var gDownTime = 0L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Native library availability flag
    private var nativeAvailable = false

    // ─── INIT ─────────────────────────────────────────────────────
    init {
        SelectionProcessor.init()
        setOnApplyWindowInsetsListener { _, insets -> insets }
        nativeAvailable = try {
            System.loadLibrary("paperleaf_native")
            nativeInit(); true
        } catch (e: Throwable) { false }
        antsAnimator = ValueAnimator.ofFloat(0f, 24f).apply {
            duration = 400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                marchingAntsPhase = anim.animatedValue as Float
                if (hasSelection) invalidate()
            }
            start()
        }
    }

    // ─── MEASURE & LIFECYCLE ──────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: 800
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: (w / 1.4142f).toInt()
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && workBitmap == null) {
            initCanvas()
            fitCanvasToScreen()
        }
    }

    private fun initCanvas() {
        val bmpW = 3508; val bmpH = 2480 // Landscape A4 300DPI
        workBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).also {
            workCanvas = Canvas(it)
            workCanvas!!.drawColor(Color.parseColor("#FAF9F0"))
        }
        // Hanya buat default Layer 1 jika belum ada layer (cegah duplikasi saat loadPage selesai duluan)
        if (layers.isEmpty()) {
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val nativeId = if (nativeAvailable) nativeCreateLayer(bmpW, bmpH) else -1
            val layer = ImageLayer(bmp, cv, 0f, 0f, name = "Layer 1", nativeId = nativeId, isSelected = true)
            layers.add(layer)
            selectedLayer = layer
        }
    }

    private fun fitCanvasToScreen() {
        val scaleX = width.toFloat() / 3508f
        val scaleY = height.toFloat() / 2480f
        canvasScale = minOf(scaleX, scaleY)
        val scaledW = 3508f * canvasScale
        val scaledH = 2480f * canvasScale
        canvasTransX = (width - scaledW) / (2f * canvasScale)
        canvasTransY = (height - scaledH) / (2f * canvasScale)
        canvasRotation = 0f
        applyCanvasTransform()
        invalidate()
    }

    // ─── DRAWING ROUTINES ─────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        applyCanvasTransform()
        canvas.save()
        canvas.concat(canvasMatrix)

        val bmp = workBitmap ?: run { canvas.restore(); return }
        // Draw bitmap at its native size; canvasMatrix handles mapping to view coordinates
        val dst = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        canvas.drawBitmap(bmp, null, dst, layerPaint)

        if (isDrawingShape) drawShapePreview(canvas)
        if (isScissoring && scissorPoints.size > 1) canvas.drawPath(scissorPath, scissorPaint)
        for (layer in layers) drawLayer(canvas, layer)

        pendingBitmap?.let { pBmp ->
            val pDst = RectF(
                pendingX, pendingY,
                pendingX + pBmp.width * pendingScale,
                pendingY + pBmp.height * pendingScale
            )
            canvas.drawBitmap(pBmp, null, pDst, pendingPaint)
            canvas.drawRect(pDst, pendingBorderPaint)
        }

        if (!isImageEditingMode) {
            drawSelectionOverlay(canvas)
        }

        textDragRect?.let { rect ->
            val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#802196F3")
                style = Paint.Style.FILL
            }
            val dragBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2196F3")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            canvas.drawRect(rect, dragPaint)
            canvas.drawRect(rect, dragBorder)
        }

        canvas.restore()

        // Image editing overlay (view coordinates)
        if (isImageEditingMode && editingImageBitmap != null) {
            drawImageEditOverlay(canvas)
        }

        // Tampilkan sudut rotasi saat sedang memutar
        if (canvasRotation != 0f && gestureState == GestureState.PINCH) {
            val label = "${canvasRotation.toInt()}°"
            val tw = rotationTextPaint.measureText(label) + 16f
            val th = 22f * density
            val cx = width / 2f
            val cy = 50f * density
            val r = 6f * density
            canvas.drawRoundRect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f, r, r, rotationBgPaint)
            canvas.drawText(label, cx, cy + rotationTextPaint.textSize / 3f, rotationTextPaint)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        if (!hasSelection && !isSelecting) return
        if (scissorMode != SCISSOR_LASSO && scissorMode != SCISSOR_SMART_CUT && scissorMode != SCISSOR_MAGIC_WAND && !hasSelection) return
        val bmp = workBitmap ?: return

        if (hasSelection) {
            canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutPath(selectionPath)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipPath(selectionPath, Region.Op.DIFFERENCE)
            }
            canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), selectionOverlayPaint)
            canvas.restore()
            selectionBorderPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), marchingAntsPhase)
            canvas.drawPath(selectionPath, selectionBorderPaint)
        }
        if (isSelecting && !hasSelection && scissorMode != SCISSOR_NONE) {
            canvas.drawPath(selectionPath, selectionLinePaint)
        }
    }

    private fun drawLayer(canvas: Canvas, layer: ImageLayer) {
        if (!layer.isVisible) return
        val bmp = workBitmap ?: return
        val cw = bmp.width.toFloat(); val ch = bmp.height.toFloat()

        if (layer.isMasking) {
            canvas.save()
            canvas.clipRect(0f, 0f, cw, ch)
            drawMaskedLayerContent(canvas, layer)
            canvas.translate(layer.x, layer.y)
            canvas.rotate(layer.rotation, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
            canvas.scale(layer.scale, layer.scale, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
            drawLayerSelection(canvas, layer, RectF(0f, 0f, layer.bitmap.width.toFloat(), layer.bitmap.height.toFloat()))
            canvas.restore()
            return
        }

        canvas.save()
        canvas.clipRect(0f, 0f, cw, ch)
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
        canvas.scale(layer.scale, layer.scale, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
        val dst = RectF(0f, 0f, layer.bitmap.width.toFloat(), layer.bitmap.height.toFloat())
        canvas.drawBitmap(layer.bitmap, null, dst, layerPaint)
        drawLayerSelection(canvas, layer, dst)
        canvas.restore()
    }

    private fun drawLayerSelection(canvas: Canvas, layer: ImageLayer, dst: RectF) {
        if (!layer.isSelected) return
        if (layer.isTextLayer) {
            val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2196F3")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            }
            val hr = 16f / canvasScale
            canvas.drawRect(dst, selPaint)
            listOf(
                dst.left to dst.top, dst.right to dst.top,
                dst.left to dst.bottom, dst.right to dst.bottom
            ).forEach { (hx, hy) ->
                canvas.drawCircle(hx, hy, hr, handleFill)
                canvas.drawCircle(hx, hy, hr, handleBorder)
            }
        } else if (isTransformMode) {
            canvas.drawRect(dst, selectPaint)
            val hr = 28f / canvasScale
            listOf(dst.left to dst.top, dst.right to dst.top,
                   dst.left to dst.bottom, dst.right to dst.bottom
            ).forEach { (hx, hy) ->
                canvas.drawCircle(hx, hy, hr, handleFill)
                canvas.drawCircle(hx, hy, hr, handleBorder)
            }
        }
    }

    private fun findMaskTarget(maskLayer: ImageLayer): ImageLayer? {
        val idx = layers.indexOf(maskLayer)
        if (idx <= 0) return null
        for (i in (idx - 1) downTo 0) {
            val candidate = layers[i]
            if (!candidate.isVisible) continue
            if (!candidate.isMasking) return candidate
        }
        return null
    }

    private fun drawMaskedLayerContent(canvas: Canvas, maskLayer: ImageLayer) {
        val idx = layers.indexOf(maskLayer)
        val target = findMaskTarget(maskLayer)
        val bmp = workBitmap ?: return
        val cw = bmp.width.toFloat(); val ch = bmp.height.toFloat()
        if (target == null) {
            if (idx <= 0) {
                Log.w("DrawingView", "drawMaskedLayerContent: layer at index 0 cannot mask, drawing normally")
            } else {
                Log.d("DrawingView", "drawMaskedLayerContent: no non-mask target found below ${maskLayer.name}, mask hidden")
            }
            canvas.save()
            canvas.clipRect(0f, 0f, cw, ch)
            canvas.translate(maskLayer.x, maskLayer.y)
            canvas.rotate(maskLayer.rotation, maskLayer.bitmap.width / 2f, maskLayer.bitmap.height / 2f)
            canvas.scale(maskLayer.scale, maskLayer.scale, maskLayer.bitmap.width / 2f, maskLayer.bitmap.height / 2f)
            canvas.drawBitmap(maskLayer.bitmap, 0f, 0f, layerPaint)
            canvas.restore()
            return
        }
        Log.d("DrawingView", "drawMaskedLayerContent: maskLayer=${maskLayer.name} (idx=$idx) targeting ${target.name}")

        // Save layer for offscreen compositing — canvas is in identity space here
        val saveCount = canvas.saveLayer(null, null)

        // 1. Draw mask layer content with its visual transform
        canvas.save()
        canvas.clipRect(0f, 0f, cw, ch)
        canvas.translate(maskLayer.x, maskLayer.y)
        canvas.rotate(maskLayer.rotation, maskLayer.bitmap.width / 2f, maskLayer.bitmap.height / 2f)
        canvas.scale(maskLayer.scale, maskLayer.scale, maskLayer.bitmap.width / 2f, maskLayer.bitmap.height / 2f)
        canvas.drawBitmap(maskLayer.bitmap, 0f, 0f, layerPaint)
        canvas.restore()

        // 2. DST_IN — keep mask content only where target has non-transparent pixels
        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.save()
        canvas.clipRect(0f, 0f, cw, ch)
        canvas.translate(target.x, target.y)
        canvas.rotate(target.rotation, target.bitmap.width / 2f, target.bitmap.height / 2f)
        canvas.scale(target.scale, target.scale, target.bitmap.width / 2f, target.bitmap.height / 2f)
        canvas.drawBitmap(target.bitmap, 0f, 0f, clipPaint)
        canvas.restore()

        canvas.restoreToCount(saveCount)
    }

    private fun drawShapePreview(canvas: Canvas) {
        // Shapes are already in bitmap coordinates; matrix transform handles scaling/translation
        shapePaint.color = brushSettings.color
        shapePaint.strokeWidth = brushSettings.size
        when (activeShape) {
            ShapeType.LINE -> canvas.drawLine(
                shapeStartX, shapeStartY,
                shapeEndX, shapeEndY, shapePaint)
            ShapeType.RECT -> canvas.drawRect(
                minOf(shapeStartX, shapeEndX),
                minOf(shapeStartY, shapeEndY),
                maxOf(shapeStartX, shapeEndX),
                maxOf(shapeStartY, shapeEndY), shapePaint)
            ShapeType.CIRCLE -> {
                val r = hypot(shapeEndX - shapeStartX, shapeEndY - shapeStartY)
                canvas.drawCircle(
                    shapeStartX, shapeStartY, r, shapePaint)
            }
            else -> {}
        }
    }

    // ─── PUBLIC API ───────────────────────────────────────────────
    private fun ensureSoftwareBitmap(bmp: Bitmap): Bitmap {
        if (bmp.config != Bitmap.Config.HARDWARE && bmp.isMutable) return bmp
        return bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getBitmap(): Bitmap? = workBitmap

    fun setBitmap(@Suppress("UNUSED_PARAMETER") _src: Bitmap) {
        saveState("setBitmap")
        // Reset workCanvas to clean background only
        if (workCanvas != null) {
            workCanvas!!.drawColor(Color.parseColor("#FAF9F0"))
            applyTemplate()
        } else {
            workBitmap = Bitmap.createBitmap(3508, 2480, Bitmap.Config.ARGB_8888).also {
                workCanvas = Canvas(it)
                workCanvas!!.drawColor(Color.parseColor("#FAF9F0"))
            }
            applyTemplate()
        }
        fitCanvasToScreen()
    }

    fun createDefaultLayer(src: Bitmap? = null) {
        if (nativeAvailable) nativeClearLayers()
        layers.clear()
        val bmp = Bitmap.createBitmap(3508, 2480, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        if (src != null) {
            cv.drawBitmap(src, Rect(0,0,src.width,src.height), Rect(0,0,3508,2480), null)
        }
        val nativeId = if (nativeAvailable) nativeCreateLayer(3508, 2480) else -1
        val layer = ImageLayer(bmp, cv, 0f, 0f, name = "Layer 1", nativeId = nativeId, isSelected = true)
        layers.add(layer)
        selectedLayer = layer
        isTransformMode = false
        onLayersChanged?.invoke()
        invalidate()
    }

    fun setPendingImage(bmp: Bitmap) {
        val sbmp = ensureSoftwareBitmap(bmp)
        val cw = 3508f; val ch = 2480f
        val sc = minOf(cw * 0.7f / sbmp.width, ch * 0.7f / sbmp.height, 1f)
        pendingScale = sc
        pendingX = (cw - sbmp.width  * sc) / 2f
        pendingY = (ch - sbmp.height * sc) / 2f
        pendingBitmap = sbmp
        invalidate()
    }

    fun commitPendingImage() {
        val bmp = pendingBitmap ?: return
        pendingBitmap = null
        addImageLayer(bmp, pendingX, pendingY, pendingScale)
        invalidate()
    }

    fun cancelPendingImage() {
        pendingBitmap = null
        invalidate()
    }

    // ─── IMAGE EDITING MODE ────────────────────────────────────────
    fun enterImageEditMode(bmp: Bitmap) {
        val sbmp = ensureSoftwareBitmap(bmp)
        editingImageBitmap = sbmp
        editImgScale = minOf(width * 0.7f / sbmp.width, height * 0.7f / sbmp.height, 1f)
        editImgX = (width - sbmp.width * editImgScale) / 2f
        editImgY = (height - sbmp.height * editImgScale) / 2f
        isImageEditingMode = true
        hasSelection = false
        selectionPath.reset()
        scissorMode = SCISSOR_NONE
        onImageEditModeChanged?.invoke(true)
        invalidate()
    }

    fun exitImageEditingMode(): Bitmap? {
        val bmp = editingImageBitmap
        editingImageBitmap = null
        isImageEditingMode = false
        hasSelection = false
        selectionPath.reset()
        scissorMode = SCISSOR_NONE
        onImageEditModeChanged?.invoke(false)
        invalidate()
        return bmp
    }

    fun deleteImageEditSelection() {
        val bmp = editingImageBitmap ?: return
        if (!hasSelection || selectionPath.isEmpty) return
        val bmpPath = Path(selectionPath)
        val m = Matrix()
        m.postTranslate(-editImgX, -editImgY)
        m.postScale(1f / editImgScale, 1f / editImgScale)
        bmpPath.transform(m)
        val c = Canvas(bmp)
        c.clipPath(bmpPath)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        hasSelection = false
        selectionPath.reset()
        invalidate()
    }

    fun applySmartOnEditImage() {
        val bmp = editingImageBitmap ?: return
        smartCutJob?.cancel()
        smartCutJob = scope.launch {
            val path = SelectionProcessor.grabCutSelect(bmp, Rect(0, 0, bmp.width, bmp.height), 1f, 1f)
            withContext(Dispatchers.Main) {
                if (path != null && !path.isEmpty) {
                    val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(result)
                    c.clipPath(path)
                    c.drawBitmap(bmp, 0f, 0f, null)
                    editingImageBitmap = result
                }
                hasSelection = false
                selectionPath.reset()
                invalidate()
            }
        }
    }

    private val editLassoPoints = mutableListOf<Pair<Float, Float>>()

    private fun handleImageEditLassoTouch(event: MotionEvent, vx: Float, vy: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editLassoPoints.clear()
                editLassoPoints.add(vx to vy)
                selectionPath.reset()
                selectionPath.moveTo(vx, vy)
                isSelecting = true; hasSelection = false
            }
            MotionEvent.ACTION_MOVE -> {
                editLassoPoints.add(vx to vy)
                selectionPath.lineTo(vx, vy)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                editLassoPoints.add(vx to vy)
                selectionPath.close()
                isSelecting = false; hasSelection = true
                invalidate()
            }
        }
    }

    private fun handleImageEditSmartTouch(event: MotionEvent, vx: Float, vy: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionStartX = vx; selectionStartY = vy
                selectionPath.reset(); isSelecting = true; hasSelection = false
            }
            MotionEvent.ACTION_MOVE -> {
                selectionPath.reset()
                selectionPath.addRect(
                    minOf(selectionStartX, vx), minOf(selectionStartY, vy),
                    maxOf(selectionStartX, vx), maxOf(selectionStartY, vy),
                    Path.Direction.CW
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isSelecting = false
                val bx = minOf(selectionStartX, vx); val by = minOf(selectionStartY, vy)
                val bw = abs(vx - selectionStartX); val bh = abs(vy - selectionStartY)
                if (bw < 10f || bh < 10f) return
                val bmp = editingImageBitmap ?: return
                val bmpBx = ((bx - editImgX) / editImgScale).toInt().coerceIn(0, bmp.width - 1)
                val bmpBy = ((by - editImgY) / editImgScale).toInt().coerceIn(0, bmp.height - 1)
                val bmpBw = (bw / editImgScale).toInt().coerceIn(2, bmp.width - bmpBx)
                val bmpBh = (bh / editImgScale).toInt().coerceIn(2, bmp.height - bmpBy)
                smartCutJob?.cancel()
                smartCutJob = scope.launch {
                    val path = SelectionProcessor.grabCutSelect(
                        bmp, Rect(bmpBx, bmpBy, bmpBx + bmpBw, bmpBy + bmpBh), 1f, 1f
                    )
                    withContext(Dispatchers.Main) {
                        if (path != null && !path.isEmpty) {
                            val m = Matrix()
                            m.postScale(editImgScale, editImgScale)
                            m.postTranslate(editImgX, editImgY)
                            path.transform(m)
                            selectionPath.set(path)
                        }
                        hasSelection = true; invalidate()
                    }
                }
            }
        }
    }

    private fun handleImageEditMagicTap(vx: Float, vy: Float) {
        val bmp = editingImageBitmap ?: return
        val bmpX = ((vx - editImgX) / editImgScale).toInt().coerceIn(0, bmp.width - 1)
        val bmpY = ((vy - editImgY) / editImgScale).toInt().coerceIn(0, bmp.height - 1)
        magicWandJob?.cancel()
        magicWandJob = scope.launch {
            val path = SelectionProcessor.floodFillSelect(bmp, bmpX, bmpY, 40)
            withContext(Dispatchers.Main) {
                if (path != null && !path.isEmpty) {
                    val m = Matrix()
                    m.postScale(editImgScale, editImgScale)
                    m.postTranslate(editImgX, editImgY)
                    path.transform(m)
                    selectionPath.set(path)
                } else {
                    selectionPath.reset()
                    selectionPath.addCircle(vx, vy, 50f, Path.Direction.CW)
                }
                hasSelection = true; isSelecting = false; invalidate()
            }
        }
    }

    private fun handleImageEditTouch(event: MotionEvent) {
        val vx = event.x; val vy = event.y
        when (scissorMode) {
            SCISSOR_LASSO -> handleImageEditLassoTouch(event, vx, vy)
            SCISSOR_SMART_CUT -> handleImageEditSmartTouch(event, vx, vy)
            SCISSOR_MAGIC_WAND -> if (event.actionMasked == MotionEvent.ACTION_UP) handleImageEditMagicTap(vx, vy)
            else -> {}
        }
    }

    private fun drawImageEditOverlay(canvas: Canvas) {
        val bmp = editingImageBitmap ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimOverlayPaint)
        val dst = RectF(editImgX, editImgY,
            editImgX + bmp.width * editImgScale, editImgY + bmp.height * editImgScale)
        canvas.drawBitmap(bmp, null, dst, null)
        canvas.drawRect(dst, imageEditBorderPaint)
        if (hasSelection && !selectionPath.isEmpty && scissorMode != SCISSOR_NONE) {
            canvas.drawPath(selectionPath, selectionBorderPaint)
        }
        if (isSelecting && !hasSelection && scissorMode != SCISSOR_NONE) {
            canvas.drawPath(selectionPath, selectionLinePaint)
        }
    }

    fun importBitmap(src: Bitmap) {
        val sbmp = ensureSoftwareBitmap(src)
        val wc = workCanvas ?: return
        val cw = wc.width.toFloat(); val ch = wc.height.toFloat()
        val maxW = cw * 0.6f; val maxH = ch * 0.6f
        val scale = minOf(maxW / sbmp.width, maxH / sbmp.height, 1f)
        val dstW = (sbmp.width * scale).toInt(); val dstH = (sbmp.height * scale).toInt()
        val left = ((cw - dstW) / 2f).toInt(); val top  = ((ch - dstH) / 2f).toInt()
        saveState("importBitmap")
        wc.drawBitmap(sbmp, Rect(0,0,sbmp.width,sbmp.height), Rect(left,top,left+dstW,top+dstH), Paint())
        invalidate()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        undoInProgress = true
        pushCurrentStateToRedo()
        restoreSnapshot(undoStack.removeLast())
        undoInProgress = false
        invalidate()
        onLayersChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoInProgress = true
        pushCurrentStateToUndo()
        restoreSnapshot(redoStack.removeLast())
        undoInProgress = false
        invalidate()
        onLayersChanged?.invoke()
    }

    fun clearUndoRedo() {
        for (snap in undoStack) {
            if (!snap.workBitmap.isRecycled) snap.workBitmap.recycle()
            for (ls in snap.layers) {
                if (!ls.bitmap.isRecycled) ls.bitmap.recycle()
            }
        }
        for (snap in redoStack) {
            if (!snap.workBitmap.isRecycled) snap.workBitmap.recycle()
            for (ls in snap.layers) {
                if (!ls.bitmap.isRecycled) ls.bitmap.recycle()
            }
        }
        undoStack.clear()
        redoStack.clear()
    }

    fun cleanupMemory() {
        clearUndoRedo()
        if (workBitmap != null && !workBitmap!!.isRecycled) {
            workBitmap!!.recycle()
            workBitmap = null
            workCanvas = null
        }
        for (layer in layers) {
            if (!layer.bitmap.isRecycled) {
                layer.bitmap.recycle()
            }
        }
        layers.clear()
        selectedLayer = null
        if (nativeAvailable) nativeClearLayers()
    }

    private fun saveState(@Suppress("UNUSED_PARAMETER") operation: String? = null) {
        if (undoInProgress) return
        val wb = workBitmap ?: return
        if (wb.isRecycled) return
        val layerSnapshots = layers.mapNotNull { it.toSnapshot() }
        if (undoStack.size >= MAX_UNDO) {
            val removed = undoStack.removeFirst()
            if (!removed.workBitmap.isRecycled) removed.workBitmap.recycle()
            for (ls in removed.layers) {
                if (!ls.bitmap.isRecycled) ls.bitmap.recycle()
            }
        }
        undoStack.addLast(CanvasSnapshot(layerSnapshots, wb.copy(Bitmap.Config.ARGB_8888, true) ?: return))
        redoStack.clear()
    }

    private fun pushCurrentStateToRedo() {
        val wb = workBitmap ?: return
        if (wb.isRecycled) return
        val layerSnapshots = layers.mapNotNull { it.toSnapshot() }
        if (redoStack.size >= MAX_UNDO) {
            val removed = redoStack.removeFirst()
            if (!removed.workBitmap.isRecycled) removed.workBitmap.recycle()
            for (ls in removed.layers) {
                if (!ls.bitmap.isRecycled) ls.bitmap.recycle()
            }
        }
        redoStack.addLast(CanvasSnapshot(layerSnapshots, wb.copy(Bitmap.Config.ARGB_8888, true) ?: return))
    }

    private fun pushCurrentStateToUndo() {
        val wb = workBitmap ?: return
        if (wb.isRecycled) return
        val layerSnapshots = layers.mapNotNull { it.toSnapshot() }
        if (undoStack.size >= MAX_UNDO) {
            val removed = undoStack.removeFirst()
            if (!removed.workBitmap.isRecycled) removed.workBitmap.recycle()
            for (ls in removed.layers) {
                if (!ls.bitmap.isRecycled) ls.bitmap.recycle()
            }
        }
        undoStack.addLast(CanvasSnapshot(layerSnapshots, wb.copy(Bitmap.Config.ARGB_8888, true) ?: return))
    }

    private fun ImageLayer.toSnapshot(): LayerSnapshot? {
        if (bitmap.isRecycled) return null
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        return LayerSnapshot(
        bitmap = copy,
        x = x, y = y, scale = scale, rotation = rotation,
        isSelected = isSelected, isVisible = isVisible,
        isLocked = isLocked, isMasking = isMasking,
        isTextLayer = isTextLayer,
        textContent = textContent, textFontSize = textFontSize,
        textIsHorizontal = textIsHorizontal,
        textFlipH = textFlipH, textFlipV = textFlipV,
        textIsBold = textIsBold, textIsItalic = textIsItalic,
        name = name, nativeId = nativeId
    )
    }

    private fun restoreSnapshot(snapshot: CanvasSnapshot) {
        if (snapshot.workBitmap.isRecycled) return
        workBitmap = snapshot.workBitmap
        workCanvas = Canvas(workBitmap!!)
        if (nativeAvailable) nativeClearLayers()
        layers.clear()
        for (ls in snapshot.layers) {
            if (ls.bitmap.isRecycled) continue
            val cv = Canvas(ls.bitmap)
            val newNativeId = if (nativeAvailable) nativeCreateLayer(ls.bitmap.width, ls.bitmap.height) else -1
            val layer = ImageLayer(
                bitmap = ls.bitmap, canvas = cv,
                x = ls.x, y = ls.y, scale = ls.scale, rotation = ls.rotation,
                isSelected = ls.isSelected, isVisible = ls.isVisible,
                isLocked = ls.isLocked, isMasking = ls.isMasking,
                isTextLayer = ls.isTextLayer,
                textContent = ls.textContent, textFontSize = ls.textFontSize,
                textIsHorizontal = ls.textIsHorizontal,
                textFlipH = ls.textFlipH, textFlipV = ls.textFlipV,
                textIsBold = ls.textIsBold, textIsItalic = ls.textIsItalic,
                name = ls.name, nativeId = newNativeId
            )
            layers.add(layer)
        }
        selectedLayer = layers.lastOrNull { it.isSelected }
        isTransformMode = false
    }

    fun addImageLayer(bitmap: Bitmap, startX: Float = -1f, startY: Float = -1f, startScale: Float = -1f) {
        saveState("addImageLayer")
        val cw = 3508; val ch = 2480
        val fullBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val fullCv = Canvas(fullBmp)
        val sbmp = ensureSoftwareBitmap(bitmap)
        val sc = if (startScale < 0) minOf(cw * 0.6f / sbmp.width, ch * 0.6f / sbmp.height) else startScale
        val cx = if (startX < 0) (cw - sbmp.width * sc) / 2f else startX
        val cy = if (startY < 0) (ch - sbmp.height * sc) / 2f else startY
        val dst = RectF(cx, cy, cx + sbmp.width * sc, cy + sbmp.height * sc)
        fullCv.drawBitmap(sbmp, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        val layerCount = layers.size + 1
        val nativeId = if (nativeAvailable) nativeCreateLayer(cw, ch) else -1
        val layer = ImageLayer(fullBmp, fullCv, 0f, 0f, 1f, 0f, name = "Foto $layerCount", nativeId = nativeId)
        layers.forEach { it.isSelected = false }
        layers.add(layer)
        selectedLayer = layer
        isTransformMode = false
        onLayersChanged?.invoke()
        invalidate()
    }

    /** Bake the visual transform (x, y, scale, rotation) into the layer bitmap pixels,
     *  so the layer coordinate system stays aligned with canvas.
     *  After baking: x=0, y=0, scale=1, rotation=0 and the bitmap contains the
     *  transformed content at the correct canvas position. */
    private fun bakeLayerTransform(layer: ImageLayer) {
        if (layer.x == 0f && layer.y == 0f && layer.scale == 1f && layer.rotation == 0f) return
        val cw = 3508; val ch = 2480
        if (layer.bitmap.width < cw || layer.bitmap.height < ch) {
            // Small bitmap: draw into full-canvas at the visual position
            bakeLayerToFullCanvas(layer)
            return
        }
        val newBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val newCv = Canvas(newBmp)
        newCv.save()
        newCv.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        newCv.translate(layer.x, layer.y)
        newCv.rotate(layer.rotation, cw / 2f, ch / 2f)
        newCv.scale(layer.scale, layer.scale, cw / 2f, ch / 2f)
        newCv.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
        newCv.restore()
        if (nativeAvailable && layer.nativeId >= 0) nativeDeleteLayer(layer.nativeId)
        val newNativeId = if (nativeAvailable) nativeCreateLayer(cw, ch) else -1
        layer.bitmap.recycle()
        layer.bitmap = newBmp
        layer.canvas = newCv
        layer.nativeId = newNativeId
        layer.x = 0f
        layer.y = 0f
        layer.scale = 1f
        layer.rotation = 0f
        Log.d("DrawingView", "bakeLayerTransform: layer='${layer.name}' baked to full-canvas with identity transform")
    }

    private fun bakeLayerToFullCanvas(layer: ImageLayer) {
        val cw = 3508; val ch = 2480
        val newBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val newCv = Canvas(newBmp)
        newCv.save()
        newCv.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        newCv.translate(layer.x, layer.y)
        newCv.rotate(layer.rotation, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
        newCv.scale(layer.scale, layer.scale, layer.bitmap.width / 2f, layer.bitmap.height / 2f)
        newCv.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
        newCv.restore()
        if (nativeAvailable && layer.nativeId >= 0) nativeDeleteLayer(layer.nativeId)
        val newNativeId = if (nativeAvailable) nativeCreateLayer(cw, ch) else -1
        layer.bitmap.recycle()
        layer.bitmap = newBmp
        layer.canvas = newCv
        layer.nativeId = newNativeId
        layer.x = 0f
        layer.y = 0f
        layer.scale = 1f
        layer.rotation = 0f
        Log.d("DrawingView", "bakeLayerToFullCanvas: layer='${layer.name}' small bitmap baked to full-canvas")
    }

    fun ensureLayerFullCanvas() {
        val layer = selectedLayer ?: return
        if (layer.isMasking) {
            Log.d("DrawingView", "ensureLayerFullCanvas: skipping resize for masking layer ${layer.name}")
            return
        }
        if (layer.bitmap.width >= 3508 && layer.bitmap.height >= 2480) return
        bakeLayerToFullCanvas(layer)
    }

    // ─── LAYER PERSISTENCE (save/restore individual layer data) ───
    fun saveLayers(bookId: Long, spreadIndex: Int) {
        if (layers.isEmpty()) return
        val dir = java.io.File(context.filesDir, "paperleaf/books/$bookId/layers")
        dir.mkdirs()
        try {
            val meta = org.json.JSONObject()
            meta.put("count", layers.size)
            for (i in layers.indices) {
                val l = layers[i]
                val info = org.json.JSONObject()
                info.put("x", l.x.toDouble())
                info.put("y", l.y.toDouble())
                info.put("scale", l.scale.toDouble())
                info.put("rotation", l.rotation.toDouble())
                info.put("name", l.name)
                info.put("visible", l.isVisible)
                info.put("locked", l.isLocked)
                info.put("masking", l.isMasking)
                meta.put("layer_$i", info)
                val bmpFile = java.io.File(dir, "spread_${spreadIndex}_layer_$i.png")
                java.io.FileOutputStream(bmpFile).use {
                    l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            java.io.File(dir, "spread_${spreadIndex}_layers.json").writeText(meta.toString())
        } catch (_: Exception) {}
    }

    fun loadLayers(bookId: Long, spreadIndex: Int): Boolean {
        val dir = java.io.File(context.filesDir, "paperleaf/books/$bookId/layers")
        val metaFile = java.io.File(dir, "spread_${spreadIndex}_layers.json")
        if (!metaFile.exists()) return false
        try {
            if (nativeAvailable) nativeClearLayers()
            layers.clear()
            val meta = org.json.JSONObject(metaFile.readText())
            val count = meta.getInt("count")
            for (i in 0 until count) {
                val info = meta.getJSONObject("layer_$i")
                val bmpFile = java.io.File(dir, "spread_${spreadIndex}_layer_$i.png")
                if (!bmpFile.exists()) continue
                val decoded = BitmapFactory.decodeFile(bmpFile.absolutePath) ?: continue
                val bmp = decoded.copy(Bitmap.Config.ARGB_8888, true)
                decoded.recycle()
                val cv = Canvas(bmp)
                val nativeId = if (nativeAvailable) nativeCreateLayer(bmp.width, bmp.height) else -1
                val layer = ImageLayer(
                    bitmap = bmp, canvas = cv,
                    x = info.getDouble("x").toFloat(),
                    y = info.getDouble("y").toFloat(),
                    scale = info.getDouble("scale").toFloat(),
                    rotation = info.getDouble("rotation").toFloat(),
                    isSelected = false,
                    isVisible = info.getBoolean("visible"),
                    isLocked = info.getBoolean("locked"),
                    isMasking = info.getBoolean("masking"),
                    name = info.getString("name"),
                    nativeId = nativeId
                )
                layers.add(layer)
            }
            if (layers.isNotEmpty()) {
                selectedLayer = layers.last()
                layers.last().isSelected = true
            } else {
                selectedLayer = null
            }
            isTransformMode = false
            onLayersChanged?.invoke()
            invalidate()
            return true
        } catch (_: Exception) { return false }
    }

    fun flattenLayers() {
        if (layers.isEmpty()) return
        saveState("flattenLayers")
        val c = workCanvas ?: return
        c.drawColor(Color.parseColor("#FAF9F0"))
        applyTemplate()
        compositeAllLayersToCanvas(c)
        if (nativeAvailable) nativeClearLayers()
        layers.clear()
        selectedLayer = null
        isTransformMode = false
        onLayersChanged?.invoke()
        invalidate()
    }

    fun flattenLayersForSave() {
        if (layers.isEmpty()) return
        val c = workCanvas ?: return
        c.drawColor(Color.parseColor("#FAF9F0"))
        applyTemplate()
        compositeAllLayersToCanvas(c)
    }

    private fun compositeAllLayersToCanvas(c: Canvas) {
        val cw = c.width.toFloat(); val ch = c.height.toFloat()
        for (i in layers.indices) {
            val layer = layers[i]
            if (!layer.isVisible) continue
            c.save()
            c.clipRect(0f, 0f, cw, ch)
            val bw = layer.bitmap.width.toFloat()
            val bh = layer.bitmap.height.toFloat()
            val cx = bw / 2f; val cy = bh / 2f

            if (layer.isMasking) {
                val target = findMaskTarget(layer)
                if (target == null) {
                    Log.d("DrawingView", "compositeAllLayersToCanvas: no non-mask target for ${layer.name}, drawing normally")
                    c.save()
                    c.translate(layer.x, layer.y)
                    c.rotate(layer.rotation, cx, cy)
                    c.scale(layer.scale, layer.scale, cx, cy)
                    c.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                    c.restore()
                } else {
                    Log.d("DrawingView", "compositeAllLayersToCanvas: maskLayer=${layer.name} targeting ${target.name}")
                    val saveCount = c.saveLayer(null, null)

                    // Draw mask layer content with its visual transform
                    c.save()
                    c.translate(layer.x, layer.y)
                    c.rotate(layer.rotation, cx, cy)
                    c.scale(layer.scale, layer.scale, cx, cy)
                    c.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                    c.restore()

                    // DST_IN: clip by target content with target's visual transform
                    val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                    clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    val tcx = target.bitmap.width.toFloat() / 2f
                    val tcy = target.bitmap.height.toFloat() / 2f
                    c.save()
                    c.translate(target.x, target.y)
                    c.rotate(target.rotation, tcx, tcy)
                    c.scale(target.scale, target.scale, tcx, tcy)
                    c.drawBitmap(target.bitmap, 0f, 0f, clipPaint)
                    c.restore()

                    c.restoreToCount(saveCount)
                }
            } else {
                c.save()
                c.translate(layer.x, layer.y)
                c.rotate(layer.rotation, cx, cy)
                c.scale(layer.scale, layer.scale, cx, cy)
                c.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                c.restore()
            }
            c.restore()
        }
    }

    fun resetWorkCanvas() {
        val c = workCanvas ?: return
        c.drawColor(Color.parseColor("#FAF9F0"))
        applyTemplate()
        invalidate()
    }

    fun deleteLayer(layer: ImageLayer) {
        saveState("deleteLayer")
        val idx = layers.indexOf(layer)
        if (idx < 0) return
        if (nativeAvailable && layer.nativeId >= 0) nativeDeleteLayer(layer.nativeId)
        layers.removeAt(idx)
        if (layers.isEmpty()) {
            selectedLayer = null
            isTransformMode = false
        } else {
            selectedLayer = if (selectedLayer == layer) layers.last() else selectedLayer
            selectedLayer?.let {
                layers.forEach { l -> l.isSelected = false }
                it.isSelected = true
            }
        }
        onLayersChanged?.invoke()
        invalidate()
    }

    fun deleteSelectedLayer() {
        selectedLayer?.let { deleteLayer(it) }
    }

    fun duplicateLayer(layer: ImageLayer): ImageLayer? {
        saveState("duplicateLayer")
        val idx = layers.indexOf(layer)
        if (idx < 0) return null
        val bmp = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val nativeId = if (nativeAvailable) nativeDuplicateLayer(layer.nativeId) else -1
        val dup = ImageLayer(bmp, Canvas(bmp),
            layer.x + 20, layer.y + 20, layer.scale, layer.rotation,
            name = "${layer.name} (copy)", nativeId = nativeId)
        layers.forEach { it.isSelected = false }
        layers.add(idx + 1, dup)
        selectedLayer = dup
        isTransformMode = false
        onLayersChanged?.invoke()
        invalidate()
        return dup
    }

    fun renameLayer(layer: ImageLayer, newName: String) {
        saveState("renameLayer")
        layer.name = newName
        onLayersChanged?.invoke()
    }

    fun toggleLayerLock(layer: ImageLayer) {
        saveState("toggleLayerLock")
        layer.isLocked = !layer.isLocked
        onLayersChanged?.invoke()
        invalidate()
    }

    fun toggleLayerMasking(layer: ImageLayer) {
        saveState("toggleLayerMasking")
        val idx = layers.indexOf(layer)
        if (idx <= 0) {
            Log.w("DrawingView", "toggleLayerMasking: layer at index 0 cannot mask, ignored")
            return // masking only works from layer2+ (index >= 1)
        }
        layer.isMasking = !layer.isMasking
        Log.d("DrawingView", "toggleLayerMasking: layer=${layer.name} idx=$idx isMasking=${layer.isMasking}")
        onLayersChanged?.invoke()
        invalidate()
    }

    fun mergeLayerLeft(layer: ImageLayer) {
        saveState("mergeLayerLeft")
        val idx = layers.indexOf(layer)
        if (idx <= 0) return
        val target = layers[idx - 1]
        val cw = 3508; val ch = 2480

        // Buat full-canvas bitmap baru dan bake visual transform kedua layer
        val newBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val newCv = Canvas(newBmp)

        // Draw target layer with its visual transform (x,y,scale,rotation)
        newCv.save()
        newCv.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        newCv.translate(target.x, target.y)
        newCv.rotate(target.rotation, cw / 2f, ch / 2f)
        newCv.scale(target.scale, target.scale, cw / 2f, ch / 2f)
        newCv.drawBitmap(target.bitmap, 0f, 0f, layerPaint)
        newCv.restore()

        // Draw source layer with its visual transform
        newCv.save()
        newCv.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        newCv.translate(layer.x, layer.y)
        newCv.rotate(layer.rotation, cw / 2f, ch / 2f)
        newCv.scale(layer.scale, layer.scale, cw / 2f, ch / 2f)
        newCv.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
        newCv.restore()

        // Update target layer (reset transform, bitmap sudah memuat konten kedua layer)
        if (nativeAvailable && target.nativeId >= 0) nativeDeleteLayer(target.nativeId)
        val newNativeId = if (nativeAvailable) nativeCreateLayer(cw, ch) else -1
        target.bitmap = newBmp
        target.canvas = newCv
        target.nativeId = newNativeId
        target.x = 0f
        target.y = 0f
        target.scale = 1f
        target.rotation = 0f

        if (nativeAvailable && layer.nativeId >= 0) nativeDeleteLayer(layer.nativeId)
        layers.removeAt(idx)
        selectedLayer = target
        layers.forEach { it.isSelected = false }
        target.isSelected = true
        onLayersChanged?.invoke()
        invalidate()
    }

    fun moveLayer(layer: ImageLayer, newIndex: Int) {
        saveState("moveLayer")
        val oldIdx = layers.indexOf(layer)
        if (oldIdx < 0 || newIndex == oldIdx) return
        val clampedIndex: Int
        if (oldIdx == 0) {
            // Layer1 (index 0) can only move right
            clampedIndex = newIndex.coerceAtLeast(1)
        } else {
            // Other layers can move left until index 0
            clampedIndex = newIndex.coerceIn(0, layers.size - 1)
        }
        layers.removeAt(oldIdx)
        val target = clampedIndex.coerceIn(0, layers.size)
        layers.add(target, layer)
        // If masking layer is moved to index 0, disable masking
        if (target == 0 && layer.isMasking) {
            layer.isMasking = false
            Log.d("DrawingView", "moveLayer: masking disabled for ${layer.name} (moved to index 0)")
        }
        if (nativeAvailable && layer.nativeId >= 0) nativeMoveLayer(layer.nativeId, target)
        onLayersChanged?.invoke()
        invalidate()
    }

    fun selectLayer(layer: ImageLayer) {
        layers.forEach { it.isSelected = false }
        layer.isSelected = true
        selectedLayer = layer
        onLayersChanged?.invoke()
        invalidate()
    }

    fun resetZoom() {
        canvasScale = 1f; canvasTransX = 0f; canvasTransY = 0f; canvasRotation = 0f
        applyCanvasTransform()
        invalidate()
    }

    // ─── COORDINATE HELPERS ───────────────────────────────────────
    private fun viewToBitmap(viewX: Float, viewY: Float): Pair<Float, Float> {
        val pts = floatArrayOf(viewX, viewY)
        inverseMatrix.mapPoints(pts)
        return pts[0] to pts[1]
    }

    fun viewToBitmapCoords(viewX: Float, viewY: Float): Pair<Float, Float> {
        return viewToBitmap(viewX, viewY)
    }

    fun bitmapToViewCoords(bmpX: Float, bmpY: Float): Pair<Float, Float> {
        val pts = floatArrayOf(bmpX, bmpY)
        canvasMatrix.mapPoints(pts)
        return pts[0] to pts[1]
    }

    // Transform view coordinates → main canvas → layer-local space.
    // Layer bitmap is always full-canvas, so canvas coordinates = bitmap coordinates.
    private fun viewToLayer(viewX: Float, viewY: Float): Pair<Float, Float> {
        return viewToBitmap(viewX, viewY)
    }

    private fun buildStrokePaint(velocity: Float): Paint {
        val s = brushSettings; val v = velocity.coerceIn(0f, 1f)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND

            when (s.toolType) {
                BrushSettings.TOOL_ERASER -> {
                    strokeWidth = s.size * (0.6f + v * 1.0f)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                BrushSettings.TOOL_FOUNTAIN_PEN -> {
                    color = s.color
                    strokeWidth = s.size * (0.7f + v * 0.5f)
                    alpha = 245
                }
                BrushSettings.TOOL_PENCIL -> {
                    color = s.color
                    strokeWidth = s.size * 0.85f
                    alpha = 160
                }
                BrushSettings.TOOL_MARKER -> {
                    color = s.color
                    strokeWidth = s.size * 1.4f
                    alpha = 150
                    strokeCap = Paint.Cap.SQUARE
                }
                BrushSettings.TOOL_INK_PEN -> {
                    color = s.color
                    strokeWidth = s.size * (0.2f + (1f - v) * 1.3f)
                    alpha = 255
                }
                TOOL_BRUSH -> {
                    color = s.color
                    strokeWidth = s.size * 1.2f
                    alpha = 130
                }
                TOOL_ROLLER -> {
                    color = s.color
                    strokeWidth = s.size * 4.5f
                    alpha = 110
                }
                else -> {
                    color = s.color
                    strokeWidth = s.size
                    alpha = 255
                }
            }
        }
    }

    // ─── TOUCH EVENT ──────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (rawX, rawY) = viewToBitmap(event.x, event.y)
        val pc = event.pointerCount
        val action = event.actionMasked

        // Pending image placement
        if (pendingBitmap != null) {
            if (action == MotionEvent.ACTION_UP) commitPendingImage()
            return true
        }

        // Image editing mode (add image flow)
        if (isImageEditingMode) {
            handleImageEditTouch(event)
            return true
        }

        // Transform mode (layer selection / resize / pinch)
        if (isTransformMode) {
            handleTransformTouch(event, rawX, rawY)
            return true
        }

        // Tool-specific single-finger modes
        if (pc == 1) {
            if (brushSettings.toolType == TOOL_KEYBOARD) {
                handleKeyboardTouch(event, rawX, rawY)
                return true
            }
            if (brushSettings.toolType == TOOL_RULER) {
                handleShapeTouch(event, rawX, rawY); return true
            }
            if (brushSettings.toolType == TOOL_SCISSOR) {
                handleNewSelectionTouch(event, rawX, rawY)
                return true
            }
        }

        // ─── GESTURE ENGINE ────────────────────────────────────────
        handleCanvasGesture(event)
        return true
    }

    // ─── GESTURE STATE MACHINE ────────────────────────────────────
    private fun handleCanvasGesture(event: MotionEvent) {
        val pc = event.pointerCount
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                gDownTime = System.currentTimeMillis()
                for (i in 0 until pc) {
                    gFingerStartX[i] = event.getX(i)
                    gFingerStartY[i] = event.getY(i)
                }
                // 1 finger → drawing (but not keyboard tool)
                if (pc == 1 && brushSettings.toolType != TOOL_KEYBOARD) {
                    gestureState = GestureState.DRAWING
                    startDrawing(event)
                } else if (pc == 1 && brushSettings.toolType == TOOL_KEYBOARD) {
                    gestureState = GestureState.IDLE
                }
                // 2 fingers → wait for tap analysis
                else if (pc == 2) {
                    gestureState = GestureState.WAITING_TAP
                    savePinchState(event)
                }
                // 3 fingers → wait for 3-finger tap
                else if (pc == 3) {
                    gestureState = GestureState.WAITING_3_TAP
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val newPc = pc
                if (gestureState == GestureState.DRAWING) {
                    endDrawing()
                }
                // Initialize gestures for 2+ fingers
                for (i in 0 until newPc) {
                    gFingerStartX[i] = event.getX(i)
                    gFingerStartY[i] = event.getY(i)
                }
                gDownTime = System.currentTimeMillis()
                if (newPc == 2) {
                    gestureState = GestureState.WAITING_TAP
                    savePinchState(event)
                } else if (newPc == 3) {
                    gestureState = GestureState.WAITING_3_TAP
                } else {
                    gestureState = GestureState.IDLE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureState) {
                    GestureState.DRAWING -> {
                        if (pc == 1) continueDrawing(event)
                    }
                    GestureState.WAITING_TAP -> {
                        if (pc == 2) {
                            val x0 = event.getX(0); val y0 = event.getY(0)
                            val x1 = event.getX(1); val y1 = event.getY(1)
                            val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
                            if (hypot(midX - gStartMidX, midY - gStartMidY) > pinchSlop) {
                                gestureState = GestureState.PINCH
                                savePinchState(event)
                                handlePinch(event)
                            }
                        } else gestureState = GestureState.IDLE
                    }
                    GestureState.WAITING_3_TAP -> {
                        if (pc == 3) checkThreeFingerTap(event)
                        else gestureState = GestureState.IDLE
                    }
                    GestureState.PINCH -> {
                        if (pc == 2) handlePinch(event)
                        else gestureState = GestureState.IDLE
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                gestureState = GestureState.IDLE
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (gestureState == GestureState.WAITING_TAP && pc == 2) {
                    checkDoubleTap(2)
                } else if (gestureState == GestureState.WAITING_3_TAP && pc == 3) {
                    checkDoubleTap(3)
                }
                gestureState = GestureState.IDLE
                if (gestureState == GestureState.DRAWING) {
                    endDrawing()
                }
                invalidate()
            }
        }
    }

    // ─── ACTIVE DRAWING CANVAS ────────────────────────────────────
    private val activeCanvas: Canvas?
        get() = if (selectedLayer?.isLocked == true) null else selectedLayer?.canvas

    // ─── DRAWING ──────────────────────────────────────────────────
    private fun startDrawing(event: MotionEvent) {
        val sel = selectedLayer
        if (sel != null && !sel.isMasking) {
            ensureLayerFullCanvas()
        }
        saveState("startDrawing")
        if (sel != null && !sel.isMasking) {
            bakeLayerTransform(sel)
        }
        val (rawX, rawY) = viewToLayer(event.x, event.y)
        if (activeCanvas == null) return
        if (selectedLayer?.isLocked == true) return
        if (selectedLayer?.isTextLayer == true && brushSettings.toolType != TOOL_KEYBOARD) {
            onTextLayerWarning?.invoke()
            gestureState = GestureState.IDLE
            return
        }
        gestureState = GestureState.DRAWING
        strokePoints.clear()
        smoothX = rawX; smoothY = rawY
        strokeInitialized = false
        lastX = rawX; lastY = rawY; lastTime = System.currentTimeMillis()
        lastVel = 0f
        strokePath.reset()
        strokePath.moveTo(rawX, rawY)
        strokeLastMidX = rawX; strokeLastMidY = rawY
        invalidate()
    }

    private fun continueDrawing(event: MotionEvent) {
        if (selectedLayer?.isLocked == true) return
        val target = activeCanvas ?: return

        // 1. Kumpulkan titik historis dan titik saat ini
        val rawPoints = mutableListOf<Pair<Float, Float>>()
        val hs = event.historySize
        for (i in 0 until hs) {
            val (hx, hy) = viewToLayer(event.getHistoricalX(i), event.getHistoricalY(i))
            rawPoints.add(hx to hy)
        }
        val (ex, ey) = viewToLayer(event.x, event.y)
        rawPoints.add(ex to ey)

        // 2. Smoothing (Exponential)
        val stabilizedPoints = mutableListOf<Pair<Float, Float>>()
        for ((x, y) in rawPoints) {
            val sx: Float; val sy: Float
            if (!strokeInitialized) {
                sx = x; sy = y
                strokeInitialized = true
            } else {
                sx = smoothX + (x - smoothX) * 0.4f
                sy = smoothY + (y - smoothY) * 0.4f
            }
            smoothX = sx; smoothY = sy
            stabilizedPoints.add(sx to sy)
        }

        // 3. Filter titik yang terlalu rapat
        val addedPoints = mutableListOf<PointF>()
        for ((x, y) in stabilizedPoints) {
            if (strokePoints.isEmpty()) {
                val p = PointF(x, y)
                strokePoints.add(p)
                addedPoints.add(p)
            } else {
                val last = strokePoints.last()
                if (hypot(x - last.x, y - last.y) >= 1.5f) {
                    val p = PointF(x, y)
                    strokePoints.add(p)
                    addedPoints.add(p)
                }
            }
        }

        if (addedPoints.size < 2) {
            invalidate()
            return
        }

        val startIdx = strokePoints.size - addedPoints.size

        // 4. Gambar segmen demi segmen
        for (seg in 0 until addedPoints.size - 1) {
            val globalIdx = startIdx + seg
            val p1 = strokePoints[globalIdx]
            val p2 = strokePoints[globalIdx + 1]

            val p0 = strokePoints.getOrNull(globalIdx - 1) ?: p1
            val p3 = strokePoints.getOrNull(globalIdx + 2) ?: p2

            val segLen = hypot(p2.x - p1.x, p2.y - p1.y)
            val vel = (segLen / 15f).coerceIn(0f, 1f)

            val segPaint = buildStrokePaint(vel)

            // --- START TAPERING ---
            if (globalIdx < 15) {
                val taperFactor = (globalIdx + 1) / 15f
                segPaint.strokeWidth *= taperFactor
                segPaint.alpha = (segPaint.alpha * taperFactor).toInt().coerceIn(10, 255)
            }

            val segPath = Path()
            segPath.moveTo(p1.x, p1.y)

            // Spacing fixed kecil agar garis menyatu sempurna
            val spacing = 2f
            val steps = maxOf(1, (segLen / spacing).toInt())

            if (steps <= 1) {
                segPath.lineTo(p2.x, p2.y)
            } else {
                for (s in 1..steps) {
                    val t = s.toFloat() / steps
                    val q = catmullRomPoint(p0, p1, p2, p3, t)
                    segPath.lineTo(q.x, q.y)
                }
            }

            target.drawPath(segPath, segPaint)

            if (brushSettings.toolType == BrushSettings.TOOL_PENCIL) {
                val grainPaint = Paint(segPaint).apply {
                    strokeWidth = segPaint.strokeWidth * 0.4f
                    alpha = (segPaint.alpha * 0.3f).toInt()
                }
                target.drawPath(segPath, grainPaint)
            }
        }

        lastX = smoothX; lastY = smoothY
        invalidate()
    }

    private fun endDrawing() {
        val target = activeCanvas

        if (target != null && strokePoints.size > 2) {
            val lastPoint = strokePoints.last()
            val secondLast = strokePoints[strokePoints.size - 2]

            val dx = lastPoint.x - secondLast.x
            val dy = lastPoint.y - secondLast.y
            val dist = hypot(dx, dy)

            if (dist > 0.1f) {
                val endPaint = buildStrokePaint(0.5f)
                endPaint.style = Paint.Style.FILL

                val steps = 5
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val radius = (endPaint.strokeWidth / 2f) * (1f - t)
                    val alpha = (endPaint.alpha * (1f - t)).toInt().coerceIn(0, 255)

                    val px = secondLast.x + (dx * t)
                    val py = secondLast.y + (dy * t)

                    endPaint.alpha = alpha
                    target.drawCircle(px, py, radius.coerceAtLeast(0.5f), endPaint)
                }
            }
        }

        strokePath.reset()
        strokePoints.clear()
        strokeInitialized = false
    }

    private fun catmullRomPoint(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        val t2 = t * t
        val t3 = t2 * t
        return PointF(
            0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t + (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 + (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3),
            0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t + (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 + (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3)
        )
    }

    // ─── TWO-FINGER PINCH (ZOOM + PAN + ROTATE) ───────────────────
    private fun savePinchState(event: MotionEvent) {
        // ensure matrix is current
        applyCanvasTransform()
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        gStartDist = hypot(x1 - x0, y1 - y0)
        gStartAngle = Math.toDegrees(
            atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        ).toFloat()
        gStartMidX = (x0 + x1) / 2f; gStartMidY = (y0 + y1) / 2f
        gStartScale = canvasScale
        gStartRot = canvasRotation
        gStartTransX = canvasTransX; gStartTransY = canvasTransY

        // compute focal point in bitmap coordinates so scaling keeps focus anchored
        val (bmpFx, bmpFy) = viewToBitmap(gStartMidX, gStartMidY)
        gFocusBitmapX = bmpFx; gFocusBitmapY = bmpFy
    }

    private fun handlePinch(event: MotionEvent) {
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        val dist = hypot(x1 - x0, y1 - y0)
        val angle = Math.toDegrees(
            atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        ).toFloat()
        val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
        if (gStartDist == 0f) return

        // compute new scale around the initial gesture scale
        val newScale = (gStartScale * (dist / gStartDist)).coerceIn(0.1f, 10f)
        canvasScale = newScale

        // maintain focus point: screen_mid = scale * (bitmap_focus + trans)
        // => trans = screen_mid / scale - bitmap_focus
        canvasTransX = midX / canvasScale - gFocusBitmapX
        canvasTransY = midY / canvasScale - gFocusBitmapY

        canvasRotation = gStartRot + (angle - gStartAngle)
        // Snapping real-time ke kelipatan 5°
        canvasRotation = (Math.round(canvasRotation / 5f) * 5f)

        // clamp panning to allow full canvas exploration
        clampTranslation()
        applyCanvasTransform()
        invalidate()
    }

    // ─── DOUBLE TAP (UNDO / REDO) ─────────────────────────────────
    private fun checkDoubleTap(fingerCount: Int) {
        val now = System.currentTimeMillis()

        if (gLastTapUpTime > 0 &&
            now - gLastTapUpTime < doubleTapTime &&
            gLastTapFingerCount == fingerCount) {

            // Second tap confirmed — check finger positions match
            var allClose = true
            for (i in 0 until fingerCount.coerceAtMost(gLastTapDownXs.size)) {
                val dx = abs(gFingerStartX[i] - gLastTapDownXs[i])
                val dy = abs(gFingerStartY[i] - gLastTapDownYs[i])
                if (hypot(dx, dy) > touchSlop) { allClose = false; break }
            }
            if (allClose) {
                if (fingerCount == 2) undo()
                else if (fingerCount == 3) redo()
                // Reset
                gLastTapUpTime = 0
                gLastTapFingerCount = 0
                return
            }
        }

        // Record this as first tap
        gLastTapUpTime = now
        gLastTapFingerCount = fingerCount
        gLastTapDownXs = FloatArray(fingerCount) { gFingerStartX[it] }
        gLastTapDownYs = FloatArray(fingerCount) { gFingerStartY[it] }
    }

    private fun checkThreeFingerTap(event: MotionEvent) {
        // Check if any finger moved beyond slop → cancel tap
        for (i in 0 until 3) {
            val dx = abs(event.getX(i) - gFingerStartX[i])
            val dy = abs(event.getY(i) - gFingerStartY[i])
            if (hypot(dx, dy) > touchSlop) {
                gestureState = GestureState.IDLE
                return
            }
        }
        // Still waiting — 3 fingers haven't moved
    }

    // ─── KEYBOARD TOOL TOUCH ──────────────────────────────────────
    var onKeyboardTap: ((Float, Float) -> Unit)? = null
    var onTextLayerTap: ((ImageLayer) -> Unit)? = null

    private fun findTextLayerAt(x: Float, y: Float): ImageLayer? {
        return layers.lastOrNull { layer ->
            layer.isTextLayer && layer.isVisible && layer.textContent?.isNotEmpty() == true &&
                x >= layer.x && x <= layer.x + layer.bitmap.width * layer.scale &&
                y >= layer.y && y <= layer.y + layer.bitmap.height * layer.scale
        }
    }

    private var textDragLayer: ImageLayer? = null
    private var textDragLayerStartX = 0f
    private var textDragLayerStartY = 0f

    private fun handleKeyboardTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                textDragStartX = x
                textDragStartY = y
                isTextDragging = false
                textDragRect = null
                // Cek apakah touch dimulai di atas text layer
                textDragLayer = findTextLayerAt(x, y)
                textDragLayer?.let {
                    textDragLayerStartX = it.x
                    textDragLayerStartY = it.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = hypot(x - textDragStartX, y - textDragStartY)
                if (dist > touchSlop * 2) {
                    isTextDragging = true
                    // Jika touch di atas text layer, geser langsung
                    if (textDragLayer != null) {
                        val layer = textDragLayer!!
                        layer.x = textDragLayerStartX + (x - textDragStartX)
                        layer.y = textDragLayerStartY + (y - textDragStartY)
                        textDragRect = null
                        invalidate()
                    } else {
                        // Tampilkan drag rectangle
                        textDragRect = RectF(
                            minOf(textDragStartX, x), minOf(textDragStartY, y),
                            maxOf(textDragStartX, x), maxOf(textDragStartY, y)
                        )
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                textDragRect = null
                if (isTextDragging) {
                    val draggedLayer = textDragLayer
                    textDragLayer = null
                    isTextDragging = false
                    if (draggedLayer != null) {
                        // Selesai menggeser text layer → tampilkan popup
                        onTextLayerTap?.invoke(draggedLayer)
                    } else {
                        // Drag rect — cek apakah mengenai text layer
                        val dragRect = RectF(
                            minOf(textDragStartX, x), minOf(textDragStartY, y),
                            maxOf(textDragStartX, x), maxOf(textDragStartY, y)
                        )
                        val hitLayer = layers.lastOrNull { layer ->
                            layer.isTextLayer && layer.isVisible && layer.textContent?.isNotEmpty() == true &&
                                RectF.intersects(dragRect, RectF(
                                    layer.x, layer.y,
                                    layer.x + layer.bitmap.width * layer.scale,
                                    layer.y + layer.bitmap.height * layer.scale
                                ))
                        }
                        if (hitLayer != null) {
                            onTextLayerTap?.invoke(hitLayer)
                        } else {
                            onKeyboardTap?.invoke(x, y)
                        }
                    }
                    invalidate()
                } else {
                    // Tap (tanpa drag)
                    textDragLayer = null
                    val tappedLayer = findTextLayerAt(x, y)
                    if (tappedLayer != null) {
                        onTextLayerTap?.invoke(tappedLayer)
                    } else {
                        onKeyboardTap?.invoke(x, y)
                    }
                }
            }
        }
    }

    // ─── SHAPE & RULER TOUCH ──────────────────────────────────────
    private fun handleShapeTouch(event: MotionEvent, bx: Float, by: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedLayer == null) return
                saveState("handleShapeTouch")
                shapeStartX = bx; shapeStartY = by
                shapeEndX = bx;   shapeEndY = by
                isDrawingShape = true
            }
            MotionEvent.ACTION_MOVE -> {
                shapeEndX = bx; shapeEndY = by; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val layer = selectedLayer ?: return
                isDrawingShape = false
                val (lx1, ly1) = bitmapToLayer(shapeStartX, shapeStartY, layer)
                val (lx2, ly2) = bitmapToLayer(bx, by, layer)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = brushSettings.size
                    color = brushSettings.color
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                }
                when (activeShape) {
                    ShapeType.LINE -> layer.canvas.drawLine(lx1, ly1, lx2, ly2, p)
                    ShapeType.RECT -> layer.canvas.drawRect(
                        minOf(lx1, lx2), minOf(ly1, ly2),
                        maxOf(lx1, lx2), maxOf(ly1, ly2), p)
                    ShapeType.CIRCLE -> {
                        val r = hypot(lx2 - lx1, ly2 - ly1)
                        layer.canvas.drawCircle(lx1, ly1, r, p)
                    }
                    else -> {}
                }
                invalidate()
            }
        }
    }

    private fun bitmapToLayer(bx: Float, by: Float, @Suppress("UNUSED_PARAMETER") layer: ImageLayer): Pair<Float, Float> {
        return bx to by
    }

    // ─── SELECTION TOUCH ──────────────────────────────────────────
    private fun isPointInSelection(x: Float, y: Float): Boolean {
        if (!hasSelection || selectionPath.isEmpty) return false
        val bounds = RectF()
        selectionPath.computeBounds(bounds, true)
        if (!bounds.contains(x, y)) return false
        val r = Region()
        val clip = Rect()
        bounds.roundOut(clip)
        r.setPath(selectionPath, Region(clip))
        return r.contains(x.toInt(), y.toInt())
    }

    private fun handleNewSelectionTouch(event: MotionEvent, x: Float, y: Float) {
        if (hasSelection && event.actionMasked == MotionEvent.ACTION_DOWN && isPointInSelection(x, y)) {
            handleSelectionDrag(event, x, y)
            return
        }
        when (scissorMode) {
            SCISSOR_LASSO -> handleLassoTouch(event, x, y)
            SCISSOR_SMART_CUT -> handleSmartCutTouch(event, x, y)
            SCISSOR_MAGIC_WAND -> if (event.actionMasked == MotionEvent.ACTION_UP) handleMagicWandTap(x, y)
            SCISSOR_INVERSE -> invertSelection()
            SCISSOR_CUT -> cutSelection()
            SCISSOR_COPY -> copySelection()
            SCISSOR_PASTE -> pasteSelection()
            SCISSOR_DELETE -> deleteSelection()
            SCISSOR_CANCEL -> clearSelection()
        }
    }

    private val lassoPoints = mutableListOf<Pair<Float, Float>>()
    private var lassoJob: Job? = null

    private fun handleLassoTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoPoints.clear()
                lassoPoints.add(x to y)
                selectionPath.reset()
                selectionPath.moveTo(x, y)
                isSelecting = true
                hasSelection = false
            }
            MotionEvent.ACTION_MOVE -> {
                lassoPoints.add(x to y)
                selectionPath.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                lassoPoints.add(x to y)
                selectionPath.close()
                isSelecting = false
                hasSelection = true
                onSelectionChanged?.invoke()
                invalidate()

                lassoJob?.cancel()
                lassoJob = scope.launch {
                    val bmp = workBitmap ?: return@launch
                    val smoothed = SelectionProcessor.smoothLassoPath(
                        lassoPoints, bmp.width, bmp.height
                    )
                    withContext(Dispatchers.Main) {
                        if (smoothed != null) {
                            selectionPath.set(smoothed)
                        }
                        invalidate()
                    }
                }
            }
        }
    }

    private var smartCutJob: Job? = null
    private var smartCutRect: Rect? = null

    private fun handleSmartCutTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionStartX = x
                selectionStartY = y
                selectionPath.reset()
                isSelecting = true
                hasSelection = false
            }
            MotionEvent.ACTION_MOVE -> {
                selectionPath.reset()
                selectionPath.addRect(
                    minOf(selectionStartX, x), minOf(selectionStartY, y),
                    maxOf(selectionStartX, x), maxOf(selectionStartY, y),
                    Path.Direction.CW
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isSelecting = false
                val bx = minOf(selectionStartX, x)
                val by = minOf(selectionStartY, y)
                val bw = abs(x - selectionStartX)
                val bh = abs(y - selectionStartY)
                if (bw < 10f || bh < 10f) return

                smartCutRect = Rect(bx.toInt(), by.toInt(), (bx + bw).toInt(), (by + bh).toInt())
                val bmp = workBitmap ?: return

                smartCutJob?.cancel()
                smartCutJob = scope.launch {
                    val ocvPath = SelectionProcessor.grabCutSelect(
                        bmp, smartCutRect!!, 1f, 1f
                    )
                    withContext(Dispatchers.Main) {
                        if (ocvPath != null) {
                            selectionPath.set(ocvPath)
                        } else {
                            selectionPath.reset()
                            selectionPath.addRect(
                                bx, by, bx + bw, by + bh,
                                Path.Direction.CW
                            )
                        }
                        hasSelection = true
                        onSelectionChanged?.invoke()
                        invalidate()
                    }
                }
            }
        }
    }

    private fun handleMagicWandTap(x: Float, y: Float) {
        magicWandJob?.cancel()
        magicWandJob = scope.launch {
            val bmp = workBitmap ?: return@launch
            val px = x.toInt().coerceIn(0, bmp.width - 1)
            val py = y.toInt().coerceIn(0, bmp.height - 1)
            val tolerance = 40

            val ocvPath = SelectionProcessor.floodFillSelect(bmp, px, py, tolerance)
            if (ocvPath != null) {
                selectionPath.set(ocvPath)
            } else {
                selectionPath.reset()
                selectionPath.addCircle(x, y, 50f, Path.Direction.CW)
            }

            hasSelection = true
            isSelecting = false
            onSelectionChanged?.invoke()
            invalidate()
        }
    }

    // ─── SELECTION ACTIONS ────────────────────────────────────────
    private fun getSelectedBitmapFromLayer(): Bitmap? {
        if (!hasSelection) return null
        val layer = selectedLayer ?: return null
        if (layer.isLocked || layer.isTextLayer) return null
        if (!layer.isMasking) {
            ensureLayerFullCanvas()
            if (layer.x != 0f || layer.y != 0f || layer.scale != 1f || layer.rotation != 0f) {
                bakeLayerTransform(layer)
            }
        }
        val bmp = layer.bitmap
        val bounds = RectF()
        selectionPath.computeBounds(bounds, true)
        val l = bounds.left.toInt().coerceAtLeast(0)
        val t = bounds.top.toInt().coerceAtLeast(0)
        val r = bounds.right.toInt().coerceAtMost(bmp.width)
        val b = bounds.bottom.toInt().coerceAtMost(bmp.height)
        val bw = (r - l).coerceAtLeast(1)
        val bh = (b - t).coerceAtLeast(1)

        val result = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        val localPath = Path(selectionPath)
        localPath.offset(-l.toFloat(), -t.toFloat())
        c.clipPath(localPath)
        c.drawBitmap(bmp, -l.toFloat(), -t.toFloat(), null)
        return result
    }

    fun cutSelection() {
        val layer = selectedLayer ?: return
        if (layer.isLocked || layer.isTextLayer) return
        val selBmp = getSelectedBitmapFromLayer() ?: return
        saveState("cutSelection")
        ClipboardManager.clipboardBitmap = selBmp
        clearSelectedAreaFromLayer()
        val bounds = RectF()
        selectionPath.computeBounds(bounds, true)
        val nativeId = if (nativeAvailable) nativeCreateLayer(selBmp.width, selBmp.height) else -1
        val cutLayer = ImageLayer(
            bitmap = selBmp,
            canvas = Canvas(selBmp),
            x = bounds.left,
            y = bounds.top,
            scale = 1f,
            name = "Potongan",
            nativeId = nativeId
        )
        layers.forEach { it.isSelected = false }
        layers.add(cutLayer)
        selectedLayer = cutLayer
        isTransformMode = true
        hasSelection = false
        isSelecting = false
        selectionPath.reset()
        onSelectionChanged?.invoke()
        onLayersChanged?.invoke()
        invalidate()
        scissorMode = SCISSOR_NONE
    }

    fun copySelection() {
        val selBmp = getSelectedBitmapFromLayer() ?: return
        ClipboardManager.clipboardBitmap = selBmp
        invalidate()
    }

    fun pasteSelection() {
        val bmp = ClipboardManager.clipboardBitmap ?: return
        saveState("pasteSelection")
        val cw = 3508f; val ch = 2480f
        val sc = minOf(cw * 0.5f / bmp.width, ch * 0.5f / bmp.height, 1f)
        val px = (cw - bmp.width * sc) / 2f
        val py = (ch - bmp.height * sc) / 2f
        val nativeId = if (nativeAvailable) nativeCreateLayer(bmp.width, bmp.height) else -1
        val layer = ImageLayer(
            bitmap = bmp,
            canvas = Canvas(bmp),
            x = px,
            y = py,
            scale = sc,
            name = "Tempelan",
            nativeId = nativeId
        )
        layers.forEach { it.isSelected = false }
        layers.add(layer)
        selectedLayer = layer
        isTransformMode = true
        hasSelection = false
        isSelecting = false
        selectionPath.reset()
        onSelectionChanged?.invoke()
        onLayersChanged?.invoke()
        invalidate()
        scissorMode = SCISSOR_NONE
    }

    fun deleteSelection() {
        if (!hasSelection) return
        val layer = selectedLayer ?: return
        if (layer.isLocked || layer.isTextLayer) return
        saveState("deleteSelection")
        clearSelectedAreaFromLayer()
        hasSelection = false
        isSelecting = false
        selectionPath.reset()
        onSelectionChanged?.invoke()
        invalidate()
        scissorMode = SCISSOR_NONE
    }

    fun invertSelection() {
        if (!hasSelection) return
        val bmp = workBitmap ?: return
        val fullPath = Path()
        fullPath.addRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Path.Direction.CW)
        fullPath.op(selectionPath, Path.Op.DIFFERENCE)
        selectionPath.set(fullPath)
        invalidate()
    }

    fun clearSelection() {
        hasSelection = false
        isSelecting = false
        selectionPath.reset()
        onSelectionChanged?.invoke()
        invalidate()
        scissorMode = SCISSOR_NONE
    }

    private fun clearSelectedAreaFromLayer() {
        val layer = selectedLayer ?: return
        if (layer.isLocked || layer.isTextLayer) return
        if (!layer.isMasking) {
            ensureLayerFullCanvas()
            if (layer.x != 0f || layer.y != 0f || layer.scale != 1f || layer.rotation != 0f) {
                bakeLayerTransform(layer)
            }
        }
        val c = layer.canvas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.save()
            c.clipOutPath(selectionPath)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.restore()
        } else {
            c.save()
            @Suppress("DEPRECATION")
            c.clipPath(selectionPath, Region.Op.DIFFERENCE)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.restore()
        }
    }

    // ─── SELECTION DRAG (MOVE) ────────────────────────────────────
    private var selDragStartX = 0f
    private var selDragStartY = 0f
    private var selDragOrigX = 0f
    private var selDragOrigY = 0f
    private var selDragLayer: ImageLayer? = null

    private fun handleSelectionDrag(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selDragStartX = x; selDragStartY = y
                val selBmp = getSelectedBitmapFromLayer() ?: return
                saveState("handleSelectionDrag")
                clearSelectedAreaFromLayer()
                val bounds = RectF()
                selectionPath.computeBounds(bounds, true)
                selDragOrigX = bounds.left; selDragOrigY = bounds.top
                val nativeId = if (nativeAvailable) nativeCreateLayer(selBmp.width, selBmp.height) else -1
                selDragLayer = ImageLayer(
                    bitmap = selBmp,
                    canvas = Canvas(selBmp),
                    x = bounds.left,
                    y = bounds.top,
                    scale = 1f,
                    name = "Pindahan",
                    nativeId = nativeId
                )
                layers.forEach { it.isSelected = false }
                layers.add(selDragLayer!!)
                selectedLayer = selDragLayer
                isTransformMode = true
                hasSelection = false
                selectionPath.reset()
                onLayersChanged?.invoke()
                invalidate()
            }
        }
    }

    // ─── LAYER TRANSFORM TOUCH ────────────────────────────────────
    private fun logLayerState(layer: ImageLayer, tag: String) {
        val bmp = workBitmap
        Log.d("DrawingView", "$tag — layer='${layer.name}' x=${"%.1f".format(layer.x)} y=${"%.1f".format(layer.y)} scale=${"%.3f".format(layer.scale)} rotation=${"%.1f".format(layer.rotation)} bitmap=${layer.bitmap.width}x${layer.bitmap.height} canvas=${bmp?.width ?: 3508}x${bmp?.height ?: 2480}")
    }

    private fun handleTransformTouch(event: MotionEvent, rawX: Float, rawY: Float) {
        when (event.pointerCount) {
            1 -> handleTransformSingleFinger(event, rawX, rawY)
            2 -> handleTransformTwoFinger(event)
        }
    }

    private fun handleTransformSingleFinger(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                saveState("transformStart")
                activeCorner = Corner.NONE
                val sel = selectedLayer ?: return
                logLayerState(sel, "transform DOWN before")
                val hr = 28f / canvasScale
                val dst = RectF(sel.x, sel.y,
                    sel.x + sel.bitmap.width * sel.scale,
                    sel.y + sel.bitmap.height * sel.scale)
                val corners = listOf(
                    Corner.TL to (dst.left to dst.top),
                    Corner.TR to (dst.right to dst.top),
                    Corner.BL to (dst.left to dst.bottom),
                    Corner.BR to (dst.right to dst.bottom)
                )
                for ((corner, pos) in corners) {
                    if (hypot(x - pos.first, y - pos.second) <= hr + 12f) {
                        activeCorner = corner
                        cornerDragStart = PointF(x, y)
                        cornerInitDst = RectF(dst)
                        cornerInitScale = sel.scale
                        cornerLayerX = sel.x
                        cornerLayerY = sel.y
                        return
                    }
                }
                if (dst.contains(x, y)) {
                    dragStartX  = x; dragStartY  = y
                    layerStartX = sel.x; layerStartY = sel.y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner != Corner.NONE) {
                    val layer = selectedLayer ?: return
                    val dx = x - cornerDragStart.x
                    val dy = y - cornerDragStart.y
                    val bw = layer.bitmap.width.toFloat()
                    val bh = layer.bitmap.height.toFloat()
                    var newW = cornerInitDst.width()
                    var newH = cornerInitDst.height()
                    when (activeCorner) {
                        Corner.TL -> { newW -= dx; newH -= dy }
                        Corner.TR -> { newW += dx; newH -= dy }
                        Corner.BL -> { newW -= dx; newH += dy }
                        Corner.BR -> { newW += dx; newH += dy }
                        else -> {}
                    }
                    val newScale = minOf(
                        (newW / bw).coerceIn(0.05f, 20f),
                        (newH / bh).coerceIn(0.05f, 20f)
                    )
                    layer.scale = newScale
                    when (activeCorner) {
                        Corner.TL -> { layer.x = cornerInitDst.right - bw * newScale; layer.y = cornerInitDst.bottom - bh * newScale }
                        Corner.TR -> { layer.x = cornerInitDst.left; layer.y = cornerInitDst.bottom - bh * newScale }
                        Corner.BL -> { layer.x = cornerInitDst.right - bw * newScale; layer.y = cornerInitDst.top }
                        Corner.BR -> { layer.x = cornerInitDst.left; layer.y = cornerInitDst.top }
                        else -> {}
                    }
                    clampLayerToCanvas(layer)
                    if (nativeAvailable && layer.nativeId >= 0) {
                        nativeSetLayerTransform(layer.nativeId, layer.x, layer.y, layer.scale, layer.rotation)
                    }
                    logLayerState(layer, "transform corner MOVE after")
                    invalidate()
                    return
                }
                val layer = selectedLayer ?: return
                layer.x = layerStartX + (x - dragStartX)
                layer.y = layerStartY + (y - dragStartY)
                clampLayerToCanvas(layer)
                if (nativeAvailable && layer.nativeId >= 0) {
                    nativeSetLayerTransform(layer.nativeId, layer.x, layer.y, layer.scale, layer.rotation)
                }
                logLayerState(layer, "transform drag MOVE after")
                invalidate()
            }
        }
    }

    private fun handleTransformTwoFinger(event: MotionEvent) {
        val layer = selectedLayer ?: return
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        val dist  = hypot(x1 - x0, y1 - y0)
        val angle = Math.toDegrees(Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                saveState("pinchTransformStart")
                initDist  = dist;  initScale = layer.scale
                initAngle = angle; initRot   = layer.rotation
                lastMidX  = (x0 + x1) / 2f; lastMidY = (y0 + y1) / 2f
                logLayerState(layer, "transform pinch DOWN before")
            }
            MotionEvent.ACTION_MOVE -> {
                if (initDist == 0f) return
                layer.scale    = (initScale * (dist / initDist)).coerceIn(0.1f, 10f)
                var newRotation = initRot + (angle - initAngle)
                newRotation = (Math.round(newRotation / 5f) * 5f).coerceIn(-360f, 360f)
                layer.rotation = newRotation
                val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
                layer.x += midX - lastMidX; layer.y += midY - lastMidY
                clampLayerToCanvas(layer)
                lastMidX = midX; lastMidY = midY
                if (nativeAvailable && layer.nativeId >= 0) {
                    nativeSetLayerTransform(layer.nativeId, layer.x, layer.y, layer.scale, layer.rotation)
                }
                logLayerState(layer, "transform pinch MOVE after")
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                initDist = 0f
                if (nativeAvailable && layer.nativeId >= 0) {
                    nativeSetLayerTransform(layer.nativeId, layer.x, layer.y, layer.scale, layer.rotation)
                }
                logLayerState(layer, "transform pinch UP after")
            }
        }
    }

    // Clamp canvasTransX/Y so the scaled bitmap can be fully panned into view
    private fun clampTranslation() {
        val bmp = workBitmap ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val maxPan = 0.2f

        if (canvasRotation == 0f) {
            canvasTransX = canvasTransX.coerceIn(
                -bmp.width * (1f + maxPan),
                (vw / canvasScale) * (1f + maxPan)
            )
            canvasTransY = canvasTransY.coerceIn(
                -bmp.height * (1f + maxPan),
                (vh / canvasScale) * (1f + maxPan)
            )
        } else {
            val angle = Math.toRadians(canvasRotation.toDouble())
            val cx = bmp.width / 2f; val cy = bmp.height / 2f
            val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()

            // Compute rotated bounding box relative to center
            val corners = floatArrayOf(
                -cx, -cy,  bmp.width - cx, -cy,
                bmp.width - cx, bmp.height - cy,  -cx, bmp.height - cy
            )
            var rxMin = Float.MAX_VALUE; var rxMax = Float.MIN_VALUE
            var ryMin = Float.MAX_VALUE; var ryMax = Float.MIN_VALUE
            for (i in 0 until 4) {
                val bx = corners[i * 2]; val by = corners[i * 2 + 1]
                val rx = bx * cosA - by * sinA
                val ry = bx * sinA + by * cosA
                rxMin = minOf(rxMin, rx); rxMax = maxOf(rxMax, rx)
                ryMin = minOf(ryMin, ry); ryMax = maxOf(ryMax, ry)
            }
            // transX: right edge constraint → -(cx + rxMax), left edge → vw/scale - (cx + rxMin)
            val panW = rxMax - rxMin
            val panH = ryMax - ryMin
            canvasTransX = canvasTransX.coerceIn(
                -cx - rxMax - panW * maxPan,
                vw / canvasScale - cx - rxMin + panW * maxPan
            )
            canvasTransY = canvasTransY.coerceIn(
                -cy - ryMax - panH * maxPan,
                vh / canvasScale - cy - ryMin + panH * maxPan
            )
        }
    }

    private fun clampLayerToCanvas(layer: ImageLayer) {
        val cw = 3508f; val ch = 2480f
        val lw = layer.bitmap.width * layer.scale
        val lh = layer.bitmap.height * layer.scale
        layer.x = layer.x.coerceIn(-lw * 0.5f, cw - lw * 0.5f)
        layer.y = layer.y.coerceIn(-lh * 0.5f, ch - lh * 0.5f)
    }

    // Native hooks
    private external fun nativeInit()
    private external fun nativeRenderStroke(points: FloatArray, color: Int, strokeWidth: Float): Boolean
    private external fun nativeCreateLayer(width: Int, height: Int): Int
    private external fun nativeDeleteLayer(layerId: Int)
    private external fun nativeDuplicateLayer(layerId: Int): Int
    private external fun nativeMoveLayer(layerId: Int, newIndex: Int)
    private external fun nativeSetLayerTransform(layerId: Int, x: Float, y: Float, scale: Float, rotation: Float)
    private external fun nativeSetLayerVisibility(layerId: Int, visible: Boolean)
    private external fun nativeCompositeLayers(targetW: Int, targetH: Int): Int
    private external fun nativeFreeResult(handle: Int)
    private external fun nativeGetLayerCount(): Int
    private external fun nativeClearLayers()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        antsAnimator?.cancel()
        clearUndoRedo()
        if (nativeAvailable) nativeClearLayers()
    }

}
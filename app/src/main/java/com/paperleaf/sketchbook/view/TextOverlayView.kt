package com.paperleaf.sketchbook.view

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.*

class TextOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private val DONE_COLOR = Color.parseColor("#4CAF50")
        private val CANCEL_COLOR = Color.parseColor("#EF5350")
    }

    // ─── TEXT PROPERTIES ──────────────────────────────────────
    var textColor: Int = Color.BLACK
        set(v) { field = v; updateEditTextStyle() }
    var textFontSize: Float = 48f
        set(v) { field = v; updateEditTextStyle() }
    var textAlignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        set(v) { field = v; updateEditTextStyle() }
    var isBold: Boolean = false
        set(v) { field = v; updateEditTextStyle() }
    var isItalic: Boolean = false
        set(v) { field = v; updateEditTextStyle() }
    var fontPath: String = ""
        set(v) { field = v; updateEditTextStyle() }

    var onCommit: ((TextOverlayView) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onTextChanged: ((String) -> Unit)? = null
    var showActionButtons: Boolean = true
        set(v) { field = v; if (doneBtn.parent != null) applyActionButtonState() }
    private fun applyActionButtonState() {
        val v = showActionButtons
        doneBtn.visibility = if (v) View.VISIBLE else View.GONE
        cancelBtn.visibility = if (v) View.VISIBLE else View.GONE
        val pad = (handleRadius * 1.5f).toInt()
        val top = pad + if (v) (handleRadius * 1.5f + 8 * density).toInt() else 0
        editText.setPadding(pad, top, pad, pad)
    }
    var isEditing: Boolean = false
        set(v) { field = v; borderPaint.color = if (v) Color.parseColor("#FF9800") else Color.parseColor("#2196F3"); invalidate() }
    var showHandles: Boolean = false
        set(v) { field = v; invalidate() }

    // ─── INTERNAL VIEWS ───────────────────────────────────────
    private val editText: EditText
    private val doneBtn: ImageView
    private val cancelBtn: ImageView
    private val btnSize: Int

    // ─── GESTURE STATE ────────────────────────────────────────
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOrigTransX = 0f
    private var dragOrigTransY = 0f

    // 2-finger pinch/rotate
    private var isPinching = false
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinchStartAngle = 0f
    private var pinchStartRotation = 0f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var pinchOrigTransX = 0f
    private var pinchOrigTransY = 0f

    // Corner resize
    private enum class Handle { NONE, TL, TR, BL, BR }
    private var activeHandle = Handle.NONE
    private var handleDragStartX = 0f
    private var handleDragStartY = 0f
    private var handleStartW = 0f
    private var handleStartH = 0f

    private val density = context.resources.displayMetrics.density
    private val touchSlop = 16f * density
    private val handleRadius: Float

    // ─── PAINTS ───────────────────────────────────────────────
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    init {
        btnSize = (40 * density).toInt()
        handleRadius = 9f * density
        setBackgroundColor(Color.parseColor("#1AFFFFFF"))

        editText = EditText(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = null
            setTextColor(textColor)
            textSize = textFontSize / density
            gravity = Gravity.START or Gravity.TOP
            minLines = 2
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onTextChanged?.invoke(s?.toString() ?: "")
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    onCommit?.invoke(this@TextOverlayView)
                    true
                } else false
            }
            setOnClickListener { /* needed for focus */ }
        }
        addView(editText)

        // Done button
        doneBtn = ImageView(context).apply {
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (8 * density).toInt()
                rightMargin = (8 * density).toInt()
            }
            setImageResource(android.R.drawable.ic_menu_save)
            setColorFilter(DONE_COLOR)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#22000000"))
            setOnClickListener { onCommit?.invoke(this@TextOverlayView) }
        }
        addView(doneBtn)

        // Cancel button
        cancelBtn = ImageView(context).apply {
            val mlp = LayoutParams(btnSize, btnSize)
            mlp.gravity = Gravity.TOP or Gravity.START
            mlp.topMargin = (8 * density).toInt()
            mlp.leftMargin = (8 * density).toInt()
            layoutParams = mlp
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(CANCEL_COLOR)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#22000000"))
            setOnClickListener { onCancel?.invoke() }
        }
        addView(cancelBtn)

        applyActionButtonState()
        setWillNotDraw(false)
    }

    val editTextField: EditText get() = editText

    fun getText(): String = editText.text?.toString() ?: ""

    fun setText(text: String) {
        editText.setText(text)
        if (text.isNotEmpty()) editText.setSelection(text.length)
    }

    fun requestEditTextFocus() {
        editText.requestFocus()
    }

    private fun updateEditTextStyle() {
        editText.setTextColor(textColor)
        editText.textSize = textFontSize / density
        val tf: Typeface = when {
            fontPath.isNotEmpty() -> {
                try {
                    Typeface.createFromAsset(context.assets, fontPath)
                } catch (_: Exception) {
                    Typeface.DEFAULT
                }
            }
            isBold && isItalic -> Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
            isBold -> Typeface.defaultFromStyle(Typeface.BOLD)
            isItalic -> Typeface.defaultFromStyle(Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        editText.typeface = tf
        when (textAlignment) {
            Layout.Alignment.ALIGN_NORMAL -> editText.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            Layout.Alignment.ALIGN_CENTER -> editText.textAlignment = View.TEXT_ALIGNMENT_CENTER
            Layout.Alignment.ALIGN_OPPOSITE -> editText.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
        }
        invalidate()
    }

    // ─── DRAW BOUNDING BOX & HANDLES ──────────────────────────
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawBoundingBox(canvas)
    }

    private fun drawBoundingBox(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val rect = RectF(
            handleRadius, handleRadius,
            w - handleRadius, h - handleRadius
        )

        canvas.drawRect(rect, borderPaint)

        if (showHandles) {
            val hr = handleRadius
            val corners = listOf(
                rect.left to rect.top,
                rect.right to rect.top,
                rect.left to rect.bottom,
                rect.right to rect.bottom
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, hr, handleFill)
                canvas.drawCircle(cx, cy, hr, handleStroke)
            }
        }

    }

    // ─── TOUCH HANDLING ───────────────────────────────────────
    private fun getHandleAt(x: Float, y: Float): Handle {
        val w = width.toFloat()
        val h = height.toFloat()
        val hr = handleRadius * 1.5f
        val rect = RectF(handleRadius, handleRadius, w - handleRadius, h - handleRadius)
        val corners = mapOf(
            Handle.TL to Pair(rect.left, rect.top),
            Handle.TR to Pair(rect.right, rect.top),
            Handle.BL to Pair(rect.left, rect.bottom),
            Handle.BR to Pair(rect.right, rect.bottom)
        )
        for ((handle, pos) in corners) {
            if (hypot(x - pos.first, y - pos.second) <= hr) return handle
        }
        return Handle.NONE
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = ev.x; dragStartY = ev.y
                dragOrigTransX = translationX; dragOrigTransY = translationY
                isDragging = false
                activeHandle = Handle.NONE

                val h = getHandleAt(ev.x, ev.y)
                if (h != Handle.NONE) { activeHandle = h; return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && hypot(ev.x - dragStartX, ev.y - dragStartY) > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.pointerCount) {
            1 -> handleSingleFinger(event)
            2 -> handleTwoFinger(event)
        }
        return true
    }

    private fun handleSingleFinger(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = getHandleAt(ev.x, ev.y)
                if (h != Handle.NONE) {
                    activeHandle = h
                    handleDragStartX = ev.x; handleDragStartY = ev.y
                    handleStartW = width.toFloat(); handleStartH = height.toFloat()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHandle != Handle.NONE) {
                    val dw = when (activeHandle) {
                        Handle.TL, Handle.BL -> handleDragStartX - ev.x
                        else -> ev.x - handleDragStartX
                    }
                    val newW = (handleStartW + dw).coerceAtLeast(100f * density)
                    val ratio = newW / handleStartW
                    val newH = (handleStartH * ratio).coerceAtLeast(60f * density)
                    val lp = layoutParams
                    if (lp != null) {
                        lp.width = newW.toInt()
                        lp.height = newH.toInt()
                        layoutParams = lp
                    }
                    requestLayout()
                    return
                }

                val dx = ev.x - dragStartX
                val dy = ev.y - dragStartY
                if (isDragging) {
                    translationX = dragOrigTransX + dx
                    translationY = dragOrigTransY + dy
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                isDragging = false
            }
        }
    }

    private fun handleTwoFinger(ev: MotionEvent) {
        val x0 = ev.getX(0); val y0 = ev.getY(0)
        val x1 = ev.getX(1); val y1 = ev.getY(1)
        val dist = hypot(x1 - x0, y1 - y0)
        val angle = Math.toDegrees(
            atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        ).toFloat()

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPinching = true
                pinchStartDist = dist
                pinchStartScale = scaleX
                pinchStartAngle = angle
                pinchStartRotation = rotation
                pinchMidX = (x0 + x1) / 2f; pinchMidY = (y0 + y1) / 2f
                pinchOrigTransX = translationX; pinchOrigTransY = translationY
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isPinching || pinchStartDist == 0f) return
                val newScale = (pinchStartScale * (dist / pinchStartDist)).coerceIn(0.3f, 5f)
                scaleX = newScale
                scaleY = newScale

                var newRotation = pinchStartRotation + (angle - pinchStartAngle)
                newRotation = (Math.round(newRotation / 5f) * 5f)
                rotation = newRotation

                val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
                translationX = pinchOrigTransX + (midX - pinchMidX)
                translationY = pinchOrigTransY + (midY - pinchMidY)
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPinching = false
                pinchStartDist = 0f
            }
        }
    }

    // ─── PUBLIC HELPERS ───────────────────────────────────────
    fun getTextPosition(): FloatArray {
        return floatArrayOf(translationX, translationY)
    }

    fun setTextPosition(x: Float, y: Float) {
        translationX = x
        translationY = y
    }

    fun getTextScale(): Float = scaleX

    fun setTextScale(s: Float) {
        scaleX = s; scaleY = s
    }

    fun getTextRotation(): Float = rotation

    fun setTextRotation(r: Float) {
        rotation = r
    }
}

package com.paperleaf.sketchbook.ui

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.core.graphics.ColorUtils
import com.paperleaf.sketchbook.R

class ColorPickerPopup(
    private val context: Context,
    private val anchorView: View,
    private val initialColor: Int,
    private val onColorChanged: (Int) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val popup: PopupWindow
    private var currentH = 0f
    private var currentS = 0f
    private var currentV = 0f
    private var currentA = 255

    init {
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        currentH = hsv[0]; currentS = hsv[1]; currentV = hsv[2]
        currentA = Color.alpha(initialColor)

        val view = buildView()
        popup = PopupWindow(view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                cornerRadius = 20f * context.resources.displayMetrics.density
            })
            elevation = 24f * context.resources.displayMetrics.density
            isOutsideTouchable = true
            setOnDismissListener { onDismiss() }
        }
    }

    private fun buildView(): View {
        val dp = context.resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val sliderW = (160 * dp).toInt()
        val sliderH = (16 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            minimumWidth = (180 * dp).toInt()
        }

        // Color preview circle
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (12 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(makeColor())
                setStroke((3 * dp).toInt(), Color.WHITE)
            }
        }
        root.addView(preview)

        fun refreshPreview() {
            val c = makeColor()
            (preview.background as GradientDrawable).setColor(c)
            onColorChanged(c)
        }

        // Hue slider
        root.addView(makeLabel("Hue"))
        val hueBar = GradientSliderView(context, GradientSliderView.Type.HUE, sliderW, sliderH)
        hueBar.value = currentH / 360f
        hueBar.onValueChanged = { v -> currentH = v * 360f; refreshPreview() }
        root.addView(hueBar)

        // Saturation slider
        root.addView(makeLabel("Saturation"))
        val satBar = GradientSliderView(context, GradientSliderView.Type.SATURATION, sliderW, sliderH)
        satBar.value = currentS
        satBar.onValueChanged = { v -> currentS = v; refreshPreview() }
        root.addView(satBar)

        // Brightness slider
        root.addView(makeLabel("Brightness"))
        val briBar = GradientSliderView(context, GradientSliderView.Type.BRIGHTNESS, sliderW, sliderH)
        briBar.value = currentV
        briBar.onValueChanged = { v -> currentV = v; refreshPreview() }
        root.addView(briBar)

        return root
    }

    private fun makeColor() = Color.HSVToColor(currentA, floatArrayOf(currentH, currentS, currentV))

    private fun makeLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 10f
        setTextColor(Color.parseColor("#888888"))
        val dp = context.resources.displayMetrics.density
        setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
    }

    fun show() {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return
        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val dp = context.resources.displayMetrics.density
        val popupW = (184 * dp).toInt()
        val x = loc[0] + anchorView.width / 2 - popupW / 2
        try {
            popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, x,
                loc[1] - (200 * dp).toInt()
            )
        } catch (_: Exception) { }
    }

    fun dismiss() = popup.dismiss()
}

// ── Custom gradient slider ─────────────────────────────────────────────────
class GradientSliderView(
    context: Context,
    private val type: Type,
    private val sliderW: Int,
    private val sliderH: Int
) : View(context) {

    enum class Type { HUE, SATURATION, BRIGHTNESS }

    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var onValueChanged: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f * dp
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#60000000"))
    }
    private val dp = resources.displayMetrics.density
    private val radius = sliderH / 2f

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(sliderW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(sliderH + (8 * dp).toInt(), MeasureSpec.EXACTLY)
        )

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val trackRect = RectF(radius, cy - radius + 2 * dp, width - radius, cy + radius - 2 * dp)

        // Gradient sesuai tipe
        val colors = when (type) {
            Type.HUE -> IntArray(7) { i -> Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)) }
            Type.SATURATION -> intArrayOf(Color.WHITE, Color.HSVToColor(floatArrayOf(0f, 1f, 1f)))
            Type.BRIGHTNESS -> intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(0f, 0f, 1f)))
        }
        trackPaint.shader = LinearGradient(
            trackRect.left, 0f, trackRect.right, 0f, colors, null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Thumb line
        val tx = trackRect.left + value * (trackRect.right - trackRect.left)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        canvas.drawLine(tx, trackRect.top + 2 * dp, tx, trackRect.bottom - 2 * dp, thumbPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val trackLeft = radius
        val trackRight = width - radius
        val tx = (e.x - trackLeft) / (trackRight - trackLeft)
        value = tx.coerceIn(0f, 1f)
        onValueChanged?.invoke(value)
        return true
    }
}
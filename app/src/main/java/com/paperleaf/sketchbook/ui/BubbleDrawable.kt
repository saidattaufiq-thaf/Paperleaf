package com.paperleaf.sketchbook.ui

import android.graphics.*
import android.graphics.drawable.Drawable

class BubbleDrawable(
    private val arrowPosition: ArrowPosition = ArrowPosition.NONE,
    private val arrowSize: Float = 16f,
    private val cornerRadius: Float = 16f,
    private val fillColor: Int = Color.WHITE,
    private val shadowColor: Int = Color.parseColor("#20000000"),
    private val shadowRadius: Float = 12f
) : Drawable() {

    enum class ArrowPosition { NONE, TOP_CENTER, TOP_LEFT, TOP_RIGHT,
                             BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shadowColor
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }

    init {
        shadowPaint.setShadowLayer(shadowRadius, 0f, 4f, shadowColor)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        val path = createBubblePath(w, h)

        canvas.drawPath(path, shadowPaint)
        canvas.drawPath(path, fillPaint)
    }

    private fun createBubblePath(w: Float, h: Float): Path {
        val path = Path()
        val r = cornerRadius
        val aSz = arrowSize
        val offset = aSz * 0.6f

        when (arrowPosition) {
            ArrowPosition.NONE -> {
                path.addRoundRect(offset, offset, w - offset, h - offset, r, r, Path.Direction.CW)
            }
            ArrowPosition.BOTTOM_CENTER -> {
                val arrowX = w / 2f
                val top = offset
                val bottom = h - offset - aSz
                path.moveTo(offset + r, top)
                path.lineTo(w - offset - r, top)
                path.arcTo(w - offset - r * 2, top, w - offset, top + r * 2, -90f, 90f, false)
                path.lineTo(w - offset, bottom)
                path.arcTo(w - offset - r * 2, bottom - r * 2, w - offset, bottom, 0f, 90f, false)
                path.lineTo(arrowX + aSz, bottom)
                path.lineTo(arrowX, bottom + aSz)
                path.lineTo(arrowX - aSz, bottom)
                path.lineTo(offset + r, bottom)
                path.arcTo(offset, bottom - r * 2, offset + r * 2, bottom, 90f, 90f, false)
                path.lineTo(offset, top + r)
                path.arcTo(offset, top, offset + r * 2, top + r * 2, 180f, 90f, false)
            }
            ArrowPosition.BOTTOM_LEFT -> {
                val arrowX = w * 0.25f
                val top = offset
                val bottom = h - offset - aSz
                path.moveTo(arrowX + aSz + r, top)
                path.lineTo(w - offset - r, top)
                path.arcTo(w - offset - r * 2, top, w - offset, top + r * 2, -90f, 90f, false)
                path.lineTo(w - offset, bottom)
                path.arcTo(w - offset - r * 2, bottom - r * 2, w - offset, bottom, 0f, 90f, false)
                path.lineTo(arrowX + aSz, bottom)
                path.lineTo(arrowX, bottom + aSz)
                path.lineTo(arrowX - aSz, bottom)
                path.lineTo(offset + r, bottom)
                path.arcTo(offset, bottom - r * 2, offset + r * 2, bottom, 90f, 90f, false)
                path.lineTo(offset, top + r)
                path.arcTo(offset, top, offset + r * 2, top + r * 2, 180f, 90f, false)
            }
            ArrowPosition.BOTTOM_RIGHT -> {
                val arrowX = w * 0.75f
                val top = offset
                val bottom = h - offset - aSz
                path.moveTo(offset + r, top)
                path.lineTo(w - offset - r, top)
                path.arcTo(w - offset - r * 2, top, w - offset, top + r * 2, -90f, 90f, false)
                path.lineTo(w - offset, bottom)
                path.arcTo(w - offset - r * 2, bottom - r * 2, w - offset, bottom, 0f, 90f, false)
                path.lineTo(arrowX + aSz, bottom)
                path.lineTo(arrowX, bottom + aSz)
                path.lineTo(arrowX - aSz, bottom)
                path.lineTo(offset + r, bottom)
                path.arcTo(offset, bottom - r * 2, offset + r * 2, bottom, 90f, 90f, false)
                path.lineTo(offset, top + r)
                path.arcTo(offset, top, offset + r * 2, top + r * 2, 180f, 90f, false)
            }
            ArrowPosition.TOP_CENTER -> {
                val arrowX = w / 2f
                val top = offset + aSz
                val bottom = h - offset
                path.moveTo(arrowX + aSz + r, top)
                path.lineTo(w - offset - r, top)
                path.arcTo(w - offset - r * 2, top, w - offset, top + r * 2, -90f, 90f, false)
                path.lineTo(w - offset, bottom - r)
                path.arcTo(w - offset - r * 2, bottom - r * 2, w - offset, bottom, 0f, 90f, false)
                path.lineTo(offset + r, bottom)
                path.arcTo(offset, bottom - r * 2, offset + r * 2, bottom, 90f, 90f, false)
                path.lineTo(offset, top + r)
                path.arcTo(offset, top, offset + r * 2, top + r * 2, 180f, 90f, false)
                path.lineTo(arrowX + aSz, top)
                path.lineTo(arrowX, top - aSz)
                path.lineTo(arrowX - aSz, top)
            }
            else -> {
                path.addRoundRect(offset, offset, w - offset, h - offset, r, r, Path.Direction.CW)
            }
        }
        path.close()
        return path
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }
}

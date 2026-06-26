package com.paperleaf.sketchbook.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.paperleaf.sketchbook.R

class TooltipPopup(
    private val ctx: Context,
    private val content: View,
    private val arrowPosition: BubbleDrawable.ArrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
    private val backgroundColor: Int = getThemeBgColor(ctx),
    private val cornerRadius: Float = 16f,
    private val animDuration: Long = 180L,
    private val margin: Int = dpToPx(ctx, 8)
) : PopupWindow(ctx) {

    companion object {
        fun getThemeBgColor(ctx: Context): Int {
            val uiMode = ctx.resources.configuration.uiMode
            val isDark = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            return if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#FAFAFA")
        }

        fun dpToPx(ctx: Context, dp: Int): Int =
            (dp * ctx.resources.displayMetrics.density).toInt()
    }

    private val rootView = FrameLayout(ctx).apply {
        background = BubbleDrawable(
            arrowPosition = arrowPosition,
            cornerRadius = cornerRadius,
            fillColor = backgroundColor,
            shadowColor = Color.parseColor("#20000000")
        )
        setPadding(margin, margin, margin, margin)
        elevation = dpToPx(ctx, 6).toFloat()
    }

    private var animating = false
    private var isDismissing = false

    init {
        contentView = rootView
        isOutsideTouchable = true
        isFocusable = true
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        width = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT

        addContentView(content)
    }

    private fun addContentView(child: View) {
        rootView.addView(child)
    }

    fun showAt(anchor: View, yOffset: Int = 0) {
        val anchorPos = IntArray(2)
        anchor.getLocationOnScreen(anchorPos)

        val parent = anchor.rootView
        val screenW = parent.width
        val screenH = parent.height

        val arrowX = anchorPos[0] + anchor.width / 2f
        val arrowY = anchorPos[1].toFloat()

        val measured = measureContent()
        val popupW = measured.first
        val popupH = measured.second

        val arrowW = dpToPx(ctx, 12)

        var posX = (arrowX - popupW / 2f).toInt()
        var posY = (arrowY - popupH - arrowW + yOffset).toInt()
        var actualArrowPos = BubbleDrawable.ArrowPosition.BOTTOM_CENTER

        if (posX < margin) {
            posX = margin
            actualArrowPos = BubbleDrawable.ArrowPosition.BOTTOM_LEFT
        } else if (posX + popupW > screenW - margin) {
            posX = screenW - popupW - margin
            actualArrowPos = BubbleDrawable.ArrowPosition.BOTTOM_RIGHT
        }

        if (posY < margin) {
            posY = (arrowY + anchor.height + arrowW + yOffset).toInt()
            actualArrowPos = when (actualArrowPos) {
                BubbleDrawable.ArrowPosition.BOTTOM_LEFT -> BubbleDrawable.ArrowPosition.TOP_LEFT
                BubbleDrawable.ArrowPosition.BOTTOM_RIGHT -> BubbleDrawable.ArrowPosition.TOP_RIGHT
                else -> BubbleDrawable.ArrowPosition.TOP_CENTER
            }
        }

        if (posY + popupH > screenH - margin) {
            posY = screenH - popupH - margin
        }

        updateArrowDrawable(actualArrowPos)

        showAtLocation(parent, Gravity.NO_GRAVITY, posX, posY)
    }

    override fun showAtLocation(parent: View, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        if (!isShowing) return
        rootView.alpha = 0f
        rootView.scaleX = 0.8f
        rootView.scaleY = 0.8f
        startShowAnimation()
    }

    private fun measureContent(): Pair<Int, Int> {
        val wSpec = View.MeasureSpec.makeMeasureSpec(
            (ctx.resources.displayMetrics.widthPixels * 0.85f).toInt(),
            View.MeasureSpec.AT_MOST
        )
        val hSpec = View.MeasureSpec.makeMeasureSpec(
            (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt(),
            View.MeasureSpec.AT_MOST
        )
        rootView.measure(wSpec, hSpec)
        return Pair(rootView.measuredWidth, rootView.measuredHeight)
    }

    private fun updateArrowDrawable(pos: BubbleDrawable.ArrowPosition) {
        rootView.background = BubbleDrawable(
            arrowPosition = pos,
            cornerRadius = cornerRadius,
            fillColor = backgroundColor,
            shadowColor = Color.parseColor("#20000000")
        )
    }

    private fun startShowAnimation() {
        if (animating) return
        animating = true

        rootView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(animDuration)
            .withEndAction { animating = false }
            .start()

        // Spring bounce for scale
        SpringAnimation(rootView, DynamicAnimation.SCALE_X, 1f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            setStartVelocity(4f)
            start()
        }
        SpringAnimation(rootView, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            setStartVelocity(4f)
            start()
        }
    }

    override fun dismiss() {
        if (isDismissing) return
        if (!isShowing) return

        if (animating) {
            rootView.clearAnimation()
            animating = false
        }

        isDismissing = true
        startDismissAnimation()
    }

    private fun startDismissAnimation() {
        animating = true

        rootView.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(animDuration)
            .withEndAction {
                animating = false
                isDismissing = false
                rootView.alpha = 0f
                rootView.scaleX = 0.8f
                rootView.scaleY = 0.8f
                super.dismiss()
            }
            .start()
    }

    fun getRootView(): View = rootView
}

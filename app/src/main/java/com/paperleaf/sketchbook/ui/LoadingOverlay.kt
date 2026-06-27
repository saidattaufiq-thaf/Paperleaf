package com.paperleaf.sketchbook.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.paperleaf.sketchbook.R

class LoadingOverlay private constructor(
    private val activity: Activity,
    private val useLottie: Boolean = true,
    private val initialText: String? = null
) {
    private var container: FrameLayout? = null
    private var animView: View? = null
    private var statusText: TextView? = null
    private var angleAnim: ValueAnimator? = null

    fun show() {
        if (container != null) return
        if (activity.isFinishing) return
        val decor = (activity.window.decorView as? ViewGroup) ?: return
        container = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { }
        }
        val dp = activity.resources.displayMetrics.density

        val centerContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        if (useLottie) {
            val lottie = LottieAnimationView(activity).apply {
                setAnimation(R.raw.paperleaf_loading)
                repeatCount = ValueAnimator.INFINITE
                speed = 1.2f
                layoutParams = FrameLayout.LayoutParams(
                    (80 * dp).toInt(), (80 * dp).toInt(),
                    Gravity.CENTER_HORIZONTAL
                )
                playAnimation()
            }
            animView = lottie
            centerContainer.addView(lottie)
        } else {
            val spinner = object : View(activity) {
                private val rect = RectF()
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#7AB8FF")
                    style = Paint.Style.STROKE
                    strokeWidth = 4f * dp
                    strokeCap = Paint.Cap.ROUND
                }
                private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#33FFFFFF")
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * dp
                    strokeCap = Paint.Cap.ROUND
                }
                private var sweep = 0f

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = minOf(cx, cy) - 6f * dp
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawOval(rect, bgPaint)
                    canvas.drawArc(rect, sweep - 90, 270f, false, paint)
                }

                fun updateAngle(angle: Float) {
                    sweep = angle
                    invalidate()
                }
            }
            spinner.layoutParams = FrameLayout.LayoutParams(
                (80 * dp).toInt(), (80 * dp).toInt(),
                Gravity.CENTER_HORIZONTAL
            )
            animView = spinner
            centerContainer.addView(spinner)

            angleAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1200
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    spinner.updateAngle(anim.animatedValue as Float)
                }
                start()
            }
        }

        statusText = TextView(activity).apply {
            text = initialText ?: ""
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        centerContainer.addView(statusText!!)

        container!!.addView(centerContainer)
        decor.addView(container)
        container!!.alpha = 0f
        container!!.animate().alpha(1f).setDuration(200).start()
    }

    fun setText(text: String) {
        if (activity.isFinishing) return
        activity.runOnUiThread {
            statusText?.text = text
        }
    }

    fun hide() {
        val c = container ?: return
        container = null
        c.animate().alpha(0f).setDuration(200).withEndAction {
            angleAnim?.cancel()
            angleAnim = null
            if (animView is LottieAnimationView) {
                (animView as LottieAnimationView).cancelAnimation()
            }
            animView = null
            val decor = (activity.window.decorView as? ViewGroup) ?: return@withEndAction
            if (c.parent == decor) {
                decor.removeView(c)
            }
        }.start()
    }

    companion object {
        private val instances = mutableMapOf<Activity, LoadingOverlay>()

        fun show(activity: Activity, text: String? = null, useLottie: Boolean = true) {
            hide(activity)
            val overlay = LoadingOverlay(activity, useLottie, text)
            instances[activity] = overlay
            overlay.show()
        }

        fun setText(activity: Activity, text: String) {
            instances[activity]?.setText(text)
        }

        fun hide(activity: Activity) {
            instances.remove(activity)?.hide()
        }
    }
}

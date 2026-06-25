package com.paperleaf.sketchbook.animation

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

object ViewMorphAnimator {

    private const val DURATION_IN = 350L
    private const val DURATION_OUT = 300L

    fun morphIn(view: View, onEnd: (() -> Unit)? = null) {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.visibility = View.VISIBLE

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DURATION_IN)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
            .start()
    }

    fun morphOut(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(DURATION_OUT)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
            .start()
    }

    fun morphInImmediate(view: View, onEnd: (() -> Unit)? = null) {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.visibility = View.VISIBLE

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DURATION_IN)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction(onEnd)
            .start()
    }
}

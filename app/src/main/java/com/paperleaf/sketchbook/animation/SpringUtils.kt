package com.paperleaf.sketchbook.animation

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object SpringUtils {
    fun springScale(view: View, targetScale: Float = 1f) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, targetScale).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        SpringAnimation(view, DynamicAnimation.SCALE_Y, targetScale).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
    }

    fun springLift(view: View, translationY: Float, translationZ: Float) {
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, translationY).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_LOW
            start()
        }
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Z, translationZ).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
    }

    fun springBounceIn(view: View) {
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.alpha = 0f
        view.postDelayed({
            view.animate().alpha(1f).setDuration(100).start()
            springScale(view, 1f)
        }, 50)
    }

    fun springPressEffect(view: View) {
        view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                start()
            }
            SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                start()
            }
        }.start()
    }
}

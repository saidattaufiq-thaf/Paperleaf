package com.paperleaf.sketchbook.animation

import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

class ProcreateStyleAnimator : DefaultItemAnimator() {

    init {
        moveDuration = 250
        addDuration = 0
        removeDuration = 0
        supportsChangeAnimations = false
    }

    private val smoothInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val view = holder.itemView
        val deltaX = toX - fromX
        val deltaY = toY - fromY

        if (deltaX == 0 && deltaY == 0) {
            dispatchMoveFinished(holder)
            return false
        }

        view.translationX = -deltaX.toFloat()
        view.translationY = -deltaY.toFloat()

        view.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(moveDuration)
            .setInterpolator(smoothInterpolator)
            .withStartAction { dispatchMoveStarting(holder) }
            .withEndAction {
                view.translationX = 0f
                view.translationY = 0f
                dispatchMoveFinished(holder)
            }
            .start()

        return true
    }
}
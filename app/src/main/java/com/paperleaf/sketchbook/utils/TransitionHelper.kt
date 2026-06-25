package com.paperleaf.sketchbook.utils

import android.app.Activity
import android.app.Dialog
import com.paperleaf.sketchbook.R

@Suppress("DEPRECATION")
object TransitionHelper {

    fun morphForward(activity: Activity) {
        activity.overridePendingTransition(R.anim.morph_in, R.anim.morph_out)
    }

    fun morphBackward(activity: Activity) {
        activity.overridePendingTransition(R.anim.morph_in_reverse, R.anim.morph_out_reverse)
    }

    fun morphFinish(activity: Activity) {
        activity.overridePendingTransition(R.anim.morph_in_reverse, R.anim.morph_out_reverse)
    }

    fun morphDialog(dialog: Dialog) {
        dialog.window?.setWindowAnimations(R.style.MorphDialogAnimation)
    }
}

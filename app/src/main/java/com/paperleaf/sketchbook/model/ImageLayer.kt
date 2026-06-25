package com.paperleaf.sketchbook.model

import android.graphics.Bitmap

data class ImageLayer(
    val bitmap: Bitmap,
    var x: Float = 0f,
    var y: Float = 0f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var isSelected: Boolean = true
)
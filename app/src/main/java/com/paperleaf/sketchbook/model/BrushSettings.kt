package com.paperleaf.sketchbook.model

import kotlin.math.pow

data class BrushSettings(
    var toolType: Int = Companion.TOOL_FOUNTAIN_PEN,
    var color: Int = 0xFF1A1A1A.toInt(),
    var size: Float = 8f,
    var opacity: Float = 1f,

    // Parameter Realistis Baru
    var sizeMin: Float = 5f,
    var sizePressure: Float = 1.0f,
    var sizeVelocity: Float = 0.3f,
    var flow: Float = 1.0f,
    var hardness: Float = 0.9f,
    var textureStrength: Float = 0.6f,
    var grain: Float = 0.4f,
    var bleed: Float = 0.0f,
    var spacing: Float = 0.08f
) {

    companion object {
        const val TOOL_ERASER       = 0
        const val TOOL_FOUNTAIN_PEN = 1
        const val TOOL_PENCIL       = 2
        const val TOOL_MARKER       = 3
        const val TOOL_INK_PEN      = 4
        const val TOOL_WATERCOLOR   = 5
        const val TOOL_BRUSH        = 6
        const val TOOL_ROLLER       = 9
    }

    fun getDynamicSize(pressure: Float, velocity: Float): Float {
        val pressureSize = sizeMin + (size - sizeMin) * pressure.toDouble().pow(sizePressure.toDouble()).toFloat()
        val velFactor = 1f - (velocity * sizeVelocity).coerceIn(0f, 0.7f)
        return (pressureSize * velFactor).coerceIn(sizeMin, size)
    }

    fun getDynamicOpacity(pressure: Float, @Suppress("UNUSED_PARAMETER") velocity: Float): Float {
        val p = pressure.toDouble().pow(0.8).toFloat()
        return (opacity * p * flow).coerceIn(0.1f, 1.0f)
    }
}

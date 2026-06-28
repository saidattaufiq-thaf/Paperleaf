package com.paperleaf.sketchbook.pageflip.premium.config

data class ShadowConfig(
    val edgeShadowStartColor: FloatArray = EDGE_SHADOW_START,
    val edgeShadowEndColor: FloatArray = EDGE_SHADOW_END,
    val baseShadowStartColor: FloatArray = BASE_SHADOW_START,
    val baseShadowEndColor: FloatArray = BASE_SHADOW_END,
    val shadowWidth: Float = 0.25f,
    val shadowSoftness: Float = 0.35f,
    val maxIntensity: Float = 0.8f,
    val contactShadowColor: FloatArray = floatArrayOf(0.06f, 0.06f, 0.06f),
    val curlShadowColor: FloatArray = floatArrayOf(0.12f, 0.10f, 0.08f),
    val spineShadowColor: FloatArray = floatArrayOf(0.10f, 0.10f, 0.10f),
    val ambientShadowColor: FloatArray = floatArrayOf(0.04f, 0.04f, 0.05f),
    val lightDirection: FloatArray = floatArrayOf(0.3f, -0.5f, 0.8f),
    val contactDistance: Float = 0.005f,
    val spineWidth: Float = 6.0f
) {
    companion object {
        val EDGE_SHADOW_START = floatArrayOf(0.15f, 0.15f, 0.15f, 0.5f)
        val EDGE_SHADOW_END = floatArrayOf(0.35f, 0.35f, 0.35f, 0.0f)
        val BASE_SHADOW_START = floatArrayOf(0.08f, 0.08f, 0.08f, 0.6f)
        val BASE_SHADOW_END = floatArrayOf(0.3f, 0.3f, 0.3f, 0.0f)
    }
}

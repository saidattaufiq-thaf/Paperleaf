package com.paperleaf.sketchbook.pageflip.premium.config

data class PaperLightingConfig(
    val lightDirection: FloatArray = floatArrayOf(0.3f, 0.5f, 0.8f),
    val ambientColor: FloatArray = floatArrayOf(0.32f, 0.32f, 0.36f),
    val diffuseColor: FloatArray = floatArrayOf(0.75f, 0.73f, 0.70f),
    val specularColor: FloatArray = floatArrayOf(0.03f, 0.03f, 0.03f),
    val pageColor: FloatArray = floatArrayOf(0.95f, 0.92f, 0.88f),
    val viewPosition: FloatArray = floatArrayOf(0f, 0f, 2f),
    val roughness: Float = 0.75f,
    val brightness: Float = 0.95f,
    val rimIntensity: Float = 0.12f,
    val aoIntensity: Float = 0.35f,
    val backLightIntensity: Float = 0.06f,
    val translucency: Float = 0.10f
)

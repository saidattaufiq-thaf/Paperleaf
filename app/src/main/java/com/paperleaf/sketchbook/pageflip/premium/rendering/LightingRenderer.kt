package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import com.paperleaf.sketchbook.pageflip.engine.ShaderManager
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Advanced lighting renderer for realistic paper illumination.
 * Implements Phong reflection model with multiple light components.
 * 
 * Features:
 * - Ambient lighting
 * - Diffuse lighting (Lambertian)
 * - Specular highlights
 * - Dynamic light direction
 * - View-dependent shading
 */
class LightingRenderer {
    
    companion object {
        private const val TAG = "LightingRenderer"
        
        // Default light properties
        val DEFAULT_LIGHT_DIRECTION = floatArrayOf(0.5f, 0.8f, 1.0f) // Normalized
        val DEFAULT_AMBIENT_COLOR = floatArrayOf(0.4f, 0.4f, 0.45f) // Slightly blue-ish
        val DEFAULT_DIFFUSE_COLOR = floatArrayOf(0.8f, 0.8f, 0.75f) // Warm white
        val DEFAULT_SPECULAR_COLOR = floatArrayOf(0.3f, 0.3f, 0.3f)
        const val DEFAULT_SHININESS = 32.0f
        
        // Light positions for different scenarios
        val TOP_LEFT_LIGHT = floatArrayOf(-0.5f, 0.8f, 0.5f)
        val TOP_RIGHT_LIGHT = floatArrayOf(0.5f, 0.8f, 0.5f)
        val CENTER_LIGHT = floatArrayOf(0.0f, 1.0f, 0.5f)
    }
    
    /**
     * Lighting configuration
     */
    data class LightingConfig(
        val lightDirection: FloatArray = DEFAULT_LIGHT_DIRECTION,
        val ambientColor: FloatArray = DEFAULT_AMBIENT_COLOR,
        val diffuseColor: FloatArray = DEFAULT_DIFFUSE_COLOR,
        val specularColor: FloatArray = DEFAULT_SPECULAR_COLOR,
        val shininess: Float = DEFAULT_SHININESS,
        val viewPosition: FloatArray = floatArrayOf(0f, 0f, 2f), // Camera position
        val enableSpecular: Boolean = true,
        val enableAmbient: Boolean = true
    )
    
    private var config = LightingConfig()
    private var lightingProgram = -1
    private var isInitialized = false
    
    // Uniform locations
    private var uMVPMatrix = -1
    private var uTexture = -1
    private var uLightDir = -1
    private var uAmbient = -1
    private var uDiffuse = -1
    private var uSpecular = -1
    private var uViewPos = -1
    private var aTexCoord = -1
    private var aNormal = -1
    private var aPosition = -1
    
    /**
     * Initialize lighting renderer
     */
    fun initialize(shaderManager: ShaderManager) {
        lightingProgram = shaderManager.lightingProgram
        if (lightingProgram <= 0) {
            Log.w(TAG, "Lighting program not available")
            return
        }
        
        uMVPMatrix = GLES30.glGetUniformLocation(lightingProgram, "u_MVPMatrix")
        uTexture = GLES30.glGetUniformLocation(lightingProgram, "u_texture")
        uLightDir = GLES30.glGetUniformLocation(lightingProgram, "u_lightDir")
        uAmbient = GLES30.glGetUniformLocation(lightingProgram, "u_ambient")
        uDiffuse = GLES30.glGetUniformLocation(lightingProgram, "u_diffuse")
        uSpecular = GLES30.glGetUniformLocation(lightingProgram, "u_specular")
        uViewPos = GLES30.glGetUniformLocation(lightingProgram, "u_viewPos")
        aTexCoord = GLES30.glGetAttribLocation(lightingProgram, "a_texCoord")
        aNormal = GLES30.glGetAttribLocation(lightingProgram, "a_normal")
        aPosition = GLES30.glGetAttribLocation(lightingProgram, "a_position")
        
        isInitialized = true
        Log.d(TAG, "LightingRenderer initialized")
    }
    
    /**
     * Render page with full lighting
     */
    fun render(
        mvpMatrix: FloatArray,
        textureId: Int,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        normalBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int
    ) {
        if (!isInitialized || lightingProgram <= 0) return
        
        GLES30.glUseProgram(lightingProgram)
        
        // Set MVP matrix
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        
        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTexture, 0)
        
        // Set light uniforms
        GLES30.glUniform3fv(uLightDir, 1, normalize(config.lightDirection), 0)
        GLES30.glUniform3fv(uAmbient, 1, config.ambientColor, 0)
        GLES30.glUniform3fv(uDiffuse, 1, config.diffuseColor, 0)
        GLES30.glUniform3fv(uSpecular, 1, config.specularColor, 0)
        GLES30.glUniform3fv(uViewPos, 1, config.viewPosition, 0)
        
        // Enable and bind vertex attributes
        setupVertexAttributes(vertexBuffer, texCoordBuffer, normalBuffer)
        
        // Draw
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            indexCount,
            GLES30.GL_UNSIGNED_SHORT,
            indexBuffer
        )
        
        // Cleanup
        cleanupVertexAttributes()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
    }
    
    /**
     * Render with dynamic curl-aware lighting
     * Adjusts light direction based on page curl
     */
    fun renderWithCurl(
        mvpMatrix: FloatArray,
        textureId: Int,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        normalBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float,
        bendPosition: Float
    ) {
        if (!isInitialized || lightingProgram <= 0) return
        
        // Adjust light direction based on curl
        val adjustedLightDir = adjustLightForCurl(
            config.lightDirection,
            curlFactor,
            bendPosition
        )
        
        val originalLightDir = config.lightDirection
        config.copy(lightDirection = adjustedLightDir)
        
        render(
            mvpMatrix,
            textureId,
            vertexBuffer,
            texCoordBuffer,
            normalBuffer,
            indexBuffer,
            indexCount
        )
        
        // Restore original
        config.copy(lightDirection = originalLightDir)
    }
    
    /**
     * Adjust light direction based on page curl for realistic effect
     */
    private fun adjustLightForCurl(
        originalDir: FloatArray,
        curlFactor: Float,
        bendPosition: Float
    ): FloatArray {
        // Simulate light wrapping around curled page
        val curlAngle = curlFactor * Math.PI.toFloat() * 0.5f
        
        // Rotate light direction around Y axis based on curl
        val cosAngle = cos(curlAngle)
        val sinAngle = sin(curlAngle)
        
        return floatArrayOf(
            originalDir[0] * cosAngle - originalDir[2] * sinAngle,
            originalDir[1],
            originalDir[0] * sinAngle + originalDir[2] * cosAngle
        )
    }
    
    /**
     * Setup vertex attributes for rendering
     */
    private fun setupVertexAttributes(
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        normalBuffer: FloatBuffer
    ) {
        // Position
        GLES30.glEnableVertexAttribArray(aPosition)
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(
            aPosition,
            3,
            GLES30.GL_FLOAT,
            false,
            8 * 4, // Stride: 3 pos + 3 normal + 2 texcoord
            vertexBuffer
        )
        
        // Normal
        GLES30.glEnableVertexAttribArray(aNormal)
        vertexBuffer.position(3) // Skip position
        GLES30.glVertexAttribPointer(
            aNormal,
            3,
            GLES30.GL_FLOAT,
            false,
            8 * 4,
            vertexBuffer
        )
        
        // Texture coordinate
        GLES30.glEnableVertexAttribArray(aTexCoord)
        vertexBuffer.position(6) // Skip position + normal
        GLES30.glVertexAttribPointer(
            aTexCoord,
            2,
            GLES30.GL_FLOAT,
            false,
            8 * 4,
            vertexBuffer
        )
    }
    
    /**
     * Cleanup vertex attributes
     */
    private fun cleanupVertexAttributes() {
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aNormal)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }
    
    /**
     * Normalize a 3-component vector
     */
    private fun normalize(vec: FloatArray): FloatArray {
        val length = kotlin.math.sqrt(
            vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2]
        )
        if (length < 0.0001f) return vec
        return floatArrayOf(vec[0] / length, vec[1] / length, vec[2] / length)
    }
    
    /**
     * Update lighting configuration
     */
    fun updateConfig(config: LightingConfig) {
        this.config = config
    }
    
    /**
     * Set light preset
     */
    fun setLightPreset(preset: LightPreset) {
        val newConfig = when (preset) {
            LightPreset.TOP_LEFT -> config.copy(lightDirection = TOP_LEFT_LIGHT)
            LightPreset.TOP_RIGHT -> config.copy(lightDirection = TOP_RIGHT_LIGHT)
            LightPreset.CENTER -> config.copy(lightDirection = CENTER_LIGHT)
            LightPreset.SOFT -> config.copy(
                ambientColor = floatArrayOf(0.5f, 0.5f, 0.55f),
                diffuseColor = floatArrayOf(0.7f, 0.7f, 0.65f),
                specularColor = floatArrayOf(0.2f, 0.2f, 0.2f)
            )
            LightPreset.DRAMATIC -> config.copy(
                ambientColor = floatArrayOf(0.2f, 0.2f, 0.25f),
                diffuseColor = floatArrayOf(0.9f, 0.9f, 0.85f),
                specularColor = floatArrayOf(0.5f, 0.5f, 0.5f),
                shininess = 64.0f
            )
        }
        updateConfig(newConfig)
    }
    
    /**
     * Check if renderer is ready
     */
    fun isReady(): Boolean = isInitialized && lightingProgram > 0
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        isInitialized = false
    }
    
    enum class LightPreset {
        TOP_LEFT,
        TOP_RIGHT,
        CENTER,
        SOFT,
        DRAMATIC
    }
}

package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import com.paperleaf.sketchbook.pageflip.engine.ShaderManager
import com.paperleaf.sketchbook.pageflip.premium.mesh.MeshGenerator
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Renders dynamic shadows for realistic page flip effect.
 * Implements soft shadows with gradient falloff.
 * 
 * Features:
 * - Edge shadow on folded page
 * - Base shadow under page
 * - Soft gradient transitions
 * - Dynamic intensity based on curl
 */
class ShadowRenderer {
    
    companion object {
        private const val TAG = "ShadowRenderer"
        
        // Shadow colors (RGBA)
        private val EDGE_SHADOW_START = floatArrayOf(0.15f, 0.15f, 0.15f, 0.5f)
        private val EDGE_SHADOW_END = floatArrayOf(0.35f, 0.35f, 0.35f, 0.0f)
        private val BASE_SHADOW_START = floatArrayOf(0.08f, 0.08f, 0.08f, 0.6f)
        private val BASE_SHADOW_END = floatArrayOf(0.3f, 0.3f, 0.3f, 0.0f)
    }
    
    /**
     * Shadow configuration parameters
     */
    data class ShadowConfig(
        val edgeShadowStartColor: FloatArray = EDGE_SHADOW_START,
        val edgeShadowEndColor: FloatArray = EDGE_SHADOW_END,
        val baseShadowStartColor: FloatArray = BASE_SHADOW_START,
        val baseShadowEndColor: FloatArray = BASE_SHADOW_END,
        val shadowWidth: Float = 0.25f,
        val shadowSoftness: Float = 0.35f,
        val maxIntensity: Float = 0.8f
    )
    
    private var config = ShadowConfig()
    private var shadowProgram = -1
    private var isInitialized = false
    
    // Uniform locations
    private var uMVPMatrix = -1
    private var uShadowColor = -1
    private var uIntensity = -1
    private var aPosition = -1
    private var aTexCoord = -1
    
    /**
     * Initialize shadow renderer
     */
    fun initialize(shaderManager: ShaderManager) {
        shadowProgram = shaderManager.shadowProgram
        if (shadowProgram <= 0) {
            Log.w(TAG, "Shadow program not available, using fallback")
            return
        }
        
        uMVPMatrix = GLES30.glGetUniformLocation(shadowProgram, "u_MVPMatrix")
        uShadowColor = GLES30.glGetUniformLocation(shadowProgram, "u_shadowColor")
        uIntensity = GLES30.glGetUniformLocation(shadowProgram, "u_intensity")
        aPosition = GLES30.glGetAttribLocation(shadowProgram, "a_position")
        aTexCoord = GLES30.glGetAttribLocation(shadowProgram, "a_texCoord")
        
        isInitialized = true
        Log.d(TAG, "ShadowRenderer initialized")
    }
    
    /**
     * Render edge shadow on folded page
     */
    fun renderEdgeShadow(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float
    ) {
        if (!isInitialized || shadowProgram <= 0) return
        
        GLES30.glUseProgram(shadowProgram)
        
        // Set MVP matrix
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        
        // Calculate shadow intensity based on curl
        val intensity = calculateShadowIntensity(curlFactor) * config.maxIntensity
        
        // Render shadow gradient along edge
        renderShadowGradient(
            mvpMatrix,
            vertexBuffer,
            texCoordBuffer,
            indexBuffer,
            indexCount,
            config.edgeShadowStartColor,
            config.edgeShadowEndColor,
            intensity
        )
        
        GLES30.glUseProgram(0)
    }
    
    /**
     * Render base shadow under the page
     */
    fun renderBaseShadow(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float,
        offsetZ: Float = -0.001f
    ) {
        if (!isInitialized || shadowProgram <= 0) return
        
        GLES30.glUseProgram(shadowProgram)
        
        // Offset matrix for shadow position
        val shadowMvp = mvpMatrix.clone()
        shadowMvp[14] += offsetZ // Move shadow slightly behind page
        
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, shadowMvp, 0)
        
        // Base shadow is softer and darker
        val intensity = calculateShadowIntensity(curlFactor) * config.maxIntensity * 0.7f
        
        renderShadowGradient(
            shadowMvp,
            vertexBuffer,
            texCoordBuffer,
            indexBuffer,
            indexCount,
            config.baseShadowStartColor,
            config.baseShadowEndColor,
            intensity
        )
        
        GLES30.glUseProgram(0)
    }
    
    /**
     * Render shadow gradient using fragment shader
     */
    private fun renderShadowGradient(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        startColor: FloatArray,
        endColor: FloatArray,
        intensity: Float
    ) {
        // Enable vertex attributes
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        
        // Bind buffers
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(
            aPosition,
            3,
            GLES30.GL_FLOAT,
            false,
            8 * 4, // 3 pos + 2 normal + 2 texcoord + 1 padding (in floats)
            vertexBuffer
        )
        
        texCoordBuffer.position(0)
        GLES30.glVertexAttribPointer(
            aTexCoord,
            2,
            GLES30.GL_FLOAT,
            false,
            8 * 4,
            texCoordBuffer
        )
        
        // Set uniform colors (average of start and end)
        val avgColor = floatArrayOf(
            (startColor[0] + endColor[0]) / 2f,
            (startColor[1] + endColor[1]) / 2f,
            (startColor[2] + endColor[2]) / 2f,
            (startColor[3] + endColor[3]) / 2f
        )
        GLES30.glUniform4fv(uShadowColor, 1, avgColor, 0)
        GLES30.glUniform1f(uIntensity, intensity)
        
        // Draw
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            indexCount,
            GLES30.GL_UNSIGNED_SHORT,
            indexBuffer
        )
        
        // Disable attributes
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }
    
    /**
     * Calculate shadow intensity based on curl factor
     */
    private fun calculateShadowIntensity(curlFactor: Float): Float {
        // Shadow is strongest at maximum curl
        return curlFactor.coerceIn(0f, 1f)
    }
    
    /**
     * Update shadow configuration
     */
    fun updateConfig(config: ShadowConfig) {
        this.config = config
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        isInitialized = false
    }
}

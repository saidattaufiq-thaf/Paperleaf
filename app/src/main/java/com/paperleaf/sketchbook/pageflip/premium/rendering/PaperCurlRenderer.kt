package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import com.paperleaf.sketchbook.pageflip.engine.ShaderManager
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class PaperCurlRenderer {

    companion object {
        private const val TAG = "PaperCurlRenderer"
        private val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private var basicProgram = -1
    private var paperProgram = -1
    private var backProgram = -1
    private var isInitialized = false

    private var basicPosLoc = -1
    private var basicTexCoordLoc = -1
    private var basicMVPMatrixLoc = -1
    private var basicTextureLoc = -1

    private var paperPosLoc = -1
    private var paperTexCoordLoc = -1
    private var paperMVPMatrixLoc = -1
    private var paperTextureLoc = -1
    private var paperNoiseLoc = -1
    private var paperRoughLoc = -1
    private var paperBrightLoc = -1

    private var backPosLoc = -1
    private var backNormalLoc = -1
    private var backTexCoordLoc = -1
    private var backMVPMatrixLoc = -1
    private var backModelMatrixLoc = -1
    private var backTextureLoc = -1
    private var backFrontTexLoc = -1
    private var backNoiseLoc = -1
    private var backLightDirLoc = -1
    private var backAmbientLoc = -1
    private var backDiffuseLoc = -1
    private var backSpecularLoc = -1
    private var backRoughLoc = -1
    private var backBrightLoc = -1
    private var backWarmthLoc = -1
    private var backTranslucencyLoc = -1
    private var backRimIntensityLoc = -1
    private var backAoIntensityLoc = -1

    private var noiseTextureId = 0

    fun initialize(shaderManager: ShaderManager) {
        basicProgram = shaderManager.basicProgram
        paperProgram = shaderManager.paperProgram
        backProgram = shaderManager.backProgram

        if (basicProgram > 0) {
            basicPosLoc = GLES30.glGetAttribLocation(basicProgram, "a_position")
            basicTexCoordLoc = shaderManager.basicTexCoordLoc
            basicMVPMatrixLoc = shaderManager.basicMVPMatrixLoc
            basicTextureLoc = shaderManager.basicTextureLoc
        }

        if (paperProgram > 0) {
            paperPosLoc = GLES30.glGetAttribLocation(paperProgram, "a_position")
            paperTexCoordLoc = shaderManager.paperTexCoordLoc
            paperMVPMatrixLoc = shaderManager.paperMVPMatrixLoc
            paperTextureLoc = shaderManager.paperTextureLoc
            paperNoiseLoc = shaderManager.paperNoiseTextureLoc
            paperRoughLoc = shaderManager.paperRoughnessLoc
            paperBrightLoc = shaderManager.paperBrightnessLoc
        }

        if (backProgram > 0) {
            backPosLoc = GLES30.glGetAttribLocation(backProgram, "a_position")
            backNormalLoc = GLES30.glGetAttribLocation(backProgram, "a_normal")
            backTexCoordLoc = GLES30.glGetAttribLocation(backProgram, "a_texCoord")
            backMVPMatrixLoc = shaderManager.backMVPMatrixLoc
            backModelMatrixLoc = shaderManager.backModelMatrixLoc
            backTextureLoc = shaderManager.backTextureLoc
            backFrontTexLoc = shaderManager.backFrontTextureLoc
            backNoiseLoc = shaderManager.backNoiseTextureLoc
            backLightDirLoc = shaderManager.backLightDirLoc
            backAmbientLoc = shaderManager.backAmbientLoc
            backDiffuseLoc = shaderManager.backDiffuseLoc
            backSpecularLoc = shaderManager.backSpecularLoc
            backRoughLoc = shaderManager.backRoughnessLoc
            backBrightLoc = shaderManager.backBrightnessLoc
            backWarmthLoc = shaderManager.backWarmthLoc
            backTranslucencyLoc = shaderManager.backTranslucencyLoc
            backRimIntensityLoc = shaderManager.backRimIntensityLoc
            backAoIntensityLoc = shaderManager.backAoIntensityLoc
        }

        isInitialized = true
        Log.d(TAG, "PaperCurlRenderer initialized")
    }

    fun setNoiseTexture(textureId: Int) {
        noiseTextureId = textureId
    }

    fun renderFront(
        mvpMatrix: FloatArray,
        textureId: Int,
        vertexBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        usePaperTexture: Boolean,
        roughness: Float,
        brightness: Float,
        gpuBuffer: GpuBuffer? = null
    ) {
        if (!isInitialized) return

        if (usePaperTexture && paperProgram > 0) {
            GLES30.glUseProgram(paperProgram)
            GLES30.glUniformMatrix4fv(paperMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES30.glUniform1i(paperTextureLoc, 0)
            GLES30.glUniform1i(paperNoiseLoc, 1)
            GLES30.glUniform1f(paperRoughLoc, roughness)
            GLES30.glUniform1f(paperBrightLoc, brightness)

            bindTexture(textureId, GLES30.GL_TEXTURE0)
            bindNoiseTexture()

            if (gpuBuffer != null && gpuBuffer.isInitialized()) {
                gpuBuffer.draw(indexCount)
            } else {
                setupInterleavedAttributes(paperPosLoc, paperTexCoordLoc, vertexBuffer)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
                disableAttributes(paperPosLoc, paperTexCoordLoc)
            }
        } else if (basicProgram > 0) {
            GLES30.glUseProgram(basicProgram)
            GLES30.glUniformMatrix4fv(basicMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES30.glUniform1i(basicTextureLoc, 0)

            bindTexture(textureId, GLES30.GL_TEXTURE0)

            if (gpuBuffer != null && gpuBuffer.isInitialized()) {
                gpuBuffer.draw(indexCount)
            } else {
                setupInterleavedAttributes(basicPosLoc, basicTexCoordLoc, vertexBuffer)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
                disableAttributes(basicPosLoc, basicTexCoordLoc)
            }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
    }

    fun renderBack(
        mvpMatrix: FloatArray,
        frontTextureId: Int,
        backTextureId: Int,
        vertexBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        noiseTextureId: Int = 0,
        lightDirection: FloatArray = floatArrayOf(0.3f, 0.5f, 0.8f),
        ambientColor: FloatArray = floatArrayOf(0.35f, 0.35f, 0.40f),
        diffuseColor: FloatArray = floatArrayOf(0.75f, 0.73f, 0.70f),
        specularColor: FloatArray = floatArrayOf(0.02f, 0.02f, 0.02f),
        warmth: Float = 0.3f,
        roughness: Float = 0.6f,
        brightness: Float = 0.92f,
        translucency: Float = 0.15f,
        rimIntensity: Float = 0.08f,
        aoIntensity: Float = 0.30f,
        modelMatrix: FloatArray = identityMatrix,
        gpuBuffer: GpuBuffer? = null
    ) {
        if (!isInitialized) return

        if (backProgram > 0) {
            GLES30.glUseProgram(backProgram)
            GLES30.glUniformMatrix4fv(backMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(backModelMatrixLoc, 1, false, modelMatrix, 0)

            bindTexture(backTextureId, GLES30.GL_TEXTURE0)
            GLES30.glUniform1i(backTextureLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTextureId)
            GLES30.glUniform1i(backFrontTexLoc, 1)

            val noiseTex = if (noiseTextureId > 0) noiseTextureId else this.noiseTextureId
            if (noiseTex > 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
                GLES30.glUniform1i(backNoiseLoc, 2)
            }

            GLES30.glUniform3fv(backLightDirLoc, 1, lightDirection, 0)
            GLES30.glUniform3fv(backAmbientLoc, 1, ambientColor, 0)
            GLES30.glUniform3fv(backDiffuseLoc, 1, diffuseColor, 0)
            if (backSpecularLoc > 0) GLES30.glUniform3fv(backSpecularLoc, 1, specularColor, 0)
            GLES30.glUniform1f(backRoughLoc, roughness)
            GLES30.glUniform1f(backBrightLoc, brightness)
            GLES30.glUniform1f(backWarmthLoc, warmth)
            GLES30.glUniform1f(backTranslucencyLoc, translucency)
            if (backRimIntensityLoc > 0) GLES30.glUniform1f(backRimIntensityLoc, rimIntensity)
            if (backAoIntensityLoc > 0) GLES30.glUniform1f(backAoIntensityLoc, aoIntensity)

            if (gpuBuffer != null && gpuBuffer.isInitialized()) {
                gpuBuffer.draw(indexCount)
            } else {
                setupAllAttributes(backPosLoc, backNormalLoc, backTexCoordLoc, vertexBuffer)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
                disableAllAttributes(backPosLoc, backNormalLoc, backTexCoordLoc)
            }
        } else if (basicProgram > 0) {
            GLES30.glUseProgram(basicProgram)
            GLES30.glUniformMatrix4fv(basicMVPMatrixLoc, 1, false, mvpMatrix, 0)
            GLES30.glUniform1i(basicTextureLoc, 0)

            bindTexture(backTextureId, GLES30.GL_TEXTURE0)

            if (gpuBuffer != null && gpuBuffer.isInitialized()) {
                gpuBuffer.draw(indexCount)
            } else {
                setupInterleavedAttributes(basicPosLoc, basicTexCoordLoc, vertexBuffer)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
                disableAttributes(basicPosLoc, basicTexCoordLoc)
            }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
    }

    private fun bindTexture(textureId: Int, unit: Int) {
        GLES30.glActiveTexture(unit)
        if (textureId > 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }

    private fun bindNoiseTexture() {
        if (noiseTextureId > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTextureId)
        }
    }

    private fun setupInterleavedAttributes(posLoc: Int, texLoc: Int, buffer: FloatBuffer) {
        buffer.position(GpuBuffer.POS_OFFSET)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)

        buffer.position(GpuBuffer.TEX_OFFSET)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)
    }

    private fun setupAllAttributes(posLoc: Int, normalLoc: Int, texLoc: Int, buffer: FloatBuffer) {
        buffer.position(GpuBuffer.POS_OFFSET)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)

        buffer.position(GpuBuffer.NORMAL_OFFSET)
        GLES30.glEnableVertexAttribArray(normalLoc)
        GLES30.glVertexAttribPointer(normalLoc, 3, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)

        buffer.position(GpuBuffer.TEX_OFFSET)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)
    }

    private fun disableAttributes(posLoc: Int, texLoc: Int) {
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
    }

    private fun disableAllAttributes(posLoc: Int, normalLoc: Int, texLoc: Int) {
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(normalLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
    }

    fun releaseGl() {
        basicProgram = -1; paperProgram = -1; backProgram = -1
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized
}

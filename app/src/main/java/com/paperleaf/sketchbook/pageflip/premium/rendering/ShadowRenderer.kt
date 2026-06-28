package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import com.paperleaf.sketchbook.pageflip.premium.config.ShadowConfig
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class ShadowRenderer {

    companion object {
        private const val TAG = "ShadowRenderer"

        private const val SHADOW_VERTEX_SRC = """
#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_texCoord;

uniform mat4 u_MVPMatrix;
uniform mat4 u_ModelMatrix;

out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_texCoord;
out float v_height;

void main() {
    vec4 worldPos = u_ModelMatrix * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_normal = normalize(mat3(u_ModelMatrix) * a_normal);
    v_texCoord = a_texCoord;
    v_height = a_position.z;
    gl_Position = u_MVPMatrix * vec4(a_position, 1.0);
}
"""

        private val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val EDGE_CONTACT_COLOR = floatArrayOf(0.15f, 0.15f, 0.15f)
        private val EDGE_CURL_COLOR = floatArrayOf(0.25f, 0.20f, 0.15f)
        private val EDGE_SPINE_COLOR = floatArrayOf(0.15f, 0.15f, 0.15f)
        private val EDGE_AMBIENT_COLOR = floatArrayOf(0.08f, 0.08f, 0.08f)

        private val BASE_CONTACT_COLOR = floatArrayOf(0.06f, 0.06f, 0.06f)
        private val BASE_CURL_COLOR = floatArrayOf(0.10f, 0.08f, 0.06f)
        private val BASE_SPINE_COLOR = floatArrayOf(0.08f, 0.08f, 0.08f)
        private val BASE_AMBIENT_COLOR = floatArrayOf(0.04f, 0.04f, 0.05f)

        private const val SHADOW_FRAGMENT_SRC = """
#version 300 es
precision highp float;

in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_texCoord;
in float v_height;

uniform vec3 u_contactShadowColor;
uniform vec3 u_curlShadowColor;
uniform vec3 u_spineShadowColor;
uniform vec3 u_ambientShadowColor;
uniform vec3 u_lightDirection;
uniform float u_curlAngle;
uniform float u_curlRadius;
uniform float u_pageHeight;
uniform float u_shadowStrength;
uniform float u_contactDistance;
uniform float u_spineWidth;

out vec4 fragColor;

void main() {
    vec3 N = normalize(v_normal);
    vec3 L = normalize(u_lightDirection);
    vec3 up = vec3(0.0, 0.0, 1.0);
    float h = abs(v_height);

    float contactShadow = exp(-h / max(u_contactDistance, 0.001));
    contactShadow = clamp(contactShadow, 0.0, 1.0);

    float curlCrease = 1.0 - abs(dot(N, up));
    float foldCenter = abs(v_texCoord.x - 0.5) * 2.0;
    float curlShadow = curlCrease * smoothstep(0.0, 0.25, foldCenter);
    float curlAngleNorm = clamp(u_curlAngle / 3.14159, 0.0, 1.0);
    curlShadow *= curlAngleNorm * 0.7;
    curlShadow = clamp(curlShadow, 0.0, 1.0);

    float spineShadow = exp(-v_texCoord.x * u_spineWidth) * 0.45;

    float NdotL = dot(N, L);
    float selfShadow = smoothstep(-0.4, 0.6, NdotL);
    selfShadow = 1.0 - selfShadow;
    float heightFactor = smoothstep(0.0, u_pageHeight * 0.3, h);
    selfShadow *= heightFactor * 0.55;
    selfShadow = clamp(selfShadow, 0.0, 1.0);

    float NdotUp = dot(N, up);
    float ao = 1.0 - NdotUp * 0.5 + 0.5;
    ao = clamp(ao, 0.0, 1.0);
    ao = 1.0 - ao * 0.25;

    float rim = 1.0 - abs(dot(N, L));
    rim = pow(rim, 3.0) * 0.15;

    float totalShadow = max(
        contactShadow,
        max(curlShadow, max(spineShadow, max(selfShadow, 1.0 - ao)))
    );
    totalShadow = clamp(totalShadow + rim, 0.0, 1.0);
    totalShadow = smoothstep(0.0, 1.0, totalShadow);

    vec3 shadowColor = u_ambientShadowColor;
    shadowColor = mix(shadowColor, u_contactShadowColor, contactShadow);
    shadowColor = mix(shadowColor, u_curlShadowColor, curlShadow);
    shadowColor = mix(shadowColor, u_spineShadowColor, spineShadow);

    float alpha = totalShadow * u_shadowStrength;

    fragColor = vec4(shadowColor * alpha, alpha);
}
"""
    }

    private var config = ShadowConfig()
    private var advProgram = -1
    private var isInitialized = false

    private var adv_uMVPMatrix = -1
    private var adv_uModelMatrix = -1
    private var adv_uContactColor = -1
    private var adv_uCurlColor = -1
    private var adv_uSpineColor = -1
    private var adv_uAmbientColor = -1
    private var adv_uLightDir = -1
    private var adv_uCurlAngle = -1
    private var adv_uCurlRadius = -1
    private var adv_uPageHeight = -1
    private var adv_uStrength = -1
    private var adv_uContactDist = -1
    private var adv_uSpineWidth = -1

    private val shadowMvpCopy = FloatArray(16)
    private val reusableAvgColor = FloatArray(4)

    fun initialize(shaderManager: com.paperleaf.sketchbook.pageflip.engine.ShaderManager) {
        compileAdvancedShader()
        isInitialized = true
        Log.d(TAG, "ShadowRenderer initialized")
    }

    private fun compileAdvancedShader() {
        advProgram = ShaderCompiler.createProgram(SHADOW_VERTEX_SRC, SHADOW_FRAGMENT_SRC, TAG)
        if (advProgram < 0) {
            Log.e(TAG, "Advanced shadow program compilation failed")
            return
        }

        adv_uMVPMatrix = GLES30.glGetUniformLocation(advProgram, "u_MVPMatrix")
        adv_uModelMatrix = GLES30.glGetUniformLocation(advProgram, "u_ModelMatrix")
        adv_uContactColor = GLES30.glGetUniformLocation(advProgram, "u_contactShadowColor")
        adv_uCurlColor = GLES30.glGetUniformLocation(advProgram, "u_curlShadowColor")
        adv_uSpineColor = GLES30.glGetUniformLocation(advProgram, "u_spineShadowColor")
        adv_uAmbientColor = GLES30.glGetUniformLocation(advProgram, "u_ambientShadowColor")
        adv_uLightDir = GLES30.glGetUniformLocation(advProgram, "u_lightDirection")
        adv_uCurlAngle = GLES30.glGetUniformLocation(advProgram, "u_curlAngle")
        adv_uCurlRadius = GLES30.glGetUniformLocation(advProgram, "u_curlRadius")
        adv_uPageHeight = GLES30.glGetUniformLocation(advProgram, "u_pageHeight")
        adv_uStrength = GLES30.glGetUniformLocation(advProgram, "u_shadowStrength")
        adv_uContactDist = GLES30.glGetUniformLocation(advProgram, "u_contactDistance")
        adv_uSpineWidth = GLES30.glGetUniformLocation(advProgram, "u_spineWidth")

        Log.d(TAG, "Advanced shadow shader compiled")
    }

    fun renderShadow(
        mvpMatrix: FloatArray,
        modelMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float,
        curlAngle: Float,
        curlRadius: Float,
        pageHeight: Float,
        lightDirection: FloatArray = config.lightDirection,
        gpuBuffer: GpuBuffer? = null
    ) {
        if (!isInitialized) return

        if (advProgram > 0) {
            GLES30.glUseProgram(advProgram)
            GLES30.glUniformMatrix4fv(adv_uMVPMatrix, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(adv_uModelMatrix, 1, false, modelMatrix, 0)
            GLES30.glUniform3fv(adv_uContactColor, 1, config.contactShadowColor, 0)
            GLES30.glUniform3fv(adv_uCurlColor, 1, config.curlShadowColor, 0)
            GLES30.glUniform3fv(adv_uSpineColor, 1, config.spineShadowColor, 0)
            GLES30.glUniform3fv(adv_uAmbientColor, 1, config.ambientShadowColor, 0)
            GLES30.glUniform3fv(adv_uLightDir, 1, lightDirection, 0)
            GLES30.glUniform1f(adv_uCurlAngle, curlAngle)
            GLES30.glUniform1f(adv_uCurlRadius, curlRadius)
            GLES30.glUniform1f(adv_uPageHeight, pageHeight)
            GLES30.glUniform1f(adv_uStrength, curlFactor.coerceIn(0f, 1f) * config.maxIntensity)
            GLES30.glUniform1f(adv_uContactDist, config.contactDistance)
            GLES30.glUniform1f(adv_uSpineWidth, config.spineWidth)

            drawWithBuffer(gpuBuffer, vertexBuffer, indexBuffer, indexCount)
            GLES30.glUseProgram(0)
            return
        }
    }

    fun renderEdgeShadow(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float,
        gpuBuffer: GpuBuffer? = null
    ) {
        if (advProgram > 0) {
            GLES30.glUseProgram(advProgram)
            GLES30.glUniformMatrix4fv(adv_uMVPMatrix, 1, false, mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(adv_uModelMatrix, 1, false, identityMatrix, 0)
            GLES30.glUniform3fv(adv_uContactColor, 1, EDGE_CONTACT_COLOR, 0)
            GLES30.glUniform3fv(adv_uCurlColor, 1, EDGE_CURL_COLOR, 0)
            GLES30.glUniform3fv(adv_uSpineColor, 1, EDGE_SPINE_COLOR, 0)
            GLES30.glUniform3fv(adv_uAmbientColor, 1, EDGE_AMBIENT_COLOR, 0)
            GLES30.glUniform3fv(adv_uLightDir, 1, config.lightDirection, 0)
            GLES30.glUniform1f(adv_uCurlAngle, curlFactor * 3.14159f)
            GLES30.glUniform1f(adv_uCurlRadius, 0.5f)
            GLES30.glUniform1f(adv_uPageHeight, 2.6f)
            GLES30.glUniform1f(adv_uStrength, curlFactor.coerceIn(0f, 1f) * 0.6f)
            GLES30.glUniform1f(adv_uContactDist, 0.008f)
            GLES30.glUniform1f(adv_uSpineWidth, 8.0f)

            drawWithBuffer(gpuBuffer, vertexBuffer, indexBuffer, indexCount)
            GLES30.glUseProgram(0)
            return
        }
    }

    fun renderBaseShadow(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        curlFactor: Float,
        offsetZ: Float = -0.001f,
        gpuBuffer: GpuBuffer? = null
    ) {
        System.arraycopy(mvpMatrix, 0, shadowMvpCopy, 0, 16)
        shadowMvpCopy[14] += offsetZ

        if (advProgram > 0) {
            GLES30.glUseProgram(advProgram)
            GLES30.glUniformMatrix4fv(adv_uMVPMatrix, 1, false, shadowMvpCopy, 0)
            GLES30.glUniformMatrix4fv(adv_uModelMatrix, 1, false, identityMatrix, 0)
            GLES30.glUniform3fv(adv_uContactColor, 1, BASE_CONTACT_COLOR, 0)
            GLES30.glUniform3fv(adv_uCurlColor, 1, BASE_CURL_COLOR, 0)
            GLES30.glUniform3fv(adv_uSpineColor, 1, BASE_SPINE_COLOR, 0)
            GLES30.glUniform3fv(adv_uAmbientColor, 1, BASE_AMBIENT_COLOR, 0)
            GLES30.glUniform3fv(adv_uLightDir, 1, config.lightDirection, 0)
            GLES30.glUniform1f(adv_uCurlAngle, curlFactor * 3.14159f)
            GLES30.glUniform1f(adv_uCurlRadius, 0.5f)
            GLES30.glUniform1f(adv_uPageHeight, 2.6f)
            GLES30.glUniform1f(adv_uStrength, curlFactor.coerceIn(0f, 1f) * config.maxIntensity * 0.7f)
            GLES30.glUniform1f(adv_uContactDist, 0.003f)
            GLES30.glUniform1f(adv_uSpineWidth, 6.0f)

            drawWithBuffer(gpuBuffer, vertexBuffer, indexBuffer, indexCount)
            GLES30.glUseProgram(0)
            return
        }
    }

    private fun drawWithBuffer(
        gpuBuffer: GpuBuffer?,
        vertexBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int
    ) {
        if (gpuBuffer != null && gpuBuffer.isInitialized()) {
            gpuBuffer.draw(indexCount)
        } else {
            bindVertexAttributes(vertexBuffer)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
            disableVertexAttributes()
        }
    }

    private fun bindVertexAttributes(buffer: FloatBuffer) {
        buffer.position(GpuBuffer.POS_OFFSET)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)

        buffer.position(GpuBuffer.NORMAL_OFFSET)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)

        buffer.position(GpuBuffer.TEX_OFFSET)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, GpuBuffer.STRIDE_BYTES, buffer)
    }

    private fun disableVertexAttributes() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
    }

    fun updateConfig(config: ShadowConfig) {
        this.config = config
    }

    fun releaseGl() {
        advProgram = -1
        isInitialized = false
    }

    fun cleanup() {
        if (advProgram > 0) {
            GLES30.glDeleteProgram(advProgram)
        }
        releaseGl()
    }
}

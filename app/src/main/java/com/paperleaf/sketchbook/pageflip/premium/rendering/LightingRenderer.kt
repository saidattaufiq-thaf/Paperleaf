package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import com.paperleaf.sketchbook.pageflip.premium.config.PaperLightingConfig
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sqrt

class LightingRenderer {

    companion object {
        private const val TAG = "LightingRenderer"

        private val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val PAPER_VERTEX_SHADER = """
#version 300 es
precision highp float;

layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_texCoord;

uniform mat4 u_MVPMatrix;
uniform mat4 u_ModelMatrix;

out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_texCoord;

void main() {
    vec4 worldPos = u_ModelMatrix * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_normal = normalize(mat3(u_ModelMatrix) * a_normal);
    v_texCoord = a_texCoord;
    gl_Position = u_MVPMatrix * vec4(a_position, 1.0);
}
"""

        private val PAPER_FRAGMENT_SHADER = """
#version 300 es
precision highp float;

in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_texCoord;

uniform sampler2D u_texture;
uniform vec3 u_lightDirection;
uniform vec3 u_viewPosition;
uniform vec3 u_ambientColor;
uniform vec3 u_diffuseColor;
uniform vec3 u_specularColor;
uniform vec3 u_pageColor;
uniform float u_roughness;
uniform float u_brightness;
uniform float u_rimIntensity;
uniform float u_aoIntensity;
uniform float u_backLightIntensity;
uniform float u_translucency;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fiber(vec2 uv) {
    float f = 0.0;
    vec2 u = uv * 40.0;
    float a0 = 0.6;
    vec2 dir0 = vec2(sin(a0), cos(a0));
    float d0 = abs(dot(u, dir0));
    f += exp(-d0 * 6.0) * 0.08;
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 2.094 + 0.785;
        vec2 dir = vec2(sin(a), cos(a));
        float d = abs(dot(u, dir));
        f += exp(-d * 10.0) * 0.03;
    }
    vec2 uf = uv * 120.0;
    float af = 0.8;
    vec2 dirf = vec2(sin(af), cos(af));
    float df = abs(dot(uf, dirf));
    f += exp(-df * 12.0) * 0.02;
    return clamp(f, 0.0, 1.0);
}

void main() {
    vec4 texColor = texture(u_texture, v_texCoord);

    float n = vnoise(v_texCoord * 15.0);
    n += vnoise(v_texCoord * 30.0) * 0.5;
    n += vnoise(v_texCoord * 60.0) * 0.25;
    n *= 0.571;
    float f = fiber(v_texCoord);
    float surfaceDetail = mix(n, f, 0.2);
    float paperEffect = mix(1.0, surfaceDetail, u_roughness);
    float roughnessMod = mix(u_roughness, u_roughness * (0.7 + 0.3 * n), 0.3);

    vec3 baseColor = texColor.rgb * u_brightness * u_pageColor * paperEffect;

    vec3 N = normalize(v_normal);
    vec3 L = normalize(u_lightDirection);
    vec3 V = normalize(u_viewPosition - v_worldPos);
    vec3 H = normalize(L + V);

    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.001);
    float NdotH = max(dot(N, H), 0.0);

    float NdotUp = N.z;
    float h = abs(v_worldPos.z);

    float wrap = 0.0 + roughnessMod * 0.5;
    float softNdotL = max((NdotL + wrap) / (1.0 + wrap), 0.0);
    float diffuse = mix(NdotL, softNdotL * softNdotL, roughnessMod * 0.5);

    float spec = pow(NdotH, 2.0 + (1.0 - roughnessMod) * 10.0) * 0.02;
    spec = mix(spec, 0.0, roughnessMod * 0.8);

    float rim = pow(1.0 - NdotV, 3.0) * u_rimIntensity * (0.5 + 0.5 * NdotL);

    float aoHeight = 1.0 - smoothstep(0.0, 0.08, h) * 0.3;
    float aoNormal = NdotUp * 0.5 + 0.5;
    float ao = aoNormal * aoHeight;
    ao = 1.0 - (1.0 - ao) * u_aoIntensity;

    float creaseShadow = 1.0 - abs(NdotUp);
    creaseShadow = smoothstep(0.0, 0.8, creaseShadow) * 0.2;
    float selfShadow = 1.0 - creaseShadow;

    float contactShadow = 1.0 - exp(-h / 0.008) * 0.35;

    float backNdotL = max(dot(-N, L), 0.0);
    float backLight = backNdotL * u_backLightIntensity;

    float translucency = pow(1.0 - NdotV, 2.0) * u_translucency * backNdotL;
    translucency *= 1.0 - smoothstep(0.0, 0.05, h);

    vec3 ambient = u_ambientColor * ao;
    vec3 diffuseTerm = baseColor * u_diffuseColor * diffuse * selfShadow * contactShadow;
    vec3 specTerm = u_specularColor * spec;
    vec3 rimTerm = vec3(rim) * baseColor * u_diffuseColor;
    vec3 backLightTerm = u_pageColor * u_diffuseColor * (backLight + translucency * 1.5);

    vec3 result = ambient * baseColor + diffuseTerm + specTerm + rimTerm + backLightTerm;

    fragColor = vec4(result, texColor.a);
}
"""
    }

    var config = PaperLightingConfig()
        private set
    private var paperProgram = -1
    private var isInitialized = false

    private var uMVPMatrix = -1
    private var uModelMatrix = -1
    private var uTexture = -1
    private var uLightDir = -1
    private var uViewPos = -1
    private var uAmbient = -1
    private var uDiffuse = -1
    private var uSpecular = -1
    private var uPageColor = -1
    private var uRoughness = -1
    private var uBrightness = -1
    private var uRimIntensity = -1
    private var uAoIntensity = -1
    private var uBackLightIntensity = -1
    private var uTranslucency = -1

    private val normalizedLightDir = FloatArray(3)

    fun initialize(shaderManager: com.paperleaf.sketchbook.pageflip.engine.ShaderManager) {
        compilePaperShaders()
        isInitialized = true
        Log.d(TAG, "LightingRenderer initialized")
    }

    private fun compilePaperShaders() {
        paperProgram = ShaderCompiler.createProgram(PAPER_VERTEX_SHADER, PAPER_FRAGMENT_SHADER, TAG)
        if (paperProgram < 0) {
            Log.e(TAG, "Paper lighting program compilation failed")
            return
        }

        uMVPMatrix = GLES30.glGetUniformLocation(paperProgram, "u_MVPMatrix")
        uModelMatrix = GLES30.glGetUniformLocation(paperProgram, "u_ModelMatrix")
        uTexture = GLES30.glGetUniformLocation(paperProgram, "u_texture")
        uLightDir = GLES30.glGetUniformLocation(paperProgram, "u_lightDirection")
        uViewPos = GLES30.glGetUniformLocation(paperProgram, "u_viewPosition")
        uAmbient = GLES30.glGetUniformLocation(paperProgram, "u_ambientColor")
        uDiffuse = GLES30.glGetUniformLocation(paperProgram, "u_diffuseColor")
        uSpecular = GLES30.glGetUniformLocation(paperProgram, "u_specularColor")
        uPageColor = GLES30.glGetUniformLocation(paperProgram, "u_pageColor")
        uRoughness = GLES30.glGetUniformLocation(paperProgram, "u_roughness")
        uBrightness = GLES30.glGetUniformLocation(paperProgram, "u_brightness")
        uRimIntensity = GLES30.glGetUniformLocation(paperProgram, "u_rimIntensity")
        uAoIntensity = GLES30.glGetUniformLocation(paperProgram, "u_aoIntensity")
        uBackLightIntensity = GLES30.glGetUniformLocation(paperProgram, "u_backLightIntensity")
        uTranslucency = GLES30.glGetUniformLocation(paperProgram, "u_translucency")

        Log.d(TAG, "Paper lighting shader compiled")
    }

    fun render(
        mvpMatrix: FloatArray,
        textureId: Int,
        vertexBuffer: FloatBuffer,
        texCoordBuffer: FloatBuffer,
        normalBuffer: FloatBuffer,
        indexBuffer: ShortBuffer,
        indexCount: Int,
        modelMatrix: FloatArray = identityMatrix,
        gpuBuffer: GpuBuffer? = null
    ) {
        Log.d("TRACE_Lighting", "render: tex=$textureId indexCount=$indexCount useGpuBuffer=${gpuBuffer != null && gpuBuffer.isInitialized()} program=$paperProgram")
        if (!isInitialized || paperProgram <= 0) {
            Log.d("TRACE_Lighting", "render SKIPPED: isInitialized=$isInitialized program=$paperProgram")
            return
        }

        GLES30.glUseProgram(paperProgram)
        GLES30.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uModelMatrix, 1, false, modelMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTexture, 0)

        normalizeDirection(config.lightDirection, normalizedLightDir)
        GLES30.glUniform3fv(uLightDir, 1, normalizedLightDir, 0)
        GLES30.glUniform3fv(uViewPos, 1, config.viewPosition, 0)
        GLES30.glUniform3fv(uAmbient, 1, config.ambientColor, 0)
        GLES30.glUniform3fv(uDiffuse, 1, config.diffuseColor, 0)
        GLES30.glUniform3fv(uSpecular, 1, config.specularColor, 0)
        GLES30.glUniform3fv(uPageColor, 1, config.pageColor, 0)
        GLES30.glUniform1f(uRoughness, config.roughness)
        GLES30.glUniform1f(uBrightness, config.brightness)
        GLES30.glUniform1f(uRimIntensity, config.rimIntensity)
        GLES30.glUniform1f(uAoIntensity, config.aoIntensity)
        GLES30.glUniform1f(uBackLightIntensity, config.backLightIntensity)
        GLES30.glUniform1f(uTranslucency, config.translucency)

        if (gpuBuffer != null && gpuBuffer.isInitialized()) {
            gpuBuffer.draw(indexCount)
        } else {
            bindVertexAttributes(vertexBuffer)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
            disableVertexAttributes()
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
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

    private fun normalizeDirection(src: FloatArray, dst: FloatArray) {
        val len = sqrt(src[0] * src[0] + src[1] * src[1] + src[2] * src[2])
        if (len < 0.0001f) {
            dst[0] = 0f; dst[1] = 1f; dst[2] = 0f
            return
        }
        dst[0] = src[0] / len
        dst[1] = src[1] / len
        dst[2] = src[2] / len
    }

    fun updateConfig(config: PaperLightingConfig) {
        this.config = config
    }

    fun releaseGl() {
        paperProgram = -1
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized && paperProgram > 0

    fun cleanup() {
        if (paperProgram > 0) {
            GLES30.glDeleteProgram(paperProgram)
        }
        releaseGl()
    }
}

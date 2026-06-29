package com.paperleaf.sketchbook.pageflip.engine

import android.opengl.GLES30
import android.util.Log

class ShaderManager {

    companion object {
        private const val TAG = "ShaderManager"

        val BASIC_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec3 a_position;
            layout(location = 1) in vec2 a_texCoord;

            uniform mat4 u_MVPMatrix;

            out vec2 v_texCoord;

            void main() {
                gl_Position = u_MVPMatrix * vec4(a_position, 1.0);
                v_texCoord = a_texCoord;
            }
        """.trimIndent()

        val BASIC_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform sampler2D u_texture;

            in vec2 v_texCoord;
            out vec4 fragColor;

            void main() {
                fragColor = texture(u_texture, v_texCoord);
            }
        """.trimIndent()

        val PAPER_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform sampler2D u_texture;
            uniform sampler2D u_noiseTexture;
            uniform float u_roughness;
            uniform float u_brightness;

            in vec2 v_texCoord;
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
                vec2 u = uv * 50.0;
                for (int i = 0; i < 5; i++) {
                    float a = float(i) * 2.094 + 0.785;
                    vec2 dir = vec2(sin(a), cos(a));
                    float d = abs(dot(u, dir));
                    f += exp(-d * 8.0) * 0.06;
                }
                return clamp(f, 0.0, 1.0);
            }

            void main() {
                vec4 texColor = texture(u_texture, v_texCoord);

                float n = vnoise(v_texCoord * 15.0);
                n += vnoise(v_texCoord * 30.0) * 0.5;
                n += vnoise(v_texCoord * 60.0) * 0.25;
                n *= 0.571;

                float texNoise = texture(u_noiseTexture, v_texCoord * 6.0).r;
                float grain = mix(n, texNoise, 0.4);

                float f = fiber(v_texCoord);

                float surfaceDetail = mix(grain, f, 0.15);
                float paperEffect = mix(1.0, surfaceDetail, u_roughness);

                vec3 result = texColor.rgb * paperEffect * u_brightness;
                fragColor = vec4(result, texColor.a);
            }
        """.trimIndent()

        val BACK_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec3 a_position;
            layout(location = 1) in vec3 a_normal;
            layout(location = 2) in vec2 a_texCoord;

            uniform mat4 u_MVPMatrix;
            uniform mat4 u_ModelMatrix;

            out vec2 v_texCoord;
            out vec3 v_normal;
            out vec3 v_fragPos;
            out float v_height;

            void main() {
                vec4 worldPos = u_ModelMatrix * vec4(a_position, 1.0);
                v_fragPos = worldPos.xyz;
                v_normal = normalize(mat3(u_ModelMatrix) * a_normal);
                v_texCoord = a_texCoord;
                v_height = a_position.z;
                gl_Position = u_MVPMatrix * vec4(a_position, 1.0);
            }
        """.trimIndent()

        val BACK_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform sampler2D u_texture;
            uniform sampler2D u_frontTexture;
            uniform vec3 u_lightDir;
            uniform vec3 u_ambient;
            uniform vec3 u_diffuse;
            uniform vec3 u_specular;
            uniform float u_roughness;
            uniform float u_brightness;
            uniform float u_warmth;
            uniform float u_translucency;
            uniform float u_rimIntensity;
            uniform float u_aoIntensity;

            in vec2 v_texCoord;
            in vec3 v_normal;
            in vec3 v_fragPos;
            in float v_height;
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
                vec4 backTex = texture(u_texture, v_texCoord);
                vec4 frontTex = texture(u_frontTexture, v_texCoord);

                vec3 warmShift = vec3(1.0, 0.91, 0.82);
                vec3 warmed = mix(backTex.rgb, backTex.rgb * warmShift, u_warmth);

                float n = vnoise(v_texCoord * 12.0);
                n += vnoise(v_texCoord * 24.0) * 0.5;
                n *= 0.667;
                float f = fiber(v_texCoord);
                float surfaceDetail = mix(n, f, 0.2);
                float roughness = mix(1.0, surfaceDetail, u_roughness);
                float roughnessMod = mix(u_roughness, u_roughness * 0.7, 0.3);

                vec3 N = normalize(v_normal);
                vec3 L = normalize(u_lightDir);
                vec3 V = normalize(-v_fragPos);
                vec3 H = normalize(L + V);
                float h = abs(v_height);

                float NdotL = max(dot(N, L), 0.0);
                float NdotV = max(dot(N, V), 0.001);
                float NdotH = max(dot(N, H), 0.0);
                float NdotUp = N.z;

                float wrap = 0.0 + roughnessMod * 0.5;
                float softNdotL = max((NdotL + wrap) / (1.0 + wrap), 0.0);
                float diff = mix(NdotL, softNdotL * softNdotL, roughnessMod * 0.5);
                diff = diff * 0.7 + 0.3;

                float spec = pow(NdotH, 2.0 + (1.0 - roughnessMod) * 8.0) * 0.015;
                spec = mix(spec, 0.0, roughnessMod * 0.8);

                float rim = pow(1.0 - NdotV, 3.0) * u_rimIntensity * (0.5 + 0.5 * NdotL);

                float aoHeight = 1.0 - smoothstep(0.0, 0.1, h) * 0.25;
                float aoNormal = NdotUp * 0.5 + 0.5;
                float ao = aoNormal * aoHeight;
                ao = 1.0 - (1.0 - ao) * u_aoIntensity;

                float creaseShadow = 1.0 - abs(NdotUp);
                creaseShadow = smoothstep(0.0, 0.8, creaseShadow) * 0.15;
                float selfShadow = 1.0 - creaseShadow;

                float contactShadow = 1.0 - exp(-h / 0.01) * 0.3;

                vec3 lit = warmed * (u_ambient * ao + diff * u_diffuse * 0.8) * u_brightness * roughness * selfShadow * contactShadow;
                vec3 specColor = u_specular * spec;
                vec3 rimColor = vec3(rim) * warmed * 0.5;

                float thickness = 1.0 - smoothstep(0.0, 0.04, h);
                float translucency = u_translucency * thickness * max(dot(-N, L), 0.0);

                vec3 translucent = mix(lit + specColor + rimColor, frontTex.rgb, translucency);

                fragColor = vec4(translucent, 1.0);
            }
        """.trimIndent()

        private const val VERTEX_SHADER_BASIC = "basic_vertex.glsl"
        private const val FRAGMENT_SHADER_BASIC = "basic_fragment.glsl"
        private const val FRAGMENT_SHADER_PAPER = "paper_fragment.glsl"
        private const val VERTEX_SHADER_BACK = "back_vertex.glsl"
        private const val FRAGMENT_SHADER_BACK = "back_fragment.glsl"
    }

    var basicProgram: Int = -1
    var paperProgram: Int = -1
    var backProgram: Int = -1

    var basicMVPMatrixLoc: Int = -1
    var basicTexCoordLoc: Int = -1
    var basicTextureLoc: Int = -1

    var paperMVPMatrixLoc: Int = -1
    var paperTexCoordLoc: Int = -1
    var paperTextureLoc: Int = -1
    var paperNoiseTextureLoc: Int = -1
    var paperRoughnessLoc: Int = -1
    var paperBrightnessLoc: Int = -1

    var backMVPMatrixLoc: Int = -1
    var backModelMatrixLoc: Int = -1
    var backTextureLoc: Int = -1
    var backFrontTextureLoc: Int = -1
    var backNoiseTextureLoc: Int = -1
    var backLightDirLoc: Int = -1
    var backAmbientLoc: Int = -1
    var backDiffuseLoc: Int = -1
    var backSpecularLoc: Int = -1
    var backRoughnessLoc: Int = -1
    var backBrightnessLoc: Int = -1
    var backWarmthLoc: Int = -1
    var backTranslucencyLoc: Int = -1
    var backRimIntensityLoc: Int = -1
    var backAoIntensityLoc: Int = -1

    private var isInitialized = false

    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "ShaderManager already initialized")
            return
        }

        basicProgram = createProgram(VERTEX_SHADER_BASIC, FRAGMENT_SHADER_BASIC)
        paperProgram = createProgram(VERTEX_SHADER_BASIC, FRAGMENT_SHADER_PAPER)
        backProgram = createProgram(VERTEX_SHADER_BACK, FRAGMENT_SHADER_BACK)

        getUniformLocations()
        isInitialized = true

        val count = listOf(basicProgram, paperProgram, backProgram).count { it > 0 }
        Log.i(TAG, "Shader programs initialized: $count/3 succeeded")
    }

    private fun getUniformLocations() {
        if (basicProgram > 0) {
            basicMVPMatrixLoc = GLES30.glGetUniformLocation(basicProgram, "u_MVPMatrix")
            basicTexCoordLoc = GLES30.glGetAttribLocation(basicProgram, "a_texCoord")
            basicTextureLoc = GLES30.glGetUniformLocation(basicProgram, "u_texture")
        }

        if (paperProgram > 0) {
            paperMVPMatrixLoc = GLES30.glGetUniformLocation(paperProgram, "u_MVPMatrix")
            paperTexCoordLoc = GLES30.glGetAttribLocation(paperProgram, "a_texCoord")
            paperTextureLoc = GLES30.glGetUniformLocation(paperProgram, "u_texture")
            paperNoiseTextureLoc = GLES30.glGetUniformLocation(paperProgram, "u_noiseTexture")
            paperRoughnessLoc = GLES30.glGetUniformLocation(paperProgram, "u_roughness")
            paperBrightnessLoc = GLES30.glGetUniformLocation(paperProgram, "u_brightness")
        }

        if (backProgram > 0) {
            backMVPMatrixLoc = GLES30.glGetUniformLocation(backProgram, "u_MVPMatrix")
            backModelMatrixLoc = GLES30.glGetUniformLocation(backProgram, "u_ModelMatrix")
            backTextureLoc = GLES30.glGetUniformLocation(backProgram, "u_texture")
            backFrontTextureLoc = GLES30.glGetUniformLocation(backProgram, "u_frontTexture")
            backNoiseTextureLoc = GLES30.glGetUniformLocation(backProgram, "u_noiseTexture")
            backLightDirLoc = GLES30.glGetUniformLocation(backProgram, "u_lightDir")
            backAmbientLoc = GLES30.glGetUniformLocation(backProgram, "u_ambient")
            backDiffuseLoc = GLES30.glGetUniformLocation(backProgram, "u_diffuse")
            backSpecularLoc = GLES30.glGetUniformLocation(backProgram, "u_specular")
            backRoughnessLoc = GLES30.glGetUniformLocation(backProgram, "u_roughness")
            backBrightnessLoc = GLES30.glGetUniformLocation(backProgram, "u_brightness")
            backWarmthLoc = GLES30.glGetUniformLocation(backProgram, "u_warmth")
            backTranslucencyLoc = GLES30.glGetUniformLocation(backProgram, "u_translucency")
            backRimIntensityLoc = GLES30.glGetUniformLocation(backProgram, "u_rimIntensity")
            backAoIntensityLoc = GLES30.glGetUniformLocation(backProgram, "u_aoIntensity")
        }
    }

    private fun createProgram(vertexShaderFile: String, fragmentShaderFile: String): Int {
        val vertexSource = loadShaderSource(vertexShaderFile)
        val fragmentSource = loadShaderSource(fragmentShaderFile)

        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        if (vs == 0) {
            Log.e(TAG, "glCreateShader failed for $vertexShaderFile")
            return -1
        }
        GLES30.glShaderSource(vs, vertexSource)
        GLES30.glCompileShader(vs)
        val vsStatus = IntArray(1)
        GLES30.glGetShaderiv(vs, GLES30.GL_COMPILE_STATUS, vsStatus, 0)
        if (vsStatus[0] == 0) {
            Log.e(TAG, "Could not compile vertex shader $vertexShaderFile: ${GLES30.glGetShaderInfoLog(vs)}")
            GLES30.glDeleteShader(vs)
            return -1
        }

        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        if (fs == 0) {
            Log.e(TAG, "glCreateShader failed for $fragmentShaderFile")
            GLES30.glDeleteShader(vs)
            return -1
        }
        GLES30.glShaderSource(fs, fragmentSource)
        GLES30.glCompileShader(fs)
        val fsStatus = IntArray(1)
        GLES30.glGetShaderiv(fs, GLES30.GL_COMPILE_STATUS, fsStatus, 0)
        if (fsStatus[0] == 0) {
            Log.e(TAG, "Could not compile fragment shader $fragmentShaderFile: ${GLES30.glGetShaderInfoLog(fs)}")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return -1
        }

        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "glCreateProgram failed")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return -1
        }

        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Could not link program ($vertexShaderFile, $fragmentShaderFile): ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return -1
        }

        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun loadShaderSource(fileName: String): String {
        return when (fileName) {
            VERTEX_SHADER_BASIC -> BASIC_VERTEX_SHADER
            FRAGMENT_SHADER_BASIC -> BASIC_FRAGMENT_SHADER
            FRAGMENT_SHADER_PAPER -> PAPER_FRAGMENT_SHADER
            VERTEX_SHADER_BACK -> BACK_VERTEX_SHADER
            FRAGMENT_SHADER_BACK -> BACK_FRAGMENT_SHADER
            else -> throw IllegalArgumentException("Unknown shader file: $fileName")
        }
    }

    fun cleanup() {
        if (basicProgram != -1) {
            GLES30.glDeleteProgram(basicProgram)
            basicProgram = -1
        }
        if (paperProgram != -1) {
            GLES30.glDeleteProgram(paperProgram)
            paperProgram = -1
        }
        if (backProgram != -1) {
            GLES30.glDeleteProgram(backProgram)
            backProgram = -1
        }
        isInitialized = false
    }

    fun releaseGl() {
        basicProgram = -1; paperProgram = -1; backProgram = -1
        isInitialized = false
    }
}

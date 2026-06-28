package com.paperleaf.sketchbook.pageflip.engine

import android.opengl.GLES30
import android.util.Log

/**
 * Manages OpenGL ES 3.0 shader programs for premium page flip rendering.
 * Supports advanced lighting, shadows, and paper texture effects.
 */
class ShaderManager {
    
    companion object {
        private const val TAG = "ShaderManager"
        
        // Vertex shaders
        private const val VERTEX_SHADER_BASIC = "basic_vertex.glsl"
        private const val VERTEX_SHADER_CURL = "curl_vertex.glsl"
        private const val VERTEX_SHADER_SHADOW = "shadow_vertex.glsl"
        
        // Fragment shaders
        private const val FRAGMENT_SHADER_BASIC = "basic_fragment.glsl"
        private const val FRAGMENT_SHADER_LIGHTING = "lighting_fragment.glsl"
        private const val FRAGMENT_SHADER_SHADOW = "shadow_fragment.glsl"
        private const val FRAGMENT_SHADER_PAPER = "paper_fragment.glsl"
    }
    
    // Shader program handles
    var basicProgram: Int = -1
    var curlProgram: Int = -1
    var shadowProgram: Int = -1
    var lightingProgram: Int = -1
    var paperProgram: Int = -1
    
    // Uniform locations for basic program
    var basicMVPMatrixLoc: Int = -1
    var basicTexCoordLoc: Int = -1
    var basicTextureLoc: Int = -1
    
    // Uniform locations for curl program
    var curlMVPMatrixLoc: Int = -1
    var curlTexCoordLoc: Int = -1
    var curlTextureLoc: Int = -1
    var curlCurlFactorLoc: Int = -1
    var curlBendAxisLoc: Int = -1
    
    // Uniform locations for lighting program
    var lightingMVPMatrixLoc: Int = -1
    var lightingTexCoordLoc: Int = -1
    var lightingTextureLoc: Int = -1
    var lightingNormalLoc: Int = -1
    var lightingLightDirLoc: Int = -1
    var lightingAmbientLoc: Int = -1
    var lightingDiffuseLoc: Int = -1
    var lightingSpecularLoc: Int = -1
    var lightingViewPosLoc: Int = -1
    
    // Uniform locations for shadow program
    var shadowMVPMatrixLoc: Int = -1
    var shadowColorLoc: Int = -1
    var shadowIntensityLoc: Int = -1
    
    // Uniform locations for paper program
    var paperMVPMatrixLoc: Int = -1
    var paperTexCoordLoc: Int = -1
    var paperTextureLoc: Int = -1
    var paperNoiseTextureLoc: Int = -1
    var paperRoughnessLoc: Int = -1
    var paperBrightnessLoc: Int = -1
    
    private var isInitialized = false
    
    /**
     * Initialize all shader programs
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "ShaderManager already initialized")
            return
        }
        
        try {
            basicProgram = createProgram(VERTEX_SHADER_BASIC, FRAGMENT_SHADER_BASIC)
            curlProgram = createProgram(VERTEX_SHADER_CURL, FRAGMENT_SHADER_LIGHTING)
            shadowProgram = createProgram(VERTEX_SHADER_SHADOW, FRAGMENT_SHADER_SHADOW)
            lightingProgram = createProgram(VERTEX_SHADER_CURL, FRAGMENT_SHADER_LIGHTING)
            paperProgram = createProgram(VERTEX_SHADER_BASIC, FRAGMENT_SHADER_PAPER)
            
            getUniformLocations()
            isInitialized = true
            
            Log.i(TAG, "Shader programs initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize shaders", e)
            throw RuntimeException("Shader initialization failed", e)
        }
    }
    
    /**
     * Get uniform locations for all programs
     */
    private fun getUniformLocations() {
        // Basic program locations
        basicMVPMatrixLoc = GLES30.glGetUniformLocation(basicProgram, "u_MVPMatrix")
        basicTexCoordLoc = GLES30.glGetAttribLocation(basicProgram, "a_texCoord")
        basicTextureLoc = GLES30.glGetUniformLocation(basicProgram, "u_texture")
        
        // Curl program locations
        curlMVPMatrixLoc = GLES30.glGetUniformLocation(curlProgram, "u_MVPMatrix")
        curlTexCoordLoc = GLES30.glGetAttribLocation(curlProgram, "a_texCoord")
        curlTextureLoc = GLES30.glGetUniformLocation(curlProgram, "u_texture")
        curlCurlFactorLoc = GLES30.glGetUniformLocation(curlProgram, "u_curlFactor")
        curlBendAxisLoc = GLES30.glGetUniformLocation(curlProgram, "u_bendAxis")
        
        // Lighting program locations
        lightingMVPMatrixLoc = GLES30.glGetUniformLocation(lightingProgram, "u_MVPMatrix")
        lightingTexCoordLoc = GLES30.glGetAttribLocation(lightingProgram, "a_texCoord")
        lightingTextureLoc = GLES30.glGetUniformLocation(lightingProgram, "u_texture")
        lightingNormalLoc = GLES30.glGetAttribLocation(lightingProgram, "a_normal")
        lightingLightDirLoc = GLES30.glGetUniformLocation(lightingProgram, "u_lightDir")
        lightingAmbientLoc = GLES30.glGetUniformLocation(lightingProgram, "u_ambient")
        lightingDiffuseLoc = GLES30.glGetUniformLocation(lightingProgram, "u_diffuse")
        lightingSpecularLoc = GLES30.glGetUniformLocation(lightingProgram, "u_specular")
        lightingViewPosLoc = GLES30.glGetUniformLocation(lightingProgram, "u_viewPos")
        
        // Shadow program locations
        shadowMVPMatrixLoc = GLES30.glGetUniformLocation(shadowProgram, "u_MVPMatrix")
        shadowColorLoc = GLES30.glGetUniformLocation(shadowProgram, "u_shadowColor")
        shadowIntensityLoc = GLES30.glGetUniformLocation(shadowProgram, "u_intensity")
        
        // Paper program locations
        paperMVPMatrixLoc = GLES30.glGetUniformLocation(paperProgram, "u_MVPMatrix")
        paperTexCoordLoc = GLES30.glGetAttribLocation(paperProgram, "a_texCoord")
        paperTextureLoc = GLES30.glGetUniformLocation(paperProgram, "u_texture")
        paperNoiseTextureLoc = GLES30.glGetUniformLocation(paperProgram, "u_noiseTexture")
        paperRoughnessLoc = GLES30.glGetUniformLocation(paperProgram, "u_roughness")
        paperBrightnessLoc = GLES30.glGetUniformLocation(paperProgram, "u_brightness")
    }
    
    /**
     * Create a shader program from vertex and fragment shader files
     */
    private fun createProgram(vertexShaderFile: String, fragmentShaderFile: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderFile)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderFile)
        
        val program = GLES30.glCreateProgram()
        checkGlError("glCreateProgram")
        
        if (program == 0) {
            throw RuntimeException("glCreateProgram failed")
        }
        
        GLES30.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader vertex")
        GLES30.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader fragment")
        
        GLES30.glLinkProgram(program)
        checkGlError("glLinkProgram")
        
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Could not link program: ${GLES30.glGetProgramInfoLog(program)}")
            throw RuntimeException("Could not link program")
        }
        
        // Clean up shaders (they're linked into the program now)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        
        return program
    }
    
    /**
     * Load and compile a shader from file
     */
    private fun loadShader(type: Int, shaderFile: String): Int {
        val shaderSource = loadShaderSource(shaderFile)
        
        val shader = GLES30.glCreateShader(type)
        checkGlError("glCreateShader")
        
        if (shader == 0) {
            throw RuntimeException("glCreateShader failed")
        }
        
        GLES30.glShaderSource(shader, shaderSource)
        GLES30.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Could not compile shader ${shaderFile}: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $shaderFile")
        }
        
        return shader
    }
    
    /**
     * Load shader source code from assets
     */
    private fun loadShaderSource(fileName: String): String {
        return when (fileName) {
            VERTEX_SHADER_BASIC -> BASIC_VERTEX_SHADER
            FRAGMENT_SHADER_BASIC -> BASIC_FRAGMENT_SHADER
            VERTEX_SHADER_CURL -> CURL_VERTEX_SHADER
            FRAGMENT_SHADER_LIGHTING -> LIGHTING_FRAGMENT_SHADER
            FRAGMENT_SHADER_SHADOW -> SHADOW_FRAGMENT_SHADER
            FRAGMENT_SHADER_PAPER -> PAPER_FRAGMENT_SHADER
            else -> throw IllegalArgumentException("Unknown shader file: $fileName")
        }
    }
    
    /**
     * Use a specific shader program
     */
    fun useProgram(programType: ProgramType) {
        val program = when (programType) {
            ProgramType.BASIC -> basicProgram
            ProgramType.CURL -> curlProgram
            ProgramType.SHADOW -> shadowProgram
            ProgramType.LIGHTING -> lightingProgram
            ProgramType.PAPER -> paperProgram
        }
        GLES30.glUseProgram(program)
        checkGlError("glUseProgram")
    }
    
    /**
     * Cleanup shader resources
     */
    fun cleanup() {
        if (basicProgram != -1) {
            GLES30.glDeleteProgram(basicProgram)
            basicProgram = -1
        }
        if (curlProgram != -1) {
            GLES30.glDeleteProgram(curlProgram)
            curlProgram = -1
        }
        if (shadowProgram != -1) {
            GLES30.glDeleteProgram(shadowProgram)
            shadowProgram = -1
        }
        if (lightingProgram != -1) {
            GLES30.glDeleteProgram(lightingProgram)
            lightingProgram = -1
        }
        if (paperProgram != -1) {
            GLES30.glDeleteProgram(paperProgram)
            paperProgram = -1
        }
        isInitialized = false
    }
    
    private fun checkGlError(operation: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error after $operation: $error")
            throw RuntimeException("GL error $error after $operation")
        }
    }
    
    enum class ProgramType {
        BASIC,
        CURL,
        SHADOW,
        LIGHTING,
        PAPER
    }
    
    // Embedded shader sources (will be moved to .glsl files in assets)
    companion object ShaderSources {
        const val BASIC_VERTEX_SHADER = """
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
        
        const val BASIC_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            
            uniform sampler2D u_texture;
            
            in vec2 v_texCoord;
            out vec4 fragColor;
            
            void main() {
                fragColor = texture(u_texture, v_texCoord);
            }
        """.trimIndent()
        
        const val CURL_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            
            layout(location = 0) in vec3 a_position;
            layout(location = 1) in vec2 a_texCoord;
            layout(location = 2) in vec3 a_normal;
            
            uniform mat4 u_MVPMatrix;
            uniform float u_curlFactor;
            uniform vec3 u_bendAxis;
            
            out vec2 v_texCoord;
            out vec3 v_normal;
            out vec3 v_fragPos;
            
            void main() {
                // Apply curl deformation
                vec3 pos = a_position;
                float curl = sin(pos.x * u_curlFactor) * 0.1;
                pos.z += curl;
                
                v_fragPos = pos;
                v_normal = a_normal;
                v_texCoord = a_texCoord;
                
                gl_Position = u_MVPMatrix * vec4(pos, 1.0);
            }
        """.trimIndent()
        
        const val LIGHTING_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            
            uniform sampler2D u_texture;
            uniform vec3 u_lightDir;
            uniform vec3 u_ambient;
            uniform vec3 u_diffuse;
            uniform vec3 u_specular;
            uniform vec3 u_viewPos;
            
            in vec2 v_texCoord;
            in vec3 v_normal;
            in vec3 v_fragPos;
            
            out vec4 fragColor;
            
            void main() {
                vec3 norm = normalize(v_normal);
                vec3 lightDir = normalize(u_lightDir);
                
                // Ambient
                vec3 ambient = u_ambient;
                
                // Diffuse
                float diff = max(dot(norm, lightDir), 0.0);
                vec3 diffuse = diff * u_diffuse;
                
                // Specular
                vec3 viewDir = normalize(u_viewPos - v_fragPos);
                vec3 reflectDir = reflect(-lightDir, norm);
                float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32.0);
                vec3 specular = u_specular * spec;
                
                vec3 result = (ambient + diffuse + specular) * texture(u_texture, v_texCoord).rgb;
                fragColor = vec4(result, 1.0);
            }
        """.trimIndent()
        
        const val SHADOW_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            
            uniform vec4 u_shadowColor;
            uniform float u_intensity;
            
            in vec2 v_texCoord;
            out vec4 fragColor;
            
            void main() {
                float shadow = smoothstep(0.0, 1.0, v_texCoord.x);
                fragColor = vec4(u_shadowColor.rgb, u_shadowColor.a * shadow * u_intensity);
            }
        """.trimIndent()
        
        const val PAPER_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            
            uniform sampler2D u_texture;
            uniform sampler2D u_noiseTexture;
            uniform float u_roughness;
            uniform float u_brightness;
            
            in vec2 v_texCoord;
            out vec4 fragColor;
            
            void main() {
                vec4 texColor = texture(u_texture, v_texCoord);
                float noise = texture(u_noiseTexture, v_texCoord * 10.0).r;
                
                // Apply paper texture effect
                float paperEffect = mix(1.0, noise, u_roughness);
                vec3 result = texColor.rgb * paperEffect * u_brightness;
                
                fragColor = vec4(result, texColor.a);
            }
        """.trimIndent()
    }
}

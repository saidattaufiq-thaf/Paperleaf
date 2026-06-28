package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log

object ShaderCompiler {

    private const val TAG = "ShaderCompiler"

    private const val COMPILE_STATUS_SIZE = 1
    private val compileStatus = IntArray(COMPILE_STATUS_SIZE)
    private val linkStatus = IntArray(COMPILE_STATUS_SIZE)

    fun compileShader(type: Int, source: String, tag: String = TAG): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Log.e(tag, "glCreateShader failed for type=$type")
            return 0
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(tag, "Shader compile error (type=$type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun createProgram(
        vertexSource: String,
        fragmentSource: String,
        tag: String = TAG
    ): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, tag)
        if (vs == 0) return -1
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, tag)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return -1
        }
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Log.e(tag, "glCreateProgram failed")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return -1
        }
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(tag, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return -1
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }
}

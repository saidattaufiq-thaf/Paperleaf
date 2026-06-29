package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

class BookRenderer {

    companion object {
        private const val TAG = "BookRenderer"
        private const val FLOAT_SIZE = 4
        private const val PAGE_WIDTH = 1.8f
        private const val PAGE_HEIGHT = 2.6f
        private const val SPINE_WIDTH = 0.15f
        private const val PAGE_THICKNESS = 0.02f
        private const val COVER_THICKNESS = 0.04f
        private const val COVER_OVERHANG = 0.02f
        private const val STACK_MAX_PAGES = 24
        private const val SPINE_SEGMENTS = 14
        private const val LIFT_SEGMENTS = 8

        private val SPINE_COLOR = floatArrayOf(0.15f, 0.12f, 0.10f, 1.0f)
        private val COVER_FRONT = floatArrayOf(0.28f, 0.24f, 0.20f, 1.0f)
        private val COVER_BACK = floatArrayOf(0.18f, 0.15f, 0.12f, 1.0f)
        private val COVER_EDGE = floatArrayOf(0.22f, 0.19f, 0.16f, 1.0f)
        private val COVER_INNER = floatArrayOf(0.32f, 0.28f, 0.24f, 1.0f)

        private val COVER_COLORS = arrayOf(COVER_FRONT, COVER_BACK, COVER_EDGE, COVER_EDGE, COVER_EDGE, COVER_INNER)
    }

    private var program = -1
    private var mvpLoc = -1
    private var colorLoc = -1
    private var posLoc = -1
    private var isInitialized = false

    private var spineVao = 0
    private var spineVbo = 0
    private var coverVao = 0
    private var coverVbo = 0

    private var stackVao = 0
    private var stackVbo = 0
    private var stackCapacity = 0
    private var liftedVao = 0
    private var liftedVbo = 0
    private var liftedCapacity = 0

    private val stackBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(600 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val liftedBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(54 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val model = FloatArray(16)
    private val temp = FloatArray(16)

    private val reusableColor = FloatArray(4)

    fun initialize() {
        program = createSolidColorProgram()
        if (program > 0) {
            mvpLoc = GLES30.glGetUniformLocation(program, "u_MVPMatrix")
            colorLoc = GLES30.glGetUniformLocation(program, "u_color")
            posLoc = GLES30.glGetAttribLocation(program, "a_position")
        }

        buildSpineGpu()
        buildCoverGpu()
        buildStackGpu()
        buildLiftedGpu()

        isInitialized = true
        Log.d(TAG, "BookRenderer initialized")
    }

    fun render(mvpMatrix: FloatArray, flipProgress: Float, thickness: Float) {
        if (!isInitialized || program <= 0) return

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        val p = flipProgress.coerceIn(0f, 1f)
        val hw = PAGE_WIDTH / 2f
        val hh = PAGE_HEIGHT / 2f
        val sw = SPINE_WIDTH / 2f
        val cv = 0.04f
        val cw = hw + COVER_OVERHANG
        val ch = hh + COVER_OVERHANG
        val ct = COVER_THICKNESS
        val dynamicThickness = thickness * (1f - p * 0.85f)
        val stackDepth = dynamicThickness * STACK_MAX_PAGES

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -sw, 0f, -cv)
        Matrix.multiplyMM(temp, 0, mvpMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, temp, 0)
        drawSpine(p)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -(hw + sw), 0f, -cv - 0.01f)
        Matrix.multiplyMM(temp, 0, mvpMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, temp, 0)
        drawCoverBox(cw, ch, ct, isRight = false)

        val coverAngle = -p * 180f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, hw + sw, 0f, -cv - 0.01f)
        Matrix.rotateM(model, 0, coverAngle, 0f, 1f, 0f)
        Matrix.translateM(model, 0, -(hw + sw), 0f, 0f)
        Matrix.multiplyMM(temp, 0, mvpMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, temp, 0)
        drawCoverBox(cw, ch, ct, isRight = true)

        val stackHalfW = hw
        val stackX = -(hw + sw + 0.01f)
        updateStackGpu(stackHalfW, hh, stackDepth, p)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, stackX, 0f, 0f)
        Matrix.multiplyMM(temp, 0, mvpMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, temp, 0)
        drawPageStack(p)

        if (p > 0.01f && p < 0.95f) {
            updateLiftedGpu(stackHalfW, hh, p)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, stackX, 0f, stackDepth / 2f + 0.002f)
            Matrix.multiplyMM(temp, 0, mvpMatrix, 0, model, 0)
            GLES30.glUniformMatrix4fv(mvpLoc, 1, false, temp, 0)
            drawLiftedPage(p)
        }

        GLES30.glUseProgram(0)
    }

    private fun buildSpineGpu() {
        val hh = PAGE_HEIGHT / 2f
        val r = SPINE_WIDTH / 2f
        val n = SPINE_SEGMENTS
        val verts = mutableListOf<Float>()
        for (i in 0..n) {
            val t = i.toFloat() / n
            val a = t * PI.toFloat() - PI.toFloat() / 2f
            val lx = r * sin(a)
            val lz = r * (1f - cos(a))
            verts.addAll(listOf(lx, -hh, lz, lx, hh, lz))
        }

        val data = verts.toFloatArray()
        val sizeBytes = data.size * FLOAT_SIZE

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        spineVao = ids[0]
        GLES30.glGenBuffers(1, ids, 1)
        spineVbo = ids[1]

        GLES30.glBindVertexArray(spineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sizeBytes, FloatBuffer.wrap(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawSpine(progress: Float) {
        GLES30.glBindVertexArray(spineVao)
        val n = SPINE_SEGMENTS
        val center = progress * 0.08f + 0.15f
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val s = (1f - abs(t - 0.5f) * 0.5f + center).coerceIn(0f, 1f)
            setColor(SPINE_COLOR[0] * s, SPINE_COLOR[1] * s, SPINE_COLOR[2] * s, 1f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, i * 2, 4)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun buildCoverGpu() {
        val cw = PAGE_WIDTH / 2f + COVER_OVERHANG
        val ch = PAGE_HEIGHT / 2f + COVER_OVERHANG
        val ct = COVER_THICKNESS
        val hd = ct / 2f
        val verts = mutableListOf<Float>()

        fun addFace(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
                    x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float) {
            verts.addAll(listOf(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4))
        }

        addFace(-cw, -ch, hd,  cw, -ch, hd,  -cw, ch, hd,  cw, ch, hd)
        addFace(cw, -ch, -hd,  -cw, -ch, -hd,  cw, ch, -hd,  -cw, ch, -hd)
        addFace(-cw, ch, hd,  cw, ch, hd,  -cw, ch, -hd,  cw, ch, -hd)
        addFace(cw, -ch, hd,  -cw, -ch, hd,  cw, -ch, -hd,  -cw, -ch, -hd)
        addFace(cw, -ch, hd,  cw, -ch, -hd,  cw, ch, hd,  cw, ch, -hd)
        addFace(-cw, -ch, -hd,  -cw, -ch, hd,  -cw, ch, -hd,  -cw, ch, hd)

        val data = verts.toFloatArray()
        val sizeBytes = data.size * FLOAT_SIZE

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        coverVao = ids[0]
        GLES30.glGenBuffers(1, ids, 1)
        coverVbo = ids[1]

        GLES30.glBindVertexArray(coverVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, coverVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sizeBytes, FloatBuffer.wrap(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawCoverBox(hw: Float, hh: Float, thick: Float, isRight: Boolean) {
        GLES30.glBindVertexArray(coverVao)
        for (f in 0 until 6) {
            GLES30.glUniform4fv(colorLoc, 1, COVER_COLORS[f], 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, f * 4, 4)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun buildStackGpu() {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        stackVao = ids[0]
        GLES30.glGenBuffers(1, ids, 1)
        stackVbo = ids[1]

        GLES30.glBindVertexArray(stackVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, stackVbo)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun updateStackGpu(halfW: Float, halfH: Float, depth: Float, progress: Float) {
        val n = STACK_MAX_PAGES
        val hw = halfW
        val hh = halfH
        val hd = depth / 2f
        stackBuffer.clear()

        for (i in 0 until n) {
            val z = -hd + (i.toFloat() / n) * depth
            stackBuffer.put(hw).put(hh).put(z)
            stackBuffer.put(-hw).put(hh).put(z)
        }
        for (i in 0 until n) {
            val z = -hd + (i.toFloat() / n) * depth
            stackBuffer.put(hw).put(-hh).put(z)
            stackBuffer.put(-hw).put(-hh).put(z)
        }
        for (i in 0 until n) {
            val z = -hd + (i.toFloat() / n) * depth
            stackBuffer.put(hw).put(-hh).put(z)
            stackBuffer.put(hw).put(hh).put(z)
        }
        for (i in 0 until n) {
            val z = -hd + (i.toFloat() / n) * depth
            stackBuffer.put(-hw).put(-hh).put(z)
            stackBuffer.put(-hw).put(hh).put(z)
        }
        stackBuffer.put(-hw).put(-hh).put(hd).put(hw).put(-hh).put(hd).put(-hw).put(hh).put(hd).put(hw).put(hh).put(hd)
        stackBuffer.put(hw).put(-hh).put(-hd).put(-hw).put(-hh).put(-hd).put(hw).put(hh).put(-hd).put(-hw).put(hh).put(-hd)

        stackBuffer.flip()
        val sizeBytes = stackBuffer.remaining() * FLOAT_SIZE

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, stackVbo)
        if (sizeBytes > stackCapacity) {
            stackCapacity = (sizeBytes * 1.5f).toInt().coerceAtLeast(sizeBytes)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, stackCapacity, null, GLES30.GL_DYNAMIC_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, stackBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawPageStack(progress: Float) {
        GLES30.glBindVertexArray(stackVao)
        val n = STACK_MAX_PAGES
        val fade = 1f - (1f - progress) * 0.3f
        var off = 0

        for (i in 0 until n) {
            val s = 0.88f - (i.toFloat() / n) * 0.12f * fade
            setColorWithAlternate(i, s)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)
            off += 4
        }
        for (i in 0 until n) {
            val s = 0.80f - (i.toFloat() / n) * 0.10f * fade
            setColor(s * 0.97f, s * 0.95f, s)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)
            off += 4
        }
        for (i in 0 until n) {
            val s = 0.82f - (i.toFloat() / n) * 0.12f * fade
            setColor(s * 0.96f, s * 0.94f, s)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)
            off += 4
        }
        for (i in 0 until n) {
            val s = 0.70f - (i.toFloat() / n) * 0.10f * fade
            setColor(s * 0.97f, s * 0.95f, s)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)
            off += 4
        }
        setColor(0.92f, 0.90f, 0.87f, 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)
        off += 4
        setColor(0.75f, 0.73f, 0.70f, 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, off, 4)

        GLES30.glBindVertexArray(0)
    }

    private fun buildLiftedGpu() {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        liftedVao = ids[0]
        GLES30.glGenBuffers(1, ids, 1)
        liftedVbo = ids[1]

        GLES30.glBindVertexArray(liftedVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, liftedVbo)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun updateLiftedGpu(halfW: Float, halfH: Float, progress: Float) {
        val n = LIFT_SEGMENTS
        val hw = halfW
        val hh = halfH
        val lift = sin(progress * PI.toFloat()) * 0.04f
        val bend = sin(progress * PI.toFloat()) * 0.02f
        liftedBuffer.clear()

        for (i in 0..n) {
            val t = i.toFloat() / n
            val x = -hw + t * (2f * hw)
            val z = lift * sin(t * PI.toFloat())
            val yb = bend * sin(t * PI.toFloat())
            liftedBuffer.put(x).put(-hh + yb).put(z)
            liftedBuffer.put(x).put(hh + yb).put(z)
        }

        liftedBuffer.flip()
        val sizeBytes = liftedBuffer.remaining() * FLOAT_SIZE

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, liftedVbo)
        if (sizeBytes > liftedCapacity) {
            liftedCapacity = (sizeBytes * 1.5f).toInt().coerceAtLeast(sizeBytes)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, liftedCapacity, null, GLES30.GL_DYNAMIC_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, liftedBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawLiftedPage(progress: Float) {
        GLES30.glBindVertexArray(liftedVao)
        for (i in 0 until LIFT_SEGMENTS) {
            val t = i.toFloat() / LIFT_SEGMENTS
            val s = 0.88f - t * 0.06f
            setColor(s, s * 0.97f, s * 0.94f, 0.85f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, i * 2, 4)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun setColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        reusableColor[0] = r
        reusableColor[1] = g
        reusableColor[2] = b
        reusableColor[3] = a
        GLES30.glUniform4fv(colorLoc, 1, reusableColor, 0)
    }

    private fun setColorWithAlternate(i: Int, s: Float) {
        val c = if (i % 2 == 0) {
            reusableColor.let {
                it[0] = s; it[1] = s * 0.97f; it[2] = s * 0.95f; it[3] = 1f
            }
            reusableColor
        } else {
            reusableColor.let {
                it[0] = s * 0.95f; it[1] = s * 0.92f; it[2] = s * 0.90f; it[3] = 1f
            }
            reusableColor
        }
        GLES30.glUniform4fv(colorLoc, 1, c, 0)
    }

    private fun createSolidColorProgram(): Int {
        val vertexSource = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec3 a_position;
            uniform mat4 u_MVPMatrix;
            void main() {
                gl_Position = u_MVPMatrix * vec4(a_position, 1.0);
            }
        """.trimIndent()
        val fragmentSource = """
            #version 300 es
            precision highp float;
            uniform vec4 u_color;
            out vec4 fragColor;
            void main() {
                fragColor = u_color;
            }
        """.trimIndent()

        return ShaderCompiler.createProgram(vertexSource, fragmentSource, TAG)
    }

    fun releaseGl() {
        program = -1
        spineVao = 0; spineVbo = 0; coverVao = 0; coverVbo = 0
        stackVao = 0; stackVbo = 0; liftedVao = 0; liftedVbo = 0
        stackCapacity = 0; liftedCapacity = 0
        isInitialized = false
    }

    fun cleanup() {
        if (program > 0) GLES30.glDeleteProgram(program)
        val vaos = mutableListOf<Int>()
        if (spineVao > 0) vaos.add(spineVao)
        if (coverVao > 0) vaos.add(coverVao)
        if (stackVao > 0) vaos.add(stackVao)
        if (liftedVao > 0) vaos.add(liftedVao)
        if (vaos.isNotEmpty()) GLES30.glDeleteVertexArrays(vaos.size, vaos.toIntArray(), 0)
        val vbos = mutableListOf<Int>()
        if (spineVbo > 0) vbos.add(spineVbo)
        if (coverVbo > 0) vbos.add(coverVbo)
        if (stackVbo > 0) vbos.add(stackVbo)
        if (liftedVbo > 0) vbos.add(liftedVbo)
        if (vbos.isNotEmpty()) GLES30.glDeleteBuffers(vbos.size, vbos.toIntArray(), 0)
        releaseGl()
    }
}

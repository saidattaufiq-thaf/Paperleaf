package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class GpuBuffer {

    companion object {
        private const val TAG = "GpuBuffer"

        const val FLOAT_SIZE = 4
        const val SHORT_SIZE = 2

        const val STRIDE_FLOATS = 8
        const val STRIDE_BYTES = STRIDE_FLOATS * FLOAT_SIZE
        const val POS_OFFSET = 0
        const val NORMAL_OFFSET = 3
        const val TEX_OFFSET = 6

        private const val POS_SIZE = 3
        private const val NORMAL_SIZE = 3
        private const val TEX_SIZE = 2
        private const val CAPACITY_GROWTH_FACTOR = 1.5f
        private const val RESOURCE_IDS_COUNT = 3
    }

    private var vbo = 0
    private var ebo = 0
    private var vao = 0
    private var vertexCapacity = 0
    private var indexCapacity = 0
    private var indexCount = 0
    private var drawMode = GLES30.GL_TRIANGLES
    private var initialized = false

    private val tmpInt = IntArray(1)
    private var uploadFloatBuffer: FloatBuffer? = null
    private var uploadShortBuffer: ShortBuffer? = null

    fun initialize(usage: Int = GLES30.GL_DYNAMIC_DRAW) {
        if (initialized) return
        val ids = IntArray(RESOURCE_IDS_COUNT)
        GLES30.glGenBuffers(RESOURCE_IDS_COUNT, ids, 0)
        vbo = ids[0]
        ebo = ids[1]
        vao = ids[2]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, POS_SIZE, GLES30.GL_FLOAT, false, STRIDE_BYTES, POS_OFFSET * FLOAT_SIZE)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, NORMAL_SIZE, GLES30.GL_FLOAT, false, STRIDE_BYTES, NORMAL_OFFSET * FLOAT_SIZE)

        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, TEX_SIZE, GLES30.GL_FLOAT, false, STRIDE_BYTES, TEX_OFFSET * FLOAT_SIZE)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        initialized = true
        Log.d(TAG, "VBO=$vbo EBO=$ebo VAO=$vao")
    }

    fun uploadVertexData(vertices: FloatArray, usage: Int = GLES30.GL_DYNAMIC_DRAW) {
        val sizeBytes = vertices.size * FLOAT_SIZE
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, tmpInt, 0)
        val prevVbo = tmpInt[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        if (sizeBytes > vertexCapacity) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sizeBytes, null, usage)
            vertexCapacity = sizeBytes
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, ensureFloatBuffer(vertices))

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, prevVbo)
    }

    fun uploadIndexData(indices: ShortArray, usage: Int = GLES30.GL_STATIC_DRAW) {
        val sizeBytes = indices.size * SHORT_SIZE
        GLES30.glGetIntegerv(GLES30.GL_ELEMENT_ARRAY_BUFFER_BINDING, tmpInt, 0)
        val prevEbo = tmpInt[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)

        if (sizeBytes > indexCapacity) {
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sizeBytes, null, usage)
            indexCapacity = sizeBytes
        }
        GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, sizeBytes, ensureShortBuffer(indices))

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, prevEbo)
    }

    fun uploadInterleaved(vertices: FloatArray, indices: ShortArray, usage: Int = GLES30.GL_DYNAMIC_DRAW) {
        indexCount = indices.size
        uploadVertexData(vertices, usage)
        uploadIndexData(indices, GLES30.GL_STATIC_DRAW)
    }

    fun updateVertexSubData(vertices: FloatArray, offsetBytes: Int = 0) {
        Log.d("TRACE_GpuBuffer", "updateVertexSubData: vertices=${vertices.size} floats = ${vertices.size * FLOAT_SIZE} bytes, offset=$offsetBytes")
        val sizeBytes = vertices.size * FLOAT_SIZE
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, tmpInt, 0)
        val prevVbo = tmpInt[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        val maxSize = offsetBytes + sizeBytes
        if (maxSize > vertexCapacity) {
            val newCapacity = (maxSize * CAPACITY_GROWTH_FACTOR).toInt().coerceAtLeast(maxSize)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, newCapacity, null, GLES30.GL_DYNAMIC_DRAW)
            vertexCapacity = newCapacity
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, offsetBytes, sizeBytes, ensureFloatBuffer(vertices))

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, prevVbo)
    }

    fun bind() {
        GLES30.glBindVertexArray(vao)
    }

    fun unbind() {
        GLES30.glBindVertexArray(0)
    }

    fun draw(count: Int = indexCount, mode: Int = GLES30.GL_TRIANGLES) {
        if (!initialized || count == 0) {
            Log.d("TRACE_GpuBuffer", "draw SKIPPED: initialized=$initialized count=$count indexCount=$indexCount")
            return
        }
        Log.d("TRACE_GpuBuffer", "draw: count=$count mode=$mode vao=$vao vbo=$vbo ebo=$ebo")
        bind()
        GLES30.glDrawElements(mode, count, GLES30.GL_UNSIGNED_SHORT, 0)
        unbind()
    }

    fun drawArrays(first: Int, count: Int, mode: Int = GLES30.GL_TRIANGLES) {
        if (!initialized || count == 0) return
        bind()
        GLES30.glDrawArrays(mode, first, count)
        unbind()
    }

    fun drawStrip(count: Int) {
        draw(count, GLES30.GL_TRIANGLE_STRIP)
    }

    fun setDrawMode(mode: Int) {
        drawMode = mode
    }

    fun getIndexCount(): Int = indexCount
    fun getVbo(): Int = vbo
    fun getEbo(): Int = ebo
    fun getVao(): Int = vao
    fun isInitialized(): Boolean = initialized

    fun releaseGl() {
        vbo = 0; ebo = 0; vao = 0
        vertexCapacity = 0; indexCapacity = 0; indexCount = 0
        initialized = false
    }

    private fun ensureFloatBuffer(vertices: FloatArray): FloatBuffer {
        val buf = if (uploadFloatBuffer == null || uploadFloatBuffer!!.capacity() < vertices.size) {
            java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { uploadFloatBuffer = it }
        } else {
            uploadFloatBuffer!!
        }
        buf.clear()
        buf.put(vertices)
        buf.flip()
        return buf
    }

    private fun ensureShortBuffer(indices: ShortArray): ShortBuffer {
        val buf = if (uploadShortBuffer == null || uploadShortBuffer!!.capacity() < indices.size) {
            java.nio.ByteBuffer.allocateDirect(indices.size * 2)
                .order(java.nio.ByteOrder.nativeOrder())
                .asShortBuffer()
                .also { uploadShortBuffer = it }
        } else {
            uploadShortBuffer!!
        }
        buf.clear()
        buf.put(indices)
        buf.flip()
        return buf
    }

    fun cleanup() {
        if (!initialized) return
        if (vao > 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vbo > 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (ebo > 0) GLES30.glDeleteBuffers(1, intArrayOf(ebo), 0)
        releaseGl()
    }
}

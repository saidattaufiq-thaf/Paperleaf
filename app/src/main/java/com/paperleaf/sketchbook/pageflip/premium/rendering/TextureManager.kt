package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TextureManager {

    companion object {
        private const val TAG = "TextureManager"
        private const val MAX_CACHE_SIZE = 16

        private const val MIN_MIPMAP_SIZE = 32
        private const val STREAMING_CHUNK = 4

        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        private const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }

    data class TextureInfo(
        val textureId: Int,
        val width: Int,
        val height: Int,
        val mipLevels: Int = 1
    )

    enum class FilterMode {
        NEAREST, BILINEAR, TRILINEAR, ANISOTROPIC
    }

    private class CacheEntry(
        val info: TextureInfo,
        var lastAccess: Long = System.nanoTime()
    )

    private val textureCache = LinkedHashMap<Long, CacheEntry>(MAX_CACHE_SIZE, 0.75f, true)
    private val streamingQueue = mutableListOf<StreamingRequest>()

    private var textures = mutableListOf<Int>()
    private var isInitialized = false

    private var filterMode = FilterMode.TRILINEAR
    private var maxAnisotropy = 1f
    private var hasAnisotropicSupport = false

    data class StreamingRequest(
        val key: Long,
        val bitmap: Bitmap,
        val priority: Int = 0
    )

    fun initialize(capacity: Int = 4) {
        textures = MutableList(capacity) { 0 }
        maxAnisotropy = queryMaxAnisotropy()
        hasAnisotropicSupport = maxAnisotropy > 1f
        isInitialized = true
    }

    fun allocate(capacity: Int): IntArray {
        val ids = IntArray(capacity)
        GLES30.glGenTextures(capacity, ids, 0)
        textures.addAll(ids.toList())
        return ids
    }

    fun upload(bitmap: Bitmap, textureId: Int) {
        uploadInternal(bitmap, textureId, generateMipmaps = true)
    }

    fun uploadStreaming(bitmap: Bitmap, textureId: Int, mipLevel: Int = 0) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        if (mipLevel == 0) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_BASE_LEVEL, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,
                    calculateMipLevels(bitmap.width, bitmap.height),
                    GLES30.GL_RGBA8, bitmap.width, bitmap.height)
            }
            setTextureParams(textureId)
        }

        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, mipLevel, 0, 0, bitmap)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun requestStreaming(key: Long, bitmap: Bitmap, priority: Int = 0) {
        streamingQueue.removeAll { it.key == key }
        streamingQueue.add(StreamingRequest(key, bitmap, priority))
        streamingQueue.sortByDescending { it.priority }
    }

    fun processStreamingQueue(maxPerFrame: Int = 1): Int {
        var processed = 0
        val iter = streamingQueue.iterator()
        while (iter.hasNext() && processed < maxPerFrame) {
            val req = iter.next()
            iter.remove()
            if (textureCache.containsKey(req.key)) continue
            val id = IntArray(1)
            GLES30.glGenTextures(1, id, 0)
            uploadStreaming(req.bitmap, id[0], 0)
            val info = TextureInfo(id[0], req.bitmap.width, req.bitmap.height, 1)
            textureCache[req.key] = CacheEntry(info)
            processed++
        }
        return processed
    }

    fun uploadMipChain(bitmap: Bitmap, textureId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        val levels = calculateMipLevels(bitmap.width, bitmap.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, bitmap.width, bitmap.height)
        }

        var w = bitmap.width
        var h = bitmap.height
        var mip = 0

        var current = bitmap
        while (mip < levels) {
            val pixels = IntArray(w * h)
            current.getPixels(pixels, 0, w, 0, 0, w, h)
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    buffer.put((p shr 16 and 0xFF).toByte())
                    buffer.put((p shr 8 and 0xFF).toByte())
                    buffer.put((p and 0xFF).toByte())
                    buffer.put((p shr 24 and 0xFF).toByte())
                }
            }
            buffer.position(0)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, mip, 0, 0, w, h,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

            if (mip == 0) setTextureParams(textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1)

            mip++
            if (w > 1) w /= 2
            if (h > 1) h /= 2
            if (mip < levels) {
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                if (current !== bitmap && current !== scaled) current.recycle()
                current = scaled
            }
        }

        if (current !== bitmap) current.recycle()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun getOrCreate(key: Long, bitmap: Bitmap): TextureInfo {
        val cached = textureCache[key]
        if (cached != null) {
            cached.lastAccess = System.nanoTime()
            return cached.info
        }
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        upload(bitmap, id[0])
        val info = TextureInfo(id[0], bitmap.width, bitmap.height)
        textureCache[key] = CacheEntry(info)
        evictIfNeeded()
        return info
    }

    fun cache(key: Long, textureId: Int, width: Int, height: Int) {
        textureCache[key] = CacheEntry(TextureInfo(textureId, width, height))
        evictIfNeeded()
    }

    fun getFromCache(key: Long): TextureInfo? = textureCache[key]?.info

    fun evict(key: Long) {
        textureCache.remove(key)?.let {
            deleteTexture(it.info.textureId)
        }
    }

    fun setFilterMode(mode: FilterMode) {
        filterMode = mode
    }

    fun bind(textureId: Int, unit: Int = GLES30.GL_TEXTURE0) {
        GLES30.glActiveTexture(unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    }

    fun bindWithFilter(textureId: Int, unit: Int = GLES30.GL_TEXTURE0) {
        GLES30.glActiveTexture(unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        applyFilterParams()
    }

    private fun uploadInternal(bitmap: Bitmap, textureId: Int, generateMipmaps: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        if (generateMipmaps) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (hasAnisotropicSupport && filterMode == FilterMode.ANISOTROPIC) {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D,
                GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        if (generateMipmaps) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun setTextureParams(textureId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        applyFilterParams()
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun applyFilterParams() {
        when (filterMode) {
            FilterMode.NEAREST -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            }
            FilterMode.BILINEAR -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            }
            FilterMode.TRILINEAR -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            }
            FilterMode.ANISOTROPIC -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                if (hasAnisotropicSupport) {
                    GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D,
                        GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
                }
            }
        }
    }

    private fun evictIfNeeded() {
        while (textureCache.size > MAX_CACHE_SIZE) {
            val eldest = textureCache.entries.firstOrNull()
            eldest?.let {
                deleteTexture(it.value.info.textureId)
                textureCache.remove(it.key)
            }
        }
    }

    private fun deleteTexture(textureId: Int) {
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    fun releaseGl() {
        textureCache.clear()
        textures.clear()
        streamingQueue.clear()
        isInitialized = false
    }

    fun releaseAll() {
        textureCache.values.forEach { deleteTexture(it.info.textureId) }
        textureCache.clear()
        textures.forEach { deleteTexture(it) }
        textures.clear()
        streamingQueue.clear()
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized

    private fun calculateMipLevels(w: Int, h: Int): Int {
        val maxDim = maxOf(w, h)
        var levels = 1
        var size = maxDim
        while (size > MIN_MIPMAP_SIZE) {
            size /= 2
            levels++
        }
        return levels
    }

    private fun queryMaxAnisotropy(): Float {
        return try {
            val ids = IntArray(1)
            GLES30.glGetIntegerv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, ids, 0)
            ids[0].toFloat().coerceIn(1f, 16f)
        } catch (_: Exception) {
            1f
        }
    }
}

package com.paperleaf.sketchbook.pageflip.premium.rendering

import android.os.Build
import android.os.SystemClock
import android.util.Log

class FrameProfiler {

    companion object {
        private const val TAG = "FrameProfiler"
        private const val WINDOW_SIZE = 30
        private const val TARGET_120_FPS = 8.33f
        private const val TARGET_60_FPS = 16.67f
        private const val TARGET_30_FPS = 33.33f
        private const val DROP_THRESHOLD = 1.5f
        private const val TIER_THRESHOLD_FACTOR = 0.9f
        private const val MAX_CONSECUTIVE_SLOW_FRAMES = 5
        private const val UPGRADE_THRESHOLD_FACTOR = 0.5f
        private const val DEFAULT_REFRESH_RATE = 60f
        private const val REFRESH_RATE_120_THRESHOLD = 90f
        private const val DEFAULT_GRID_RESOLUTION = 30
        private const val NANO_TO_MILLI = 1_000_000f
        private const val FPS_CONVERSION = 1000f

        private val qualityMaxAllowedFactors = mapOf(
            QualityLevel.ULTRA to 0.85f,
            QualityLevel.HIGH to 0.85f,
            QualityLevel.MEDIUM to 0.75f,
            QualityLevel.LOW to 0.65f
        )

        private val downgradeGridResolutions = mapOf(
            QualityLevel.ULTRA to 25,
            QualityLevel.HIGH to 22,
            QualityLevel.MEDIUM to 20,
            QualityLevel.LOW to 18
        )

        private val upgradeGridResolutions = mapOf(
            QualityLevel.LOW to 22,
            QualityLevel.MEDIUM to 25,
            QualityLevel.HIGH to 30
        )
    }

    enum class PerformanceTier {
        TIER_120, TIER_60, TIER_30, TIER_FALLBACK
    }

    enum class QualityLevel {
        ULTRA, HIGH, MEDIUM, LOW
    }

    data class FrameMetrics(
        val frameTimeMs: Float = 0f,
        val fps: Float = 0f,
        val drawCalls: Int = 0,
        val vboUploads: Int = 0,
        val textureBinds: Int = 0,
        val tier: PerformanceTier = PerformanceTier.TIER_60,
        val quality: QualityLevel = QualityLevel.HIGH
    )

    private val frameTimes = FloatArray(WINDOW_SIZE)
    private var frameIndex = 0
    private var frameCount = 0
    private var lastFrameTime = 0L

    private var frameStartNs = 0L
    private var frameDeltaMs = 0f
    private var frameDropped = false

    private var drawCallsThisFrame = 0
    private var vboUploadsThisFrame = 0
    private var textureBindsThisFrame = 0

    private var slowFrameCount = 0
    private var quality = QualityLevel.HIGH
    private var tier = PerformanceTier.TIER_60
    private var gridResolution = DEFAULT_GRID_RESOLUTION

    private var targetFrameTime = TARGET_60_FPS

    fun beginFrame() {
        frameStartNs = System.nanoTime()
        drawCallsThisFrame = 0
        vboUploadsThisFrame = 0
        textureBindsThisFrame = 0
    }

    fun endFrame() {
        val elapsed = System.nanoTime() - frameStartNs
        frameDeltaMs = elapsed / NANO_TO_MILLI

        frameTimes[frameIndex] = frameDeltaMs
        frameIndex = (frameIndex + 1) % WINDOW_SIZE
        frameCount++

        updateTier()
        adaptQuality()

        val now = SystemClock.uptimeMillis()
        if (lastFrameTime > 0) {
            val expectedFrame = targetFrameTime
            val actualFrame = now - lastFrameTime
            frameDropped = actualFrame > expectedFrame * DROP_THRESHOLD
        }
        lastFrameTime = now
    }

    fun markDrawCall() { drawCallsThisFrame++ }
    fun markVboUpload() { vboUploadsThisFrame++ }
    fun markTextureBind() { textureBindsThisFrame++ }

    fun getFrameTime(): Float = frameDeltaMs
    fun getFps(): Float = if (frameDeltaMs > 0f) FPS_CONVERSION / frameDeltaMs else 0f
    fun getQuality(): QualityLevel = quality
    fun getGridResolution(): Int = gridResolution
    fun getTier(): PerformanceTier = tier

    fun getMetrics(): FrameMetrics {
        val avgFps = if (frameCount >= WINDOW_SIZE) {
            val total = frameTimes.sum()
            FPS_CONVERSION / (total / WINDOW_SIZE)
        } else 0f
        return FrameMetrics(
            frameTimeMs = frameDeltaMs,
            fps = avgFps,
            drawCalls = drawCallsThisFrame,
            vboUploads = vboUploadsThisFrame,
            textureBinds = textureBindsThisFrame,
            tier = tier,
            quality = quality
        )
    }

    fun shouldDropFrame(): Boolean = frameDropped

    fun supports120Fps(context: android.content.Context? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val ctx = context ?: return false
        val refreshRate = try {
            val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.display
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay
            }
            val mode = display?.mode
            mode?.refreshRate ?: DEFAULT_REFRESH_RATE
        } catch (_: Exception) {
            DEFAULT_REFRESH_RATE
        }
        return refreshRate >= REFRESH_RATE_120_THRESHOLD
    }

    fun request120Fps(enable: Boolean) {
        targetFrameTime = if (enable) TARGET_120_FPS else TARGET_60_FPS
    }

    fun reset() {
        frameTimes.fill(0f)
        frameIndex = 0
        frameCount = 0
        slowFrameCount = 0
        quality = QualityLevel.HIGH
        gridResolution = DEFAULT_GRID_RESOLUTION
    }

    private fun updateTier() {
        val avg = if (frameCount >= WINDOW_SIZE) {
            var sum = 0.0
            for (i in 0 until WINDOW_SIZE) sum += frameTimes[i]
            (sum / WINDOW_SIZE).toFloat()
        } else if (frameCount > 0) {
            var sum = 0.0
            for (i in 0 until frameCount) sum += frameTimes[i]
            (sum / frameCount).toFloat()
        } else return

        tier = when {
            avg <= TARGET_120_FPS * TIER_THRESHOLD_FACTOR -> PerformanceTier.TIER_120
            avg <= TARGET_60_FPS * TIER_THRESHOLD_FACTOR -> PerformanceTier.TIER_60
            avg <= TARGET_30_FPS * TIER_THRESHOLD_FACTOR -> PerformanceTier.TIER_30
            else -> PerformanceTier.TIER_FALLBACK
        }
    }

    private fun adaptQuality() {
        if (frameTimes.all { it == 0f }) return

        val avg = if (frameCount >= WINDOW_SIZE) {
            frameTimes.average().toFloat()
        } else return

        val maxAllowed = qualityMaxAllowedFactors[quality] ?: 0.85f

        if (avg > maxAllowed * DROP_THRESHOLD) {
            slowFrameCount++
        } else {
            slowFrameCount = (slowFrameCount - 1).coerceAtLeast(0)
        }

        if (slowFrameCount > MAX_CONSECUTIVE_SLOW_FRAMES) {
            downgradeQuality()
            slowFrameCount = 0
        } else if (slowFrameCount == 0 && avg < maxAllowed * UPGRADE_THRESHOLD_FACTOR) {
            upgradeQuality()
        }
    }

    private fun downgradeQuality() {
        quality = when (quality) {
            QualityLevel.ULTRA -> QualityLevel.HIGH
            QualityLevel.HIGH -> QualityLevel.MEDIUM
            QualityLevel.MEDIUM -> QualityLevel.LOW
            QualityLevel.LOW -> QualityLevel.LOW
        }
        gridResolution = downgradeGridResolutions[quality] ?: DEFAULT_GRID_RESOLUTION
        Log.d(TAG, "Downgraded to $quality (grid=$gridResolution)")
    }

    private fun upgradeQuality() {
        val targetQuality = when (quality) {
            QualityLevel.LOW -> QualityLevel.MEDIUM
            QualityLevel.MEDIUM -> QualityLevel.HIGH
            QualityLevel.HIGH -> QualityLevel.ULTRA
            QualityLevel.ULTRA -> QualityLevel.ULTRA
        }
        gridResolution = upgradeGridResolutions[quality] ?: DEFAULT_GRID_RESOLUTION
        quality = targetQuality
        Log.d(TAG, "Upgraded to $quality (grid=$gridResolution)")
    }
}

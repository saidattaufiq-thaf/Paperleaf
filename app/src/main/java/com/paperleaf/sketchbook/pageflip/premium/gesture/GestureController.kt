package com.paperleaf.sketchbook.pageflip.premium.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.min

class GestureController {

    companion object {
        private const val SWIPE_THRESHOLD = 50f
        private const val SWIPE_VELOCITY_THRESHOLD = 100f
        private const val PAN_THRESHOLD = 5f
        private const val TAP_THRESHOLD = 10f

        private const val PALM_REJECTION_MARGIN = 100f
        private const val PALM_TOUCH_SIZE_THRESHOLD = 0.3f
        private const val PALM_VELOCITY_THRESHOLD = 2000f

        private const val VELOCITY_SMOOTHING_ALPHA = 0.72f
        private const val HISTORY_SIZE = 4
        private const val VELOCITY_EPSILON = 0.1f

        private const val DIRECTION_LOCK_ANGLE_DEG = 30f
        private const val DIRECTION_LOCK_COS = 0.866f

        private const val JITTER_FILTER_STRENGTH = 0.35f
        private const val MIN_MOVEMENT = 1.5f

        private const val EDGE_MARGIN_RATIO = 0.15f

        private const val MULTI_TOUCH_FLIP_CANCEL = true
    }

    sealed class GestureEvent {
        data class Swipe(val direction: SwipeDirection, val velocity: Float) : GestureEvent()
        data class Pan(
            val deltaX: Float,
            val deltaY: Float,
            val velocityX: Float = 0f,
            val velocityY: Float = 0f,
            val foldOriginX: Float = 0f,
            val foldOriginY: Float = 0f
        ) : GestureEvent()
        data class Scale(val scaleFactor: Float, val focusX: Float, val focusY: Float) : GestureEvent()
        data class Tap(val x: Float, val y: Float) : GestureEvent()
        data class LongPress(val x: Float, val y: Float) : GestureEvent()
        data class StylusPress(val x: Float, val y: Float, val pressure: Float) : GestureEvent()
        object FlipStart : GestureEvent()
        object FlipEnd : GestureEvent()
    }

    enum class SwipeDirection {
        LEFT, RIGHT, UP, DOWN
    }

    data class GestureConfig(
        val enableSwipe: Boolean = true,
        val enablePinch: Boolean = true,
        val enablePan: Boolean = true,
        val enableTap: Boolean = true,
        val enableStylus: Boolean = true,
        val enablePalmRejection: Boolean = true,
        val requireSingleTouchForFlip: Boolean = true,
        val enableDirectionLock: Boolean = true,
        val enableJitterFilter: Boolean = true,
        val palmRejectionMargin: Float = PALM_REJECTION_MARGIN
    )

    private data class TouchSample(
        val x: Float,
        val y: Float,
        val time: Long,
        val pressure: Float = 1f,
        val touchMajor: Float = 0f
    )

    private var config = GestureConfig()
    private var listener: ((GestureEvent) -> Unit)? = null
    private var context: Context? = null

    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var isFlipping = false
    private var flipStartX = 0f
    private var flipStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var filteredTouchX = 0f
    private var filteredTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isStylusActive = false
    private var currentPressure = 1.0f

    private var screenWidth = 0f
    private var screenHeight = 0f

    private val touchHistory = ArrayDeque<TouchSample>(HISTORY_SIZE + 1)

    private var trackedVelocityX = 0f
    private var trackedVelocityY = 0f

    private var lockedDirection = false
    private var lockedAxisX = true
    private var lockedAngleRad = 0f

    private var totalFlipDeltaX = 0f
    private var initialTouchEdge = 0f

    private var activePointerCount = 0

    private var foldOriginX = 0f
    private var foldOriginY = 0f

    fun initialize(
        context: Context,
        width: Float,
        height: Float,
        onGesture: (GestureEvent) -> Unit
    ) {
        this.context = context
        screenWidth = width
        screenHeight = height
        listener = onGesture
        setupGestureDetectors()
    }

    private fun setupGestureDetectors() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!config.enableTap) return false
                if (isStylusActive) return false
                listener?.invoke(GestureEvent.Tap(e.x, e.y))
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!config.enableTap) return
                if (isStylusActive) return
                listener?.invoke(GestureEvent.LongPress(e.x, e.y))
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!config.enableSwipe) return false
                if (isStylusActive) return false
                if (isFlipping) return false

                val deltaX = e2.x - (e1?.x ?: e2.x)
                val deltaY = e2.y - (e1?.y ?: e2.y)

                val direction = if (abs(deltaX) > abs(deltaY)) {
                    if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                } else {
                    if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                }

                val velocity = sqrt(velocityX * velocityX + velocityY * velocityY)
                if (velocity > SWIPE_VELOCITY_THRESHOLD) {
                    listener?.invoke(GestureEvent.Swipe(direction, velocity))
                    return true
                }
                return false
            }
        })

        scaleDetector = ScaleGestureDetector(context!!, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!config.enablePinch) return false
                if (isStylusActive) return false
                if (isFlipping) return false
                listener?.invoke(
                    GestureEvent.Scale(detector.scaleFactor, detector.focusX, detector.focusY)
                )
                return true
            }
        })
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                activePointerId = event.getPointerId(0)

                val rawX = event.getX(0)
                val rawY = event.getY(0)

                flipStartX = rawX
                flipStartY = rawY
                lastTouchX = rawX
                lastTouchY = rawY
                filteredTouchX = rawX
                filteredTouchY = rawY
                totalFlipDeltaX = 0f
                lockedDirection = false
                trackedVelocityX = 0f
                trackedVelocityY = 0f
                touchHistory.clear()

                isStylusActive = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                currentPressure = if (isStylusActive && config.enableStylus) {
                    event.getPressure(0)
                } else {
                    1.0f
                }

                recordTouchSample(rawX, rawY, event.eventTime, currentPressure,
                    event.getTouchMajor(0))

                if (config.enablePalmRejection && isPalmTouch(rawX, rawY, event)) {
                    return false
                }

                if (config.enableSwipe && !isStylusActive) {
                    initialTouchEdge = detectTouchEdge(rawX, rawY)
                    computeFoldOrigin(rawX, rawY)
                    isFlipping = true
                    listener?.invoke(GestureEvent.FlipStart)
                }

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++

                if (isStylusActive && config.enableStylus) {
                    val pi = pointerIndex
                    val pressure = event.getPressure(pi)
                    listener?.invoke(
                        GestureEvent.StylusPress(event.getX(pi), event.getY(pi), pressure)
                    )
                }

                if (config.requireSingleTouchForFlip && isFlipping) {
                    isFlipping = false
                    listener?.invoke(GestureEvent.FlipEnd)
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return false

                val rawX = event.getX(pi)
                val rawY = event.getY(pi)

                if (isStylusActive && config.enableStylus) {
                    currentPressure = event.getPressure(pi)
                    listener?.invoke(GestureEvent.StylusPress(rawX, rawY, currentPressure))
                    lastTouchX = rawX
                    lastTouchY = rawY
                    return true
                }

                recordTouchSample(rawX, rawY, event.eventTime,
                    event.getPressure(pi), event.getTouchMajor(pi))

                updateTrackedVelocity(event.eventTime)

                var deltaX = rawX - lastTouchX
                var deltaY = rawY - lastTouchY

                if (config.enableJitterFilter) {
                    val filteredDelta = applyJitterFilter(rawX, rawY, deltaX, deltaY)
                    deltaX = filteredDelta.first
                    deltaY = filteredDelta.second
                }

                if (config.enableDirectionLock && isFlipping) {
                    val locked = applyDirectionLock(deltaX, deltaY)
                    deltaX = locked.first
                    deltaY = locked.second
                }

                if (isFlipping && config.enableSwipe) {
                    totalFlipDeltaX += deltaX
                    listener?.invoke(
                        GestureEvent.Pan(
                            deltaX, deltaY,
                            trackedVelocityX, trackedVelocityY,
                            foldOriginX, foldOriginY
                        )
                    )
                }

                if (!isFlipping && config.enablePan && !isStylusActive) {
                    if (abs(deltaX) > PAN_THRESHOLD || abs(deltaY) > PAN_THRESHOLD) {
                        listener?.invoke(GestureEvent.Pan(deltaX, deltaY, trackedVelocityX, trackedVelocityY))
                    }
                }

                lastTouchX = rawX
                lastTouchY = rawY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount--

                if (action == MotionEvent.ACTION_UP ||
                    event.getPointerId(pointerIndex) == activePointerId) {

                    if (isFlipping) {
                        isFlipping = false
                        listener?.invoke(GestureEvent.FlipEnd)
                    }

                    val pi = pointerIndex
                    val upX = event.getX(pi)
                    val upY = event.getY(pi)
                    val totalDeltaX = upX - flipStartX
                    val totalDeltaY = upY - flipStartY
                    val totalDistance = sqrt(totalDeltaX * totalDeltaX +
                                             totalDeltaY * totalDeltaY)

                    if (totalDistance < TAP_THRESHOLD && !isStylusActive && config.enableTap) {
                        listener?.invoke(GestureEvent.Tap(upX, upY))
                    }

                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    isStylusActive = false
                    touchHistory.clear()
                }

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                if (isFlipping) {
                    isFlipping = false
                    listener?.invoke(GestureEvent.FlipEnd)
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                touchHistory.clear()
                return true
            }
        }

        gestureDetector?.onTouchEvent(event)
        scaleDetector?.onTouchEvent(event)

        return true
    }

    private fun recordTouchSample(
        x: Float, y: Float, time: Long,
        pressure: Float = 1f, touchMajor: Float = 0f
    ) {
        touchHistory.addLast(TouchSample(x, y, time, pressure, touchMajor))
        if (touchHistory.size > HISTORY_SIZE + 1) {
            touchHistory.removeFirst()
        }
    }

    private fun updateTrackedVelocity(eventTime: Long) {
        if (touchHistory.size < 2) return

        val latest = touchHistory.last()
        val oldest = touchHistory.first()
        val dt = (latest.time - oldest.time) / 1000f
        if (dt < 0.001f) return

        var totalWeight = 0f
        var weightedVelX = 0f
        var weightedVelY = 0f

        for (i in 1 until touchHistory.size) {
            val cur = touchHistory[i]
            val prev = touchHistory[i - 1]
            val segmentDt = (cur.time - prev.time) / 1000f
            if (segmentDt < 0.001f) continue

            val segDx = cur.x - prev.x
            val segDy = cur.y - prev.y
            val weight = segmentDt * 10f + 0.5f
            totalWeight += weight
            weightedVelX += segDx / segmentDt * weight
            weightedVelY += segDy / segmentDt * weight
        }

        val avgVelX = if (totalWeight > 0f) weightedVelX / totalWeight else 0f
        val avgVelY = if (totalWeight > 0f) weightedVelY / totalWeight else 0f

        trackedVelocityX = trackedVelocityX * VELOCITY_SMOOTHING_ALPHA +
                           avgVelX * (1f - VELOCITY_SMOOTHING_ALPHA)
        trackedVelocityY = trackedVelocityY * VELOCITY_SMOOTHING_ALPHA +
                           avgVelY * (1f - VELOCITY_SMOOTHING_ALPHA)

        if (abs(trackedVelocityX) < VELOCITY_EPSILON) trackedVelocityX = 0f
        if (abs(trackedVelocityY) < VELOCITY_EPSILON) trackedVelocityY = 0f
    }

    private fun applyJitterFilter(
        rawX: Float, rawY: Float,
        rawDeltaX: Float, rawDeltaY: Float
    ): Pair<Float, Float> {
        if (!config.enableJitterFilter) return Pair(rawDeltaX, rawDeltaY)

        val smoothedX = filteredTouchX + rawDeltaX
        val smoothedY = filteredTouchY + rawDeltaY

        val jitterX = rawX - smoothedX
        val jitterY = rawY - smoothedY

        filteredTouchX = smoothedX + jitterX * JITTER_FILTER_STRENGTH
        filteredTouchY = smoothedY + jitterY * JITTER_FILTER_STRENGTH

        val effectiveDeltaX = (filteredTouchX - lastTouchX)
        val effectiveDeltaY = (filteredTouchY - lastTouchY)

        val clampedDx = if (abs(effectiveDeltaX) < MIN_MOVEMENT) 0f else effectiveDeltaX
        val clampedDy = if (abs(effectiveDeltaY) < MIN_MOVEMENT) 0f else effectiveDeltaY

        return Pair(clampedDx, clampedDy)
    }

    private fun applyDirectionLock(
        deltaX: Float, deltaY: Float
    ): Pair<Float, Float> {
        val movement = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (movement < MIN_MOVEMENT) return Pair(0f, 0f)

        if (!lockedDirection && movement > PAN_THRESHOLD) {
            lockedDirection = true
            lockedAxisX = abs(deltaX) >= abs(deltaY)
            lockedAngleRad = atan2(deltaY, deltaX)
        }

        if (!lockedDirection) return Pair(deltaX, deltaY)

        if (lockedAxisX) {
            val perpAmount = abs(deltaY)
            val mainAmount = abs(deltaX)
            val ratio = if (mainAmount > 0.01f) perpAmount / mainAmount else 1f

            if (ratio < 0.5f) {
                return Pair(deltaX, 0f)
            }
            val angle = atan2(deltaY, deltaX)
            if (abs(sin(angle - lockedAngleRad)) < DIRECTION_LOCK_COS) {
                return Pair(deltaX, deltaY * 0.3f)
            }
        } else {
            val perpAmount = abs(deltaX)
            val mainAmount = abs(deltaY)
            val ratio = if (mainAmount > 0.01f) perpAmount / mainAmount else 1f

            if (ratio < 0.5f) {
                return Pair(0f, deltaY)
            }
            val angle = atan2(deltaY, deltaX)
            if (abs(cos(angle - lockedAngleRad)) < DIRECTION_LOCK_COS) {
                return Pair(deltaX * 0.3f, deltaY)
            }
        }

        return Pair(deltaX, deltaY)
    }

    private fun detectTouchEdge(x: Float, y: Float): Float {
        val marginX = screenWidth * EDGE_MARGIN_RATIO
        val marginY = screenHeight * EDGE_MARGIN_RATIO

        return when {
            x > screenWidth - marginX && y > screenHeight - marginY -> 1.5f
            x > screenWidth - marginX -> 1f
            x < marginX -> -1f
            x < marginX && y < marginY -> -1.5f
            else -> 0f
        }
    }

    private fun computeFoldOrigin(x: Float, y: Float) {
        val normX = x / screenWidth
        val normY = y / screenHeight

        foldOriginX = 0.5f + (0.5f - normX) * 0.6f
        foldOriginY = 0.5f + (0.5f - normY) * 0.6f
        foldOriginX = foldOriginX.coerceIn(0f, 1f)
        foldOriginY = foldOriginY.coerceIn(0f, 1f)
    }

    private fun isPalmTouch(x: Float, y: Float, event: MotionEvent): Boolean {
        if (!config.enablePalmRejection) return false

        if (isNearEdge(x, y)) return true

        val touchMajor = event.getTouchMajor(0)
        val touchMinor = event.getTouchMinor(0)
        if (touchMajor > 0 && touchMinor > 0) {
            val aspect = touchMajor / maxOf(touchMinor, 1f)
            if (aspect > 3f) return true

            val touchSize = touchMajor / minOf(screenWidth, screenHeight)
            if (touchSize > PALM_TOUCH_SIZE_THRESHOLD) return true
        }

        if (touchHistory.size >= 2) {
            val oldest = touchHistory.first()
            val dt = (event.eventTime - oldest.time) / 1000f
            if (dt > 0.05f) {
                val dist = sqrt((x - oldest.x) * (x - oldest.x) +
                                (y - oldest.y) * (y - oldest.y))
                val vel = dist / dt
                if (vel > PALM_VELOCITY_THRESHOLD && dist > 100f) return true
            }
        }

        return false
    }

    fun predictGestureEnd(): Float {
        if (!isFlipping) return 0.5f

        val timeToStop = abs(trackedVelocityX) / (PALM_VELOCITY_THRESHOLD * 0.1f + 1f)
        val predictedDelta = trackedVelocityX * minOf(timeToStop, 0.5f)
        return ((totalFlipDeltaX + predictedDelta) / screenWidth).coerceIn(-1f, 1f)
    }

    private fun isNearEdge(x: Float, y: Float): Boolean {
        if (!config.enablePalmRejection) return false
        return x < config.palmRejectionMargin ||
               x > screenWidth - config.palmRejectionMargin ||
               y < config.palmRejectionMargin ||
               y > screenHeight - config.palmRejectionMargin
    }

    fun getVelocityX(): Float = trackedVelocityX
    fun getVelocityY(): Float = trackedVelocityY
    fun getFoldOriginX(): Float = foldOriginX
    fun getFoldOriginY(): Float = foldOriginY
    fun getActivePointerCount(): Int = activePointerCount

    fun updateConfig(config: GestureConfig) {
        this.config = config
        setupGestureDetectors()
    }

    fun setFlipEnabled(enabled: Boolean) {
        config = config.copy(enableSwipe = enabled)
    }

    fun isCurrentlyFlipping(): Boolean = isFlipping
    fun isStylusActive(): Boolean = isStylusActive
    fun getCurrentPressure(): Float = currentPressure

    fun cleanup() {
        listener = null
        gestureDetector = null
        scaleDetector = null
        touchHistory.clear()
    }
}

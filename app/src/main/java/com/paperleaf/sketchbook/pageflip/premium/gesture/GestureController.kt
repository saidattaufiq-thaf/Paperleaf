package com.paperleaf.sketchbook.pageflip.premium.gesture

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs

/**
 * Advanced gesture controller for page flip interactions.
 * Handles swipe, pinch, pan, and stylus input with palm rejection.
 * 
 * Features:
 * - Swipe detection for page flip
 * - Pinch-to-zoom support
 * - Pan gesture recognition
 * - Stylus pressure sensitivity
 * - Palm rejection
 * - Multi-touch handling
 * - Gesture priority management
 */
class GestureController {
    
    companion object {
        private const val TAG = "GestureController"
        
        // Gesture thresholds
        private const val SWIPE_THRESHOLD = 50f      // Minimum distance for swipe
        private const val SWIPE_VELOCITY_THRESHOLD = 100f // Minimum velocity for swipe
        private const val PAN_THRESHOLD = 10f        // Minimum movement for pan
        private const val TAP_THRESHOLD = 10f        // Maximum movement for tap
        
        // Palm rejection zone (pixels from edge)
        private const val PALM_REJECTION_MARGIN = 100f
    }
    
    /**
     * Gesture event types
     */
    sealed class GestureEvent {
        data class Swipe(val direction: SwipeDirection, val velocity: Float) : GestureEvent()
        data class Pan(val deltaX: Float, val deltaY: Float) : GestureEvent()
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
    
    /**
     * Configuration options
     */
    data class GestureConfig(
        val enableSwipe: Boolean = true,
        val enablePinch: Boolean = true,
        val enablePan: Boolean = true,
        val enableTap: Boolean = true,
        val enableStylus: Boolean = true,
        val enablePalmRejection: Boolean = true,
        val palmRejectionMargin: Float = PALM_REJECTION_MARGIN,
        val requireSingleTouchForFlip: Boolean = true
    )
    
    private var config = GestureConfig()
    private var listener: ((GestureEvent) -> Unit)? = null
    
    // Gesture detectors
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null
    
    // State tracking
    private var isFlipping = false
    private var flipStartX = 0f
    private var flipStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isStylusActive = false
    private var currentPressure = 1.0f
    
    // Screen dimensions for palm rejection
    private var screenWidth = 0f
    private var screenHeight = 0f
    
    /**
     * Initialize gesture controller
     */
    fun initialize(
        width: Float,
        height: Float,
        onGesture: (GestureEvent) -> Unit
    ) {
        screenWidth = width
        screenHeight = height
        listener = onGesture
        
        setupGestureDetectors()
    }
    
    /**
     * Setup Android gesture detectors
     */
    private fun setupGestureDetectors() {
        gestureDetector = GestureDetector(null, object : GestureDetector.SimpleOnGestureListener() {
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
                
                // Determine swipe direction
                val direction = if (abs(deltaX) > abs(deltaY)) {
                    if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                } else {
                    if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                }
                
                val velocity = kotlin.math.sqrt(velocityX * velocityX + velocityY * velocityY)
                
                if (velocity > SWIPE_VELOCITY_THRESHOLD) {
                    listener?.invoke(GestureEvent.Swipe(direction, velocity))
                    return true
                }
                
                return false
            }
        })
        
        scaleDetector = ScaleGestureDetector(null, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!config.enablePinch) return false
                if (isStylusActive) return false
                if (isFlipping) return false
                
                listener?.invoke(
                    GestureEvent.Scale(
                        detector.scaleFactor,
                        detector.focusX,
                        detector.focusY
                    )
                )
                return true
            }
        })
    }
    
    /**
     * Process touch event
     * @return true if event was handled
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                flipStartX = event.getX(0)
                flipStartY = event.getY(0)
                lastTouchX = flipStartX
                lastTouchY = flipStartY
                
                // Check for stylus
                isStylusActive = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                currentPressure = if (isStylusActive && config.enableStylus) {
                    event.getPressure(0)
                } else {
                    1.0f
                }
                
                // Check palm rejection
                if (config.enablePalmRejection && isNearEdge(flipStartX, flipStartY)) {
                    return false // Ignore touch near edge (likely palm)
                }
                
                // Start potential flip
                if (config.enableSwipe && !isStylusActive) {
                    isFlipping = true
                    listener?.invoke(GestureEvent.FlipStart)
                }
                
                return true
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch detected
                if (config.requireSingleTouchForFlip && isFlipping) {
                    // Cancel flip on multi-touch
                    isFlipping = false
                    listener?.invoke(GestureEvent.FlipEnd)
                }
                
                if (isStylusActive && config.enableStylus) {
                    val pressure = event.getPressure(pointerIndex)
                    listener?.invoke(GestureEvent.StylusPress(event.getX(pointerIndex), event.getY(pointerIndex), pressure))
                }
                
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false
                
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val deltaX = x - lastTouchX
                val deltaY = y - lastTouchY
                
                // Update pressure for stylus
                if (isStylusActive && config.enableStylus) {
                    currentPressure = event.getPressure(pointerIndex)
                    listener?.invoke(GestureEvent.StylusPress(x, y, currentPressure))
                    return true // Let drawing engine handle stylus
                }
                
                // Handle flip gesture
                if (isFlipping && config.enableSwipe) {
                    // Report pan for flip animation
                    listener?.invoke(GestureEvent.Pan(deltaX, deltaY))
                }
                
                // Handle pan gesture (when not flipping)
                if (!isFlipping && config.enablePan) {
                    if (abs(deltaX) > PAN_THRESHOLD || abs(deltaY) > PAN_THRESHOLD) {
                        listener?.invoke(GestureEvent.Pan(deltaX, deltaY))
                    }
                }
                
                lastTouchX = x
                lastTouchY = y
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (action == MotionEvent.ACTION_UP || 
                    event.getPointerId(pointerIndex) == activePointerId) {
                    
                    // End flip
                    if (isFlipping) {
                        isFlipping = false
                        listener?.invoke(GestureEvent.FlipEnd)
                    }
                    
                    // Check for tap (minimal movement)
                    val totalDeltaX = event.getX(pointerIndex) - flipStartX
                    val totalDeltaY = event.getY(pointerIndex) - flipStartY
                    val totalDistance = kotlin.math.sqrt(totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY)
                    
                    if (totalDistance < TAP_THRESHOLD && !isStylusActive && config.enableTap) {
                        listener?.invoke(GestureEvent.Tap(event.getX(pointerIndex), event.getY(pointerIndex)))
                    }
                    
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    isStylusActive = false
                }
                
                return true
            }
            
            MotionEvent.ACTION_CANCEL -> {
                if (isFlipping) {
                    isFlipping = false
                    listener?.invoke(GestureEvent.FlipEnd)
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        
        // Pass to standard gesture detectors
        gestureDetector?.onTouchEvent(event)
        scaleDetector?.onTouchEvent(event)
        
        return true
    }
    
    /**
     * Check if touch is near screen edge (for palm rejection)
     */
    private fun isNearEdge(x: Float, y: Float): Boolean {
        if (!config.enablePalmRejection) return false
        
        return x < config.palmRejectionMargin ||
               x > screenWidth - config.palmRejectionMargin ||
               y < config.palmRejectionMargin ||
               y > screenHeight - config.palmRejectionMargin
    }
    
    /**
     * Update configuration
     */
    fun updateConfig(config: GestureConfig) {
        this.config = config
        setupGestureDetectors()
    }
    
    /**
     * Enable/disable flip gestures temporarily
     */
    fun setFlipEnabled(enabled: Boolean) {
        config = config.copy(enableSwipe = enabled)
    }
    
    /**
     * Check if currently in flip gesture
     */
    fun isCurrentlyFlipping(): Boolean = isFlipping
    
    /**
     * Check if stylus is active
     */
    fun isStylusActive(): Boolean = isStylusActive
    
    /**
     * Get current stylus pressure (0-1)
     */
    fun getCurrentPressure(): Float = currentPressure
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        listener = null
        gestureDetector = null
        scaleDetector = null
    }
}

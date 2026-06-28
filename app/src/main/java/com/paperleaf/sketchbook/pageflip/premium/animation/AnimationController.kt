package com.paperleaf.sketchbook.pageflip.premium.animation

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animation controller for smooth page flip transitions.
 * Implements custom interpolators and spring physics.
 * 
 * Features:
 * - Spring-based animation
 * - Custom easing functions
 * - Velocity-based completion
 * - Smooth interpolation
 * - Frame-perfect timing
 */
class AnimationController {
    
    companion object {
        private const val TAG = "AnimationController"
        
        // Default animation parameters
        const val DEFAULT_DURATION = 300L         // milliseconds
        const val DEFAULT_SPRING_TENSION = 150f   // N/m
        const val DEFAULT_SPRING_DAMPING = 20f    // N·s/m
        const val MIN_ANIMATION_TIME = 100L       // Minimum animation duration
        const val MAX_ANIMATION_TIME = 600L       // Maximum animation duration
    }
    
    /**
     * Animation state
     */
    data class AnimationState(
        var currentValue: Float = 0f,
        var targetValue: Float = 0f,
        var velocity: Float = 0f,
        var isAnimating: Boolean = false,
        var startTime: Long = 0L,
        var duration: Long = DEFAULT_DURATION
    )
    
    /**
     * Animation types
     */
    enum class AnimationType {
        SPRING,           // Spring physics
        EASE_IN_OUT,      // Smooth start and end
        EASE_OUT,         // Fast start, slow end
        LINEAR,           // Constant speed
        BOUNCE,           // Overshoot and bounce back
        ELASTIC           // Multiple oscillations
    }
    
    private val state = AnimationState()
    private var animationType = AnimationType.SPRING
    
    // Spring parameters
    private var springTension = DEFAULT_SPRING_TENSION
    private var springDamping = DEFAULT_SPRING_DAMPING
    private var mass = 1.0f
    
    // Callback
    private var onUpdate: ((Float) -> Unit)? = null
    private var onComplete: (() -> Unit)? = null
    
    /**
     * Start animation to target value
     */
    fun startAnimation(
        target: Float,
        type: AnimationType = AnimationType.SPRING,
        duration: Long = DEFAULT_DURATION,
        onUpdate: (Float) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        state.targetValue = target
        state.duration = duration.coerceIn(MIN_ANIMATION_TIME, MAX_ANIMATION_TIME)
        state.startTime = System.currentTimeMillis()
        state.isAnimating = true
        state.velocity = 0f
        
        animationType = type
        this.onUpdate = onUpdate
        this.onComplete = onComplete
        
        // Immediate update
        onUpdate(state.currentValue)
    }
    
    /**
     * Start spring animation with velocity
     */
    fun startSpringAnimation(
        target: Float,
        initialVelocity: Float = 0f,
        tension: Float = DEFAULT_SPRING_TENSION,
        damping: Float = DEFAULT_SPRING_DAMPING,
        onUpdate: (Float) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        state.targetValue = target
        state.velocity = initialVelocity
        state.startTime = System.currentTimeMillis()
        state.isAnimating = true
        state.duration = 0 // Spring runs until settled
        
        springTension = tension
        springDamping = damping
        animationType = AnimationType.SPRING
        
        this.onUpdate = onUpdate
        this.onComplete = onComplete
    }
    
    /**
     * Update animation state
     * Call this every frame
     * 
     * @param currentTime Current system time in milliseconds
     * @return true if animation is still running
     */
    fun update(currentTime: Long = System.currentTimeMillis()): Boolean {
        if (!state.isAnimating) return false
        
        when (animationType) {
            AnimationType.SPRING -> updateSpring(currentTime)
            AnimationType.EASE_IN_OUT -> updateEased(currentTime, EaseFunction.EASE_IN_OUT)
            AnimationType.EASE_OUT -> updateEased(currentTime, EaseFunction.EASE_OUT)
            AnimationType.LINEAR -> updateLinear(currentTime)
            AnimationType.BOUNCE -> updateEased(currentTime, EaseFunction.BOUNCE)
            AnimationType.ELASTIC -> updateEased(currentTime, EaseFunction.ELASTIC)
        }
        
        // Check completion
        val isComplete = when (animationType) {
            AnimationType.SPRING -> checkSpringCompletion()
            else -> currentTime >= state.startTime + state.duration
        }
        
        if (isComplete) {
            state.currentValue = state.targetValue
            state.isAnimating = false
            state.velocity = 0f
            onUpdate?.invoke(state.currentValue)
            onComplete?.invoke()
            return false
        }
        
        return true
    }
    
    /**
     * Update using spring physics
     */
    private fun updateSpring(currentTime: Long) {
        val deltaTime = 0.016f // Assume ~60 FPS
        
        // Calculate displacement
        val displacement = state.targetValue - state.currentValue
        
        // Hooke's Law: F = -kx
        val springForce = displacement * springTension
        
        // Damping force: F = -cv
        val dampingForce = state.velocity * springDamping
        
        // Total force
        val totalForce = springForce + dampingForce
        
        // Acceleration: a = F/m
        val acceleration = totalForce / mass
        
        // Integrate velocity (semi-implicit Euler)
        state.velocity += acceleration * deltaTime
        state.velocity *= 0.995f // Small global damping
        
        // Integrate position
        state.currentValue += state.velocity * deltaTime
        
        // Notify update
        onUpdate?.invoke(state.currentValue)
    }
    
    /**
     * Check if spring animation has settled
     */
    private fun checkSpringCompletion(): Boolean {
        val displacement = abs(state.targetValue - state.currentValue)
        val velocity = abs(state.velocity)
        
        // Consider complete when very close to target and nearly stopped
        return displacement < 0.001f && velocity < 0.01f
    }
    
    /**
     * Update using ease function
     */
    private fun updateEased(currentTime: Long, easeFunc: EaseFunction) {
        val elapsed = (currentTime - state.startTime).toFloat()
        val progress = (elapsed / state.duration).coerceIn(0f, 1f)
        
        val easedProgress = applyEase(progress, easeFunc)
        state.currentValue = interpolate(state.currentValue, state.targetValue, easedProgress)
        
        onUpdate?.invoke(state.currentValue)
    }
    
    /**
     * Update with linear interpolation
     */
    private fun updateLinear(currentTime: Long) {
        val elapsed = (currentTime - state.startTime).toFloat()
        val progress = (elapsed / state.duration).coerceIn(0f, 1f)
        
        state.currentValue = interpolate(state.currentValue, state.targetValue, progress)
        
        onUpdate?.invoke(state.currentValue)
    }
    
    /**
     * Apply easing function
     */
    private fun applyEase(t: Float, func: EaseFunction): Float {
        return when (func) {
            EaseFunction.EASE_IN_OUT -> easeInOut(t)
            EaseFunction.EASE_OUT -> easeOut(t)
            EaseFunction.BOUNCE -> easeBounce(t)
            EaseFunction.ELASTIC -> easeElastic(t)
        }
    }
    
    /**
     * Linear interpolation
     */
    private fun interpolate(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
    
    /**
     * Ease-in-out function (smoothstep)
     */
    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            2f * t * t
        } else {
            1f - pow(-2f * t + 2f, 2f) / 2f
        }
    }
    
    /**
     * Ease-out function (quadratic)
     */
    private fun easeOut(t: Float): Float {
        return 1f - (1f - t) * (1f - t)
    }
    
    /**
     * Bounce ease function
     */
    private fun easeBounce(t: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> n1 * (t - 1.5f / d1) * (t - 1.5f / d1) + 0.75f
            t < 2.5f / d1 -> n1 * (t - 2.25f / d1) * (t - 2.25f / d1) + 0.9375f
            else -> n1 * (t - 2.625f / d1) * (t - 2.625f / d1) + 0.984375f
        }
    }
    
    /**
     * Elastic ease function
     */
    private fun easeElastic(t: Float): Float {
        val c4 = (2f * Math.PI.toFloat()) / 3f
        
        return when {
            t == 0f -> 0f
            t == 1f -> 1f
            else -> pow(2f, -10f * t) * sin((t * 10f - 0.75f) * c4) + 1f
        }
    }
    
    // Helper math functions
    private fun abs(x: Float): Float = kotlin.math.abs(x)
    private fun pow(base: Float, exp: Float): Float = kotlin.math.pow(base, exp)
    
    /**
     * Force stop animation
     */
    fun stopAnimation() {
        state.isAnimating = false
        state.velocity = 0f
    }
    
    /**
     * Jump to specific value without animation
     */
    fun jumpTo(value: Float) {
        state.currentValue = value
        state.targetValue = value
        state.velocity = 0f
        state.isAnimating = false
        onUpdate?.invoke(value)
    }
    
    /**
     * Check if currently animating
     */
    fun isAnimating(): Boolean = state.isAnimating
    
    /**
     * Get current animated value
     */
    fun getCurrentValue(): Float = state.currentValue
    
    /**
     * Get target value
     */
    fun getTargetValue(): Float = state.targetValue
    
    /**
     * Set spring parameters
     */
    fun setSpringParameters(tension: Float, damping: Float, mass: Float = 1.0f) {
        springTension = tension
        springDamping = damping
        this.mass = mass
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        stopAnimation()
        onUpdate = null
        onComplete = null
    }
    
    private enum class EaseFunction {
        EASE_IN_OUT,
        EASE_OUT,
        BOUNCE,
        ELASTIC
    }
}

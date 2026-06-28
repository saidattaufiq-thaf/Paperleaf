package com.paperleaf.sketchbook.pageflip.premium.physics

import android.view.MotionEvent
import kotlin.math.*

/**
 * Physics engine for realistic paper simulation.
 * Implements spring-mass-damper system with paper-specific properties.
 * 
 * Features:
 * - Paper stiffness simulation
 * - Elasticity and momentum
 * - Friction damping
 * - Spring-based animation
 * - Velocity tracking
 * - Auto-complete and auto-cancel logic
 */
class PagePhysics {
    
    companion object {
        private const val TAG = "PagePhysics"
        
        // Default physical properties
        const val DEFAULT_STIFFNESS = 0.8f      // Paper rigidity (0-1)
        const val DEFAULT_ELASTICITY = 0.6f     // Bounce back factor (0-1)
        const val DEFAULT_DAMPING = 0.85f       // Velocity decay per frame (0-1)
        const val DEFAULT_MASS = 1.0f           // Relative mass of page
        
        // Thresholds for flip completion
        const val FLIP_COMPLETE_THRESHOLD = 0.6f    // Position to auto-complete flip
        const val FLIP_CANCEL_THRESHOLD = 0.2f      // Position to auto-cancel flip
        
        // Spring constants
        const val SPRING_TENSION = 150f         // Spring force multiplier
        const val SPRING_DAMPING = 20f          // Spring damping factor
    }
    
    /**
     * Current state of the page physics
     */
    data class PhysicsState(
        var position: Float = 0f,           // 0.0 = flat, 1.0 = fully flipped
        var velocity: Float = 0f,           // Current velocity
        var acceleration: Float = 0f,       // Current acceleration
        var curlFactor: Float = 0f,         // How much the page is curled (0-1)
        var bendPosition: Float = 0.5f,     // Where the bend occurs (0-1)
        var isFlipping: Boolean = false,
        var isComplete: Boolean = false
    )
    
    /**
     * Physical properties of the page
     */
    data class PaperProperties(
        val stiffness: Float = DEFAULT_STIFFNESS,
        val elasticity: Float = DEFAULT_ELASTICITY,
        val damping: Float = DEFAULT_DAMPING,
        val mass: Float = DEFAULT_MASS,
        val thickness: Float = 0.001f,      // Simulated paper thickness
        val friction: Float = 0.3f          // Surface friction
    )
    
    private val state = PhysicsState()
    private var properties = PaperProperties()
    
    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    private var touchVelocityX = 0f
    private var touchVelocityY = 0f
    
    // Spring target for auto-animation
    private var springTarget = 0f
    private var useSpring = false
    
    /**
     * Process touch down event
     */
    fun onTouchDown(x: Float, y: Float, time: Long) {
        lastTouchX = x
        lastTouchY = y
        lastTouchTime = time
        touchVelocityX = 0f
        touchVelocityY = 0f
        
        // Start flipping state
        state.isFlipping = true
        state.isComplete = false
    }
    
    /**
     * Process touch move event
     * @return true if state changed and render needed
     */
    fun onTouchMove(x: Float, y: Float, time: Long): Boolean {
        val deltaX = x - lastTouchX
        val deltaY = y - lastTouchY
        val deltaTime = max(1, time - lastTouchTime)
        
        // Calculate touch velocity
        touchVelocityX = deltaX / deltaTime * 1000f
        touchVelocityY = deltaY / deltaTime * 1000f
        
        // Convert horizontal movement to page position
        // Sensitivity depends on screen width (normalized to 0-1)
        val sensitivity = 0.002f
        val deltaPosition = deltaX * sensitivity
        
        // Apply new position with constraints
        val newPosition = (state.position + deltaPosition).coerceIn(0f, 1f)
        
        // Calculate curl based on position and velocity
        val speed = abs(touchVelocityX) / 10000f
        state.curlFactor = calculateCurlFactor(newPosition, speed)
        state.bendPosition = calculateBendPosition(newPosition)
        
        // Store velocity for release
        state.velocity = touchVelocityX * 0.0001f
        
        lastTouchX = x
        lastTouchY = y
        lastTouchTime = time
        
        return true
    }
    
    /**
     * Process touch up event
     * Determines whether to complete, cancel, or continue flip
     */
    fun onTouchUp(x: Float, y: Float, time: Long): FlipDecision {
        val decision = determineFlipDecision()
        
        when (decision) {
            FlipDecision.COMPLETE -> {
                enableSpring(1f)
            }
            FlipDecision.CANCEL -> {
                enableSpring(0f)
            }
            FlipDecision.CONTINUE -> {
                // Continue with current momentum
                useSpring = false
            }
        }
        
        state.isFlipping = false
        return decision
    }
    
    /**
     * Determine what should happen on touch release
     */
    private fun determineFlipDecision(): FlipDecision {
        val pos = state.position
        val vel = state.velocity
        
        // Check velocity-based completion
        if (vel > 0.5f && pos > 0.1f) {
            return FlipDecision.COMPLETE
        }
        if (vel < -0.5f && pos < 0.9f) {
            return FlipDecision.CANCEL
        }
        
        // Check position-based completion
        if (pos > FLIP_COMPLETE_THRESHOLD) {
            return FlipDecision.COMPLETE
        }
        if (pos < FLIP_CANCEL_THRESHOLD) {
            return FlipDecision.CANCEL
        }
        
        return FlipDecision.CONTINUE
    }
    
    /**
     * Update physics simulation
     * Should be called every frame
     * 
     * @param deltaTime Time since last update in seconds
     */
    fun update(deltaTime: Float) {
        if (useSpring) {
            updateSpring(deltaTime)
        } else {
            updateMomentum(deltaTime)
        }
        
        // Clamp position
        state.position = state.position.coerceIn(0f, 1f)
        
        // Update curl based on current state
        state.curlFactor = calculateCurlFactor(state.position, abs(state.velocity))
        state.bendPosition = calculateBendPosition(state.position)
        
        // Check completion
        state.isComplete = state.position >= 1f || state.position <= 0f
    }
    
    /**
     * Update using spring physics
     */
    private fun updateSpring(deltaTime: Float) {
        val displacement = springTarget - state.position
        
        // Hooke's law: F = -kx
        val springForce = displacement * SPRING_TENSION
        
        // Damping: F = -cv
        val dampingForce = state.velocity * SPRING_DAMPING
        
        // Total force
        val totalForce = springForce + dampingForce
        
        // Acceleration: a = F/m
        state.acceleration = totalForce / properties.mass
        
        // Integrate velocity
        state.velocity += state.acceleration * deltaTime
        
        // Apply damping
        state.velocity *= properties.damping
        
        // Integrate position
        state.position += state.velocity * deltaTime
        
        // Stop if close enough
        if (abs(displacement) < 0.001f && abs(state.velocity) < 0.01f) {
            state.position = springTarget
            state.velocity = 0f
            state.acceleration = 0f
            useSpring = false
        }
    }
    
    /**
     * Update using momentum physics
     */
    private fun updateMomentum(deltaTime: Float) {
        // Apply friction/damping
        state.velocity *= properties.damping
        
        // Apply gravity-like force toward nearest end
        val gravityForce = if (state.position > 0.5f) 0.1f else -0.1f
        state.velocity += gravityForce * deltaTime
        
        // Update position
        state.position += state.velocity * deltaTime
        
        // Stop if very slow
        if (abs(state.velocity) < 0.001f) {
            state.velocity = 0f
        }
    }
    
    /**
     * Calculate curl factor based on position and speed
     */
    private fun calculateCurlFactor(position: Float, speed: Float): Float {
        // Base curl from position (max at middle of flip)
        val positionCurl = sin(position * Math.PI).toFloat() * properties.stiffness
        
        // Additional curl from speed (dynamic effect)
        val speedCurl = speed.coerceIn(0f, 1f) * 0.3f
        
        return (positionCurl + speedCurl).coerceIn(0f, 1f)
    }
    
    /**
     * Calculate bend position along the page
     */
    private fun calculateBendPosition(position: Float): Float {
        // Bend moves from spine outward as flip progresses
        return 0.3f + position * 0.4f
    }
    
    /**
     * Enable spring animation toward target
     */
    fun enableSpring(target: Float) {
        springTarget = target
        useSpring = true
    }
    
    /**
     * Reset physics state
     */
    fun reset() {
        state.position = 0f
        state.velocity = 0f
        state.acceleration = 0f
        state.curlFactor = 0f
        state.bendPosition = 0.5f
        state.isFlipping = false
        state.isComplete = false
        useSpring = false
    }
    
    /**
     * Set paper physical properties
     */
    fun setPaperProperties(
        stiffness: Float? = null,
        elasticity: Float? = null,
        damping: Float? = null,
        mass: Float? = null
    ) {
        properties = properties.copy(
            stiffness = stiffness ?: properties.stiffness,
            elasticity = elasticity ?: properties.elasticity,
            damping = damping ?: properties.damping,
            mass = mass ?: properties.mass
        )
    }
    
    /**
     * Get current physics state
     */
    fun getState(): PhysicsState = state.copy()
    
    /**
     * Manually set position (for programmatic control)
     */
    fun setPosition(position: Float) {
        state.position = position.coerceIn(0f, 1f)
        state.curlFactor = calculateCurlFactor(state.position, 0f)
        state.bendPosition = calculateBendPosition(state.position)
    }
    
    /**
     * Get current curl factor for rendering
     */
    fun getCurlFactor(): Float = state.curlFactor
    
    /**
     * Get current bend position for rendering
     */
    fun getBendPosition(): Float = state.bendPosition
    
    /**
     * Check if flip is complete
     */
    fun isFlipComplete(): Boolean = state.isComplete
    
    /**
     * Check if page is currently being manipulated
     */
    fun isInteracting(): Boolean = state.isFlipping || useSpring || abs(state.velocity) > 0.001f
    
    enum class FlipDecision {
        COMPLETE,   // Auto-complete the flip
        CANCEL,     // Auto-cancel back to start
        CONTINUE    // Continue with momentum
    }
}

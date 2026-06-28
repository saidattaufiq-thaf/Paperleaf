package com.paperleaf.sketchbook.pageflip.premium.physics

import kotlin.math.*

class PagePhysics {

    companion object {
        const val DEFAULT_STIFFNESS = 0.8f
        const val DEFAULT_ELASTICITY = 0.6f
        const val DEFAULT_DAMPING = 0.85f
        const val DEFAULT_MASS = 1.0f

        private const val VELOCITY_SMOOTHING_ALPHA = 0.80f
        private const val MAX_VELOCITY = 15f
        private const val VELOCITY_EPSILON = 0.001f
        private const val POSITION_EPSILON = 0.0001f

        const val SPRING_TENSION = 150f
        const val SPRING_DAMPING = 22f

        const val COMPLETION_THRESHOLD = 0.80f
        const val RETURN_THRESHOLD = 0.20f
        const val SNAP_THRESHOLD = 0.03f

        private const val CURL_BASE_RADIUS = 0.5f
        private const val TOUCH_SENSITIVITY = 0.002f
        private const val TOUCH_VELOCITY_SCALE = 0.00015f
        private const val DT_MAX = 0.033f
    }

    data class PhysicsState(
        var position: Float = 0f,
        var velocity: Float = 0f,
        var acceleration: Float = 0f,
        var curlFactor: Float = 0f,
        var bendPosition: Float = 0.5f,
        var isFlipping: Boolean = false,
        var isComplete: Boolean = false
    )

    data class PaperProperties(
        val stiffness: Float = DEFAULT_STIFFNESS,
        val elasticity: Float = DEFAULT_ELASTICITY,
        val damping: Float = DEFAULT_DAMPING,
        val mass: Float = DEFAULT_MASS,
        val thickness: Float = 0.001f,
        val friction: Float = 0.3f
    )

    private val state = PhysicsState()
    private var properties = PaperProperties()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    private var touchVelocityX = 0f
    private var touchVelocityY = 0f
    private var isTouching = false

    private var springTarget = 0f
    private var useSpring = false

    private var curlRadius = CURL_BASE_RADIUS
    private var curlAngle = 0f

    fun onTouchDown(x: Float, y: Float, time: Long) {
        lastTouchX = x
        lastTouchY = y
        lastTouchTime = time
        touchVelocityX = 0f
        touchVelocityY = 0f
        isTouching = true
        useSpring = false
        state.isFlipping = true
        state.isComplete = false
        state.velocity = 0f
    }

    fun onTouchMove(x: Float, y: Float, time: Long): Boolean {
        val deltaX = x - lastTouchX
        val deltaY = y - lastTouchY
        val deltaTimeMs = max(1, time - lastTouchTime)
        val dt = deltaTimeMs / 1000f

        val instVelX = if (dt > 1e-6f) deltaX / dt else 0f
        val instVelY = if (dt > 1e-6f) deltaY / dt else 0f

        touchVelocityX = touchVelocityX * VELOCITY_SMOOTHING_ALPHA +
                         instVelX * (1f - VELOCITY_SMOOTHING_ALPHA)
        touchVelocityY = touchVelocityY * VELOCITY_SMOOTHING_ALPHA +
                         instVelY * (1f - VELOCITY_SMOOTHING_ALPHA)

        val deltaPosition = deltaX * TOUCH_SENSITIVITY
        val newPosition = (state.position + deltaPosition).coerceIn(0f, 1f)
        state.position = newPosition

        state.velocity = (touchVelocityX * TOUCH_VELOCITY_SCALE)
            .coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        lastTouchX = x
        lastTouchY = y
        lastTouchTime = time

        return true
    }

    fun onTouchUp(x: Float, y: Float, time: Long): FlipDecision {
        isTouching = false
        val decision = determineFlipDecision()

        when (decision) {
            FlipDecision.COMPLETE -> {
                state.velocity = state.velocity.coerceAtLeast(0.2f)
                enableSpring(1f)
            }
            FlipDecision.CANCEL -> {
                state.velocity = state.velocity.coerceAtMost(-0.2f)
                enableSpring(0f)
            }
            FlipDecision.CONTINUE -> {
                useSpring = false
            }
        }

        state.isFlipping = false
        return decision
    }

    private fun determineFlipDecision(): FlipDecision {
        val pos = state.position
        val vel = state.velocity

        if (vel > 0.4f && pos > 0.1f) return FlipDecision.COMPLETE
        if (vel < -0.4f && pos < 0.9f) return FlipDecision.CANCEL

        if (pos > COMPLETION_THRESHOLD) return FlipDecision.COMPLETE
        if (pos < RETURN_THRESHOLD) return FlipDecision.CANCEL

        if (pos > 1f - SNAP_THRESHOLD) return FlipDecision.COMPLETE
        if (pos < SNAP_THRESHOLD) return FlipDecision.CANCEL

        return FlipDecision.CONTINUE
    }

    fun update(deltaTime: Float) {
        val dt = deltaTime.coerceIn(0f, DT_MAX)

        if (useSpring) {
            updateSpring(dt)
        } else if (!isTouching) {
            updateMomentum(dt)
        }

        state.position = state.position.coerceIn(0f, 1f)
        state.velocity = state.velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        if (state.position <= POSITION_EPSILON && state.velocity < 0f) {
            state.velocity = 0f
        }
        if (state.position >= 1f - POSITION_EPSILON && state.velocity > 0f) {
            state.velocity = 0f
        }

        curlAngle = state.position * Math.PI.toFloat()
        curlRadius = CURL_BASE_RADIUS * (1f + (1f - properties.stiffness) * 1.5f)
        state.velocity = state.velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        state.curlFactor = calculateCurlFactor(state.position, abs(state.velocity))
        state.bendPosition = calculateBendPosition(state.position)

        state.isComplete = state.position >= 1f || state.position <= 0f
    }

    private fun updateSpring(dt: Float) {
        val displacement = springTarget - state.position

        val effectiveTension = SPRING_TENSION * (1f + properties.stiffness * 0.4f)
        val elasticForce = displacement * effectiveTension

        val effectiveDamping = SPRING_DAMPING * (1f + properties.damping * 1.5f)
        val dampingForce = state.velocity * effectiveDamping

        val frictionForce = -sign(state.velocity) *
            min(abs(state.velocity) * properties.friction * 8f, abs(elasticForce))

        val totalForce = elasticForce - dampingForce + frictionForce
        state.acceleration = totalForce / properties.mass

        state.velocity += state.acceleration * dt
        state.position += state.velocity * dt

        if (abs(displacement) < 0.005f && abs(state.velocity) < 0.05f) {
            val snap = springTarget
            state.position = snap
            state.velocity = 0f
            state.acceleration = 0f
            useSpring = false
        }
    }

    private fun updateMomentum(dt: Float) {
        val speed = abs(state.velocity)

        val frictionDecel = -sign(state.velocity) *
            properties.friction * speed * speed * 12f

        val biasCenter = 0.45f
        val elasticForce = (biasCenter - state.position) * properties.stiffness * 2.5f

        val totalForce = frictionDecel + elasticForce
        state.acceleration = totalForce / properties.mass

        state.velocity += state.acceleration * dt
        state.position += state.velocity * dt

        if (speed < VELOCITY_EPSILON) {
            state.velocity = 0f
            state.acceleration = 0f
        }
    }

    private fun calculateCurlFactor(position: Float, speed: Float): Float {
        val positionCurl = sin(position * Math.PI.toFloat()) * properties.stiffness
        val speedCurl = speed.coerceIn(0f, 1f) * 0.25f * properties.elasticity
        return (positionCurl + speedCurl).coerceIn(0f, 1f)
    }

    private fun calculateBendPosition(position: Float): Float {
        return 0.3f + position * 0.4f
    }

    fun getCurlRadius(): Float = curlRadius
    fun getCurlAngle(): Float = curlAngle

    fun enableSpring(target: Float) {
        springTarget = target
        useSpring = true
    }

    fun reset() {
        state.position = 0f
        state.velocity = 0f
        state.acceleration = 0f
        state.curlFactor = 0f
        state.bendPosition = 0.3f
        state.isFlipping = false
        state.isComplete = false
        useSpring = false
        isTouching = false
        touchVelocityX = 0f
        touchVelocityY = 0f
        curlRadius = CURL_BASE_RADIUS
        curlAngle = 0f
    }

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

    fun getState(): PhysicsState = state.copy()

    fun setPosition(position: Float) {
        state.position = position.coerceIn(0f, 1f)
        state.velocity = 0f
        state.acceleration = 0f
        curlAngle = state.position * Math.PI.toFloat()
        curlRadius = CURL_BASE_RADIUS * (1f + (1f - properties.stiffness) * 1.5f)
        state.curlFactor = calculateCurlFactor(state.position, 0f)
        state.bendPosition = calculateBendPosition(state.position)
    }

    fun getCurlFactor(): Float = state.curlFactor
    fun getBendPosition(): Float = state.bendPosition
    fun isFlipComplete(): Boolean = state.isComplete
    fun isInteracting(): Boolean = state.isFlipping || useSpring || abs(state.velocity) > VELOCITY_EPSILON

    enum class FlipDecision {
        COMPLETE,
        CANCEL,
        CONTINUE
    }
}

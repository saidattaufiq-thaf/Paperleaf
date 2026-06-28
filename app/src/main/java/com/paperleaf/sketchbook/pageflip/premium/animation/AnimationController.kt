package com.paperleaf.sketchbook.pageflip.premium.animation

import kotlin.math.abs
import kotlin.math.sqrt

class AnimationController {

    companion object {
        private const val MAX_DT = 0.05f
        private const val MIN_DT = 0.001f

        val FLIP_CONFIG = SpringConfig(
            stiffness = 180f,
            dampingRatio = 0.78f,
            mass = 1.0f,
            restitution = 0.35f,
            settleDisplacement = 0.003f,
            settleVelocity = 0.008f
        )

        val QUICK_FLIP_CONFIG = SpringConfig(
            stiffness = 320f,
            dampingRatio = 0.85f,
            mass = 1.0f,
            restitution = 0.25f,
            settleDisplacement = 0.003f,
            settleVelocity = 0.008f
        )

        val SOFT_RETURN_CONFIG = SpringConfig(
            stiffness = 120f,
            dampingRatio = 0.65f,
            mass = 1.0f,
            restitution = 0.40f,
            settleDisplacement = 0.003f,
            settleVelocity = 0.008f
        )

        val BOUNCE_CONFIG = SpringConfig(
            stiffness = 260f,
            dampingRatio = 0.30f,
            mass = 1.0f,
            restitution = 0.55f,
            settleDisplacement = 0.005f,
            settleVelocity = 0.010f
        )
    }

    data class SpringConfig(
        val stiffness: Float,
        val dampingRatio: Float,
        val mass: Float,
        val restitution: Float,
        val settleDisplacement: Float,
        val settleVelocity: Float
    )

    private var currentValue = 0f
    private var targetValue = 0f
    private var velocity = 0f
    private var animating = false
    private var lastFrameTime = 0L

    private var config = FLIP_CONFIG
    private var omega = 0f
    private var dampingCoef = 0f

    private var onUpdate: ((Float) -> Unit)? = null
    private var onComplete: (() -> Unit)? = null

    fun startSpringAnimation(
        target: Float,
        initialVelocity: Float = 0f,
        tension: Float = FLIP_CONFIG.stiffness,
        damping: Float = FLIP_CONFIG.dampingRatio * 2f * sqrt(FLIP_CONFIG.stiffness),
        onUpdate: (Float) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        val cCritical = 2f * sqrt(tension * FLIP_CONFIG.mass)
        val zeta = (damping / cCritical).coerceIn(0.02f, 5f)
        val cfg = SpringConfig(
            stiffness = tension,
            dampingRatio = zeta,
            mass = FLIP_CONFIG.mass,
            restitution = FLIP_CONFIG.restitution,
            settleDisplacement = FLIP_CONFIG.settleDisplacement,
            settleVelocity = FLIP_CONFIG.settleVelocity
        )
        startSpring(target, initialVelocity, cfg, onUpdate, onComplete)
    }

    fun startSpring(
        target: Float,
        initialVelocity: Float = 0f,
        config: SpringConfig = FLIP_CONFIG,
        onUpdate: (Float) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        this.config = config
        omega = sqrt(config.stiffness / config.mass)
        dampingCoef = 2f * config.mass * omega * config.dampingRatio

        targetValue = target
        velocity += initialVelocity * 0.6f
        animating = true
        lastFrameTime = System.nanoTime()

        this.onUpdate = onUpdate
        this.onComplete = onComplete

        onUpdate(currentValue)
    }

    fun update(currentTime: Long = System.currentTimeMillis()): Boolean {
        if (!animating) return false

        val now = System.nanoTime()
        val dt = if (lastFrameTime > 0) {
            ((now - lastFrameTime) / 1_000_000_000f).coerceIn(MIN_DT, MAX_DT)
        } else MIN_DT
        lastFrameTime = now

        stepSpring(dt)
        handleBoundary()
        notifyUpdate()

        if (checkSettled()) {
            currentValue = targetValue
            velocity = 0f
            animating = false
            onUpdate?.invoke(currentValue)
            onComplete?.invoke()
            return false
        }

        return true
    }

    private fun stepSpring(dt: Float) {
        val displacement = currentValue - targetValue
        val springForce = -config.stiffness * displacement
        val dampingForce = -dampingCoef * velocity
        val acceleration = (springForce + dampingForce) / config.mass
        velocity += acceleration * dt
        currentValue += velocity * dt
    }

    private fun handleBoundary() {
        if (currentValue < 0f) {
            currentValue = -currentValue
            velocity = -velocity * config.restitution
            if (abs(velocity) < 0.002f) velocity = 0f
        } else if (currentValue > 1f) {
            currentValue = 2f - currentValue
            velocity = -velocity * config.restitution
            if (abs(velocity) < 0.002f) velocity = 0f
        }
    }

    private fun checkSettled(): Boolean {
        if (abs(currentValue - targetValue) > config.settleDisplacement) return false
        if (abs(velocity) > config.settleVelocity) return false
        val energy = 0.5f * config.mass * velocity * velocity
                + 0.5f * config.stiffness * (currentValue - targetValue) * (currentValue - targetValue)
        return energy < 0.000015f
    }

    private fun notifyUpdate() {
        onUpdate?.invoke(currentValue)
    }

    fun stopAnimation() {
        animating = false
        velocity = 0f
    }

    fun jumpTo(value: Float) {
        currentValue = value
        targetValue = value
        velocity = 0f
        animating = false
        onUpdate?.invoke(value)
    }

    fun isAnimating(): Boolean = animating
    fun getCurrentValue(): Float = currentValue
    fun getTargetValue(): Float = targetValue
    fun getVelocity(): Float = velocity

    fun setSpringParameters(tension: Float, damping: Float, mass: Float) {
        config = config.copy(stiffness = tension, mass = mass)
        val cCritical = 2f * sqrt(tension * mass)
        val zeta = (damping / cCritical).coerceIn(0.02f, 5f)
        config = config.copy(dampingRatio = zeta)
        omega = sqrt(config.stiffness / config.mass)
        dampingCoef = 2f * config.mass * omega * config.dampingRatio
    }

    fun cleanup() {
        stopAnimation()
        onUpdate = null
        onComplete = null
    }
}

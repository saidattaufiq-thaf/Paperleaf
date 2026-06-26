package com.paperleaf.sketchbook.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.paperleaf.sketchbook.databinding.ActivitySplashBinding
import com.paperleaf.sketchbook.utils.TransitionHelper

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val tagline = "Crafted by Said\u0040taufiq"
    private var charIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lottie logo: spring scale up
        binding.lottieLogo.apply {
            scaleX = 0.6f
            scaleY = 0.6f
            alpha = 0f
            postDelayed({
                SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
                    spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    spring.stiffness = SpringForce.STIFFNESS_LOW
                    start()
                }
                SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
                    spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    spring.stiffness = SpringForce.STIFFNESS_LOW
                    start()
                }
                animate().alpha(0.9f).setDuration(400).start()
            }, 100)
        }

        // Logo text: spring slide up + fade
        binding.tvLogo.apply {
            translationY = 40f
            postDelayed({
                alpha = 1f
                SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                    spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    spring.stiffness = SpringForce.STIFFNESS_LOW
                    start()
                }
            }, 500)
        }

        // Tagline: spring in after logo
        binding.tvTagline.postDelayed({
            binding.tvTagline.animate().alpha(1f).setDuration(300).start()
            handler.postDelayed({ typeNext() }, 350)
        }, 1000)
    }

    private fun typeNext() {
        if (charIndex <= tagline.length) {
            binding.tvTagline.text = tagline.substring(0, charIndex++)
            handler.postDelayed({ typeNext() }, 55)
        } else {
            handler.postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                @Suppress("DEPRECATION")
                TransitionHelper.morphForward(this@SplashActivity)
                finish()
            }, 700)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

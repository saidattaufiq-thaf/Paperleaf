package com.paperleaf.sketchbook.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
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

        // Logo: fade in + geser ke atas
        binding.tvLogo
            .animate()
            .alpha(1f)
            .translationYBy(-24f)
            .setDuration(900)
            .withEndAction {
                binding.tvTagline.animate().alpha(1f).setDuration(300).start()
                handler.postDelayed({ typeNext() }, 350)
            }.start()
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
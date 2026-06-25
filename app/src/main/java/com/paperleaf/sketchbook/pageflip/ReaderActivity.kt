package com.paperleaf.sketchbook.pageflip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.paperleaf.sketchbook.databinding.ActivityReaderBinding
import com.eschao.android.widget.pageflip.PageFlipView
import com.eschao.android.widget.pageflip.OnPageFlipListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.paperleaf.sketchbook.utils.TransitionHelper

class ReaderActivity : AppCompatActivity() {

    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_START_SPREAD = "extra_start_spread"
    }

    private lateinit var binding: ActivityReaderBinding
    private val MAX_TEXTURE_SIZE = 2048

    private var bookId = 0L
    private var startSpread = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0)
        startSpread = intent.getIntExtra(EXTRA_START_SPREAD, 0)

        setupPageFlipListener()
        loadInitialPages()
    }

    private fun setupPageFlipListener() {
        binding.pageFlipView.setOnPageFlipListener(object : OnPageFlipListener {
            override fun canFlipForward(): Boolean {
                // TODO: Check if there are more pages to flip forward
                return true
            }

            override fun canFlipBackward(): Boolean {
                // TODO: Check if there are previous pages to flip backward
                return false // Disable backward for now
            }
        })
    }

    private fun loadInitialPages() {
        lifecycleScope.launch {
            // Load pages from your Room database or assets
            val page1 = withContext(Dispatchers.IO) {
                loadAndScaleBitmap("path_to_page_1.jpg")
            }
            val page2 = withContext(Dispatchers.IO) {
                loadAndScaleBitmap("path_to_page_2.jpg")
            }

            if (page1 != null && page2 != null) {
                binding.pageFlipView.setFirstPage(page1, page2)
            }
        }
    }

    private fun loadAndScaleBitmap(filePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)

            var sampleSize = 1
            while (options.outWidth / sampleSize > MAX_TEXTURE_SIZE || 
                   options.outHeight / sampleSize > MAX_TEXTURE_SIZE) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: Exception) {
            Log.e("PageFlip", "Error loading: $filePath", e)
            null
        }
    }

    override fun onPause() {
        super.onPause()
        binding.pageFlipView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.pageFlipView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.pageFlipView.onDelete()
    }
}
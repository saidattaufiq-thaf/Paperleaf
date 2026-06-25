package com.paperleaf.sketchbook.pageflip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.paperleaf.sketchbook.databinding.ActivityReaderBinding
import com.paperleaf.sketchbook.pageflip.PageFlipView
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
            // Logic to provide 3 distinct textures for a realistic 3D flip:
            // 1. Current Front: What you see now.
            // 2. Current Back: What you see on the underside of the page being flipped.
            // 3. Next Front: What you see revealed underneath the flipping page.
            
            val currentPageFront = withContext(Dispatchers.IO) {
                // Load actual page content here
                loadAndScaleBitmap("path_to_page_1.jpg") ?: createPlaceholderBitmap("Page 1 (Front)", android.graphics.Color.parseColor("#FFF8E1"))
            }
            val currentPageBack = withContext(Dispatchers.IO) {
                // The back of the paper should look different (e.g. slightly darker or blank)
                loadAndScaleBitmap("path_to_page_1_back.jpg") ?: createPlaceholderBitmap("Page 1 (Back Side)", android.graphics.Color.parseColor("#F5F5F5"))
            }
            val nextPageFront = withContext(Dispatchers.IO) {
                // The page that stays static underneath
                loadAndScaleBitmap("path_to_page_2.jpg") ?: createPlaceholderBitmap("Page 2 (Underneath)", android.graphics.Color.parseColor("#E1F5FE"))
            }

            binding.pageFlipView.setPages(currentPageFront, currentPageBack, nextPageFront)
        }
    }

    private fun createPlaceholderBitmap(text: String, bgColor: Int): Bitmap {
        val b = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint()
        p.color = bgColor
        c.drawRect(0f, 0f, 800f, 1200f, p)
        p.color = android.graphics.Color.BLACK
        p.textSize = 50f
        p.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText(text, 400f, 600f, p)
        return b
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
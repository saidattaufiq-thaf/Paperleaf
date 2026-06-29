package com.paperleaf.sketchbook.pageflip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.paperleaf.sketchbook.databinding.ActivityReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.paperleaf.sketchbook.utils.TransitionHelper

/**
 * ReaderActivity dengan animasi flip premium seperti WeTransfer Paper
 * Menggunakan PremiumPageFlipView dengan fitur:
 * - Curl deformation dengan spring physics
 * - Dynamic shadows dan lighting effects
 * - Gesture-based interaction (swipe, pan, tap)
 * - Smooth 60/120fps animation
 */
class ReaderActivity : AppCompatActivity() {

    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_START_SPREAD = "extra_start_spread"
        private const val TAG = "ReaderActivity"
    }

    private lateinit var binding: ActivityReaderBinding
    private val MAX_TEXTURE_SIZE = 2048

    private var bookId = 0L
    private var startSpread = 0
    
    // Sample page data - ganti dengan data buku yang sebenarnya
    private val pageImages = mutableListOf<Bitmap>()
    private var currentPageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0)
        startSpread = intent.getIntExtra(EXTRA_START_SPREAD, 0)

        setupPageFlipListener()
        loadInitialPages()
    }

    /**
     * Setup listener untuk event page flip
     */
    private fun setupPageFlipListener() {
        binding.pageFlipView.onFlipProgress = { progress ->
            Log.d(TAG, "Flip progress: $progress")
        }
        
        binding.pageFlipView.onFlipComplete = { isForward ->
            Log.d(TAG, "Flip completed: forward=$isForward")
            if (isForward) {
                loadNextPage()
            } else {
                loadPreviousPage()
            }
        }
    }

    /**
     * Load halaman awal (spread pertama)
     */
    private fun loadInitialPages() {
        lifecycleScope.launch {
            // Load sample pages - ganti dengan path gambar yang sebenarnya
            val page1 = withContext(Dispatchers.IO) {
                loadAndScaleBitmapFromAssets("pages/page_001.jpg")
            }
            val page2 = withContext(Dispatchers.IO) {
                loadAndScaleBitmapFromAssets("pages/page_002.jpg")
            }

            if (page1 != null && page2 != null) {
                pageImages.clear()
                pageImages.add(page1)
                pageImages.add(page2)
                
                // Set halaman depan dan belakang untuk spread pertama
                binding.pageFlipView.setPages(page1, page2)
                Log.d(TAG, "Initial pages loaded successfully")
            } else {
                Log.e(TAG, "Failed to load initial pages")
            }
        }
    }

    /**
     * Load halaman berikutnya saat flip selesai
     */
    private fun loadNextPage() {
        lifecycleScope.launch {
            val nextIndex = currentPageIndex + 2
            if (nextIndex < getTotalPages()) {
                val nextPage = withContext(Dispatchers.IO) {
                    loadAndScaleBitmapFromAssets("pages/page_${String.format("%03d", nextIndex + 1)}.jpg")
                }

                nextPage?.let {
                    pageImages.add(it)
                    currentPageIndex = nextIndex
                    
                    // Update texture untuk halaman berikutnya
                    binding.pageFlipView.setPageTextures(listOf(it))
                    Log.d(TAG, "Next page loaded: $nextIndex")
                }
            }
        }
    }

    /**
     * Load halaman sebelumnya saat flip balik
     */
    private fun loadPreviousPage() {
        lifecycleScope.launch {
            val prevIndex = currentPageIndex - 2
            if (prevIndex >= 0) {
                val prevPage = withContext(Dispatchers.IO) {
                    loadAndScaleBitmapFromAssets("pages/page_${String.format("%03d", prevIndex + 1)}.jpg")
                }

                prevPage?.let {
                    currentPageIndex = prevIndex
                    
                    // Update texture untuk halaman sebelumnya
                    binding.pageFlipView.setPages(prevPage, pageImages.getOrElse(currentPageIndex + 1) { it })
                    Log.d(TAG, "Previous page loaded: $prevIndex")
                }
            }
        }
    }

    /**
     * Dapatkan total jumlah halaman dari buku
     */
    private fun getTotalPages(): Int {
        // Ganti dengan logika pengambilan jumlah halaman yang sebenarnya
        return 20 // Contoh: 20 halaman
    }

    /**
     * Load dan scale bitmap dari assets folder
     */
    private fun loadAndScaleBitmapFromAssets(filePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { 
                inJustDecodeBounds = true
            }
            
            assets.open(filePath).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            var sampleSize = 1
            while (options.outWidth / sampleSize > MAX_TEXTURE_SIZE || 
                   options.outHeight / sampleSize > MAX_TEXTURE_SIZE) {
                sampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            assets.open(filePath).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image from assets: $filePath", e)
            null
        }
    }

    /**
     * Load dan scale bitmap dari file path
     */
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
            Log.e(TAG, "Error loading: $filePath", e)
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
        binding.pageFlipView.cleanup()
        pageImages.forEach { it.recycle() }
        pageImages.clear()
        Log.d(TAG, "ReaderActivity destroyed, resources cleaned up")
    }
}
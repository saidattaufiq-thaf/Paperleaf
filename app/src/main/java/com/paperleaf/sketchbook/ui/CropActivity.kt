package com.paperleaf.sketchbook.ui

import android.app.Activity
import android.content.Context // <-- Tambahan import Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.utils.TransitionHelper
import kotlin.math.*

class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI    = "extra_uri"
        const val RESULT_PATH  = "result_path"
        private const val MIN_CROP = 80f
    }

    // <-- Enum dipindahkan ke sini (di luar inner class)
    private enum class Handle { NONE, MOVE, TL, TR, BL, BR }

    private lateinit var cropView: CropView
    private lateinit var originalBitmap: Bitmap

    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)
        } ?: run { finish(); return }
        originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
        } else {
            val input = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(input).also { input?.close() }
        }

        // Layout programmatic — tidak perlu XML baru
        val root = FrameLayout(this)
        cropView = CropView(this, originalBitmap)
        root.addView(cropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Toolbar bawah
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC141414"))
            setPadding(0, 16, 0, 16)
        }
        val btnCancel = Button(this).apply {
            text = "Batal"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        val btnDone = Button(this).apply {
            text = "Selesai"
            setTextColor(Color.parseColor("#64B5F6"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { saveCrop() }
        }
        bar.addView(btnCancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnDone,   LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(root)
    }

    private fun saveCrop() {
        val cropped = cropView.getCropped() ?: run {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        val file = java.io.File(cacheDir, "crop_${System.currentTimeMillis()}.png")
        file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PATH, file.absolutePath))
        finish()
    }

    // ── Inner View ────────────────────────────────────────────────────────
    inner class CropView(context: Context, private val src: Bitmap) : View(context) {

        // Rect untuk gambar (fit-center) dan crop handle
        private val imgRect  = RectF()
        private val cropRect = RectF()

        // Handle drag state
        private var activeHandle = Handle.NONE
        private var lastX = 0f; private var lastY = 0f
        private val TOUCH_SLOP = 40f

        private val bmpPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dimPaint    = Paint().apply { color = Color.parseColor("#99000000") }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val gridPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // Fit bitmap ke layar
            val scale = minOf(w / src.width.toFloat(), h / src.height.toFloat())
            val bw = src.width * scale; val bh = src.height * scale
            imgRect.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            // Crop awal = full gambar
            cropRect.set(imgRect)
        }

        override fun onDraw(canvas: Canvas) {
            // Gambar bitmap
            canvas.drawBitmap(src, null, imgRect, bmpPaint)

            // Dim di luar crop
            canvas.drawRect(imgRect.left,  imgRect.top,    imgRect.right,  cropRect.top,   dimPaint)
            canvas.drawRect(imgRect.left,  cropRect.bottom, imgRect.right, imgRect.bottom, dimPaint)
            canvas.drawRect(imgRect.left,  cropRect.top,   cropRect.left,  cropRect.bottom, dimPaint)
            canvas.drawRect(cropRect.right, cropRect.top,  imgRect.right, cropRect.bottom, dimPaint)

            // Grid rule of thirds
            val w3 = cropRect.width() / 3f; val h3 = cropRect.height() / 3f
            for (i in 1..2) {
                canvas.drawLine(cropRect.left + w3 * i, cropRect.top,
                                cropRect.left + w3 * i, cropRect.bottom, gridPaint)
                canvas.drawLine(cropRect.left, cropRect.top + h3 * i,
                                cropRect.right, cropRect.top + h3 * i, gridPaint)
            }

            // Border
            canvas.drawRect(cropRect, borderPaint)

            // Corner handles
            val hs = 20f
            listOf(
                cropRect.left  to cropRect.top,
                cropRect.right to cropRect.top,
                cropRect.left  to cropRect.bottom,
                cropRect.right to cropRect.bottom
            ).forEach { (hx, hy) ->
                canvas.drawCircle(hx, hy, hs, handlePaint)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val x = ev.x; val y = ev.y
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeHandle = detectHandle(x, y)
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX; val dy = y - lastY
                    applyDrag(dx, dy)
                    lastX = x; lastY = y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> activeHandle = Handle.NONE
            }
            return true
        }

        private fun detectHandle(x: Float, y: Float): Handle {
            val s = TOUCH_SLOP
            return when {
                dist(x, y, cropRect.left,  cropRect.top)    < s -> Handle.TL
                dist(x, y, cropRect.right, cropRect.top)    < s -> Handle.TR
                dist(x, y, cropRect.left,  cropRect.bottom) < s -> Handle.BL
                dist(x, y, cropRect.right, cropRect.bottom) < s -> Handle.BR
                cropRect.contains(x, y)                         -> Handle.MOVE
                else                                            -> Handle.NONE
            }
        }

        private fun applyDrag(dx: Float, dy: Float) {
            when (activeHandle) {
                Handle.MOVE -> {
                    val newL = (cropRect.left + dx).coerceIn(imgRect.left, imgRect.right - MIN_CROP)
                    val newT = (cropRect.top  + dy).coerceIn(imgRect.top,  imgRect.bottom - MIN_CROP)
                    val w = cropRect.width(); val h = cropRect.height()
                    cropRect.set(newL, newT,
                        (newL + w).coerceAtMost(imgRect.right),
                        (newT + h).coerceAtMost(imgRect.bottom))
                }
                Handle.TL -> {
                    cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - MIN_CROP)
                    cropRect.top  = (cropRect.top  + dy).coerceIn(imgRect.top,  cropRect.bottom - MIN_CROP)
                }
                Handle.TR -> {
                    cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + MIN_CROP, imgRect.right)
                    cropRect.top   = (cropRect.top   + dy).coerceIn(imgRect.top,  cropRect.bottom - MIN_CROP)
                }
                Handle.BL -> {
                    cropRect.left   = (cropRect.left   + dx).coerceIn(imgRect.left, cropRect.right - MIN_CROP)
                    cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + MIN_CROP, imgRect.bottom)
                }
                Handle.BR -> {
                    cropRect.right  = (cropRect.right  + dx).coerceIn(cropRect.left + MIN_CROP, imgRect.right)
                    cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top  + MIN_CROP, imgRect.bottom)
                }
                Handle.NONE -> {}
            }
        }

        fun getCropped(): Bitmap? {
            // Konversi cropRect (koordinat view) ke koordinat bitmap asli
            val scaleX = src.width  / imgRect.width()
            val scaleY = src.height / imgRect.height()
            val bx = ((cropRect.left   - imgRect.left) * scaleX).toInt().coerceAtLeast(0)
            val by = ((cropRect.top    - imgRect.top)  * scaleY).toInt().coerceAtLeast(0)
            val bw = (cropRect.width()  * scaleX).toInt().coerceAtMost(src.width  - bx)
            val bh = (cropRect.height() * scaleY).toInt().coerceAtMost(src.height - by)
            if (bw <= 0 || bh <= 0) return null
            return Bitmap.createBitmap(src, bx, by, bw, bh)
        }

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
            sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
    }
}

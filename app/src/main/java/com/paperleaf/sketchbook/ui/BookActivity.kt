package com.paperleaf.sketchbook.ui

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.databinding.ActivityBookBinding
import com.paperleaf.sketchbook.pageflip.ReaderActivity
import com.paperleaf.sketchbook.db.AppDatabase
import com.paperleaf.sketchbook.model.Page
import com.paperleaf.sketchbook.template.PaperTemplateEngine
import com.paperleaf.sketchbook.utils.FileUtils
import com.paperleaf.sketchbook.theme.ThemeManager
import com.paperleaf.sketchbook.utils.TransitionHelper
import com.paperleaf.sketchbook.animation.ViewMorphAnimator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookActivity : AppCompatActivity(), ThemeManager.OnThemeChangeListener {

    // ─── CONSTANTS ────────────────────────────────────────────────
    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
    }

    // ─── PROPERTIES & STATE ───────────────────────────────────────
    private lateinit var binding: ActivityBookBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private var bookId = 0L
    private var pages = listOf<Page>()

    // Index spread (halaman ganda) saat ini. 0-based (0, 1, 2, dst)
    private var currentSpread = 0

    private var leftBitmap: Bitmap? = null
    private var rightBitmap: Bitmap? = null
    private var isGridMode = false
    
    private lateinit var gridAdapter: PageGridAdapter
    private lateinit var gesture: GestureDetector

    // Spread bitmap cache (max 6 entries, auto-evicts LRU)
    private val spreadCache = object : android.util.LruCache<Int, Bitmap>(6) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    private val isMidnight: Boolean get() = ThemeManager.current.id == "ios_dark"

    // ─── LIFECYCLE ────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.init(this)
        ThemeManager.addListener(this)

        val tc = ThemeManager.getThemeColors()
        window.statusBarColor = tc.background
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        applyThemeColors()

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0)
        binding.tvBookTitle.text = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: ""

        setupViewToggle()
        setupTitleEdit()
        setupSpreadEdit()
        setupGesture()
        setupBottomBar()
        setupGrid()
        observePages()
    }
	
    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    override fun onResume() {
        super.onResume()
        invalidateSpreadCache()
        LoadingOverlay.show(this)
        lifecycleScope.launch {
            loadSpreadBitmaps()
            updateSpreadUI()
        }
        // Refresh judul dari DB (mungkin diubah dari MainActivity)
        lifecycleScope.launch(Dispatchers.IO) {
            val book = db.bookDao().getBook(bookId)
            withContext(Dispatchers.Main) {
                book?.let { binding.tvBookTitle.text = it.title }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(this)
    }

    private fun applyThemeColors() {
        val c = ThemeManager.getThemeColors()
        window.statusBarColor = c.background

        if (isMidnight) {
            applyMidnightTheme()
        } else if (ThemeManager.current.id == "deep_ocean") {
            applyDeepOceanTheme()
        } else {
            binding.root.setBackgroundColor(c.background)
            val iconColor = c.toolbarIcon
            binding.btnViewFlip.setColorFilter(iconColor)
            binding.btnViewGrid.setColorFilter(iconColor)
            binding.btnPaperStore.setColorFilter(iconColor)
            binding.tvBookTitle.setTextColor(c.textPrimary)
            binding.tvPageInfo.setTextColor(c.accent)
            listOf(binding.btnMore, binding.btnShare, binding.btnDuplicate, binding.btnAddPage).forEach {
                it.setColorFilter(c.toolbarIcon)
            }
        }
    }

    private fun centerCropBitmap(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt()
        val scaledH = (src.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = (scaledW - targetW) / 2
        val y = (scaledH - targetH) / 2
        return Bitmap.createBitmap(scaled, x.coerceAtLeast(0), y.coerceAtLeast(0),
            minOf(targetW, scaledW), minOf(targetH, scaledH))
    }

    private fun applyDeepOceanTheme() {
        val targetW: Int
        val targetH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            targetW = bounds.width()
            targetH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = windowManager.defaultDisplay.let { android.util.DisplayMetrics().also(it::getMetrics) }
            targetW = dm.widthPixels
            targetH = dm.heightPixels
        }

        try {
            val bgStream = assets.open("themes/deep_ocean/bg_theme_ocean.webp")
            val src = BitmapFactory.decodeStream(bgStream)
            val cropped = centerCropBitmap(src, targetW, targetH)
            binding.root.background = BitmapDrawable(resources, cropped)
        } catch (_: Exception) {}

        val btnDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_ocean_btn)
        listOf(binding.btnMore, binding.btnShare, binding.btnDuplicate, binding.btnAddPage).forEach {
            it.background = btnDrawable?.constantState?.newDrawable()?.mutate()
        }

        binding.tvBookTitle.setTextColor(Color.parseColor("#12405D"))
        binding.tvPageInfo.setTextColor(Color.parseColor("#3EBFC5"))
        binding.bookSpreadView.isDeepOceanShadow = true

        val spreadDefault = Color.parseColor("#11415D")
        binding.btnViewFlip.setColorFilter(spreadDefault)
        binding.btnViewGrid.setColorFilter(spreadDefault)
        binding.btnPaperStore.setColorFilter(spreadDefault)

        listOf(binding.btnMore, binding.btnShare, binding.btnDuplicate, binding.btnAddPage).forEach {
            it.setColorFilter(Color.WHITE)
        }
    }

    private fun applyMidnightTheme() {
        window.statusBarColor = Color.parseColor("#1C1C1E")
        binding.root.setBackgroundColor(Color.parseColor("#1C1C1E"))
        binding.tvBookTitle.setTextColor(Color.WHITE)
        binding.tvPageInfo.setTextColor(Color.parseColor("#1B3E98"))

        updateViewToggleIcons()

        binding.btnPaperStore.setColorFilter(Color.WHITE)

        val bottomIconColor = Color.parseColor("#1C1C1E")
        listOf(binding.btnMore, binding.btnShare, binding.btnDuplicate, binding.btnAddPage).forEach {
            it.setColorFilter(bottomIconColor)
        }
    }

    override fun onThemeChanged(theme: com.paperleaf.sketchbook.theme.ThemeConfig) {
        runOnUiThread { applyThemeColors() }
    }

    // ─── UI SETUP & TOGGLES ───────────────────────────────────────
    private fun setupViewToggle() {
        binding.btnViewFlip.setOnClickListener { if (isGridMode) switchToFlip() }
        binding.btnViewGrid.setOnClickListener { if (!isGridMode) switchToGrid() }
        binding.btn3DFlip.setOnClickListener {
            startActivity(Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                putExtra(ReaderActivity.EXTRA_BOOK_TITLE, binding.tvBookTitle.text)
                putExtra(ReaderActivity.EXTRA_START_SPREAD, currentSpread)
            })
            TransitionHelper.morphForward(this@BookActivity)
        }
        binding.btnPaperStore.setOnClickListener {
            if (isMidnight) {
                binding.btnPaperStore.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnPaperStore.postDelayed({
                    binding.btnPaperStore.setColorFilter(Color.WHITE)
                }, 200)
            }
            openPaperStore()
        }
    }

    private fun openPaperStore() {
        startActivity(Intent(this, PaperStoreActivity::class.java))
        TransitionHelper.morphForward(this@BookActivity)
    }

    private fun updateViewToggleIcons() {
        if (isMidnight) {
            binding.btnViewFlip.imageTintList = ColorStateList.valueOf(
                if (!isGridMode) 0xFF1B3E98.toInt() else 0xFFFFFFFF.toInt()
            )
            binding.btnViewGrid.imageTintList = ColorStateList.valueOf(
                if (isGridMode) 0xFF1B3E98.toInt() else 0xFFFFFFFF.toInt()
            )
        } else if (ThemeManager.current.id == "deep_ocean") {
            binding.btnViewFlip.imageTintList = ColorStateList.valueOf(
                if (!isGridMode) 0xFFA9E4E3.toInt() else 0xFF11415D.toInt()
            )
            binding.btnViewGrid.imageTintList = ColorStateList.valueOf(
                if (isGridMode) 0xFFA9E4E3.toInt() else 0xFF11415D.toInt()
            )
        } else {
            binding.btnViewFlip.imageTintList = ColorStateList.valueOf(
                if (!isGridMode) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            )
            binding.btnViewGrid.imageTintList = ColorStateList.valueOf(
                if (isGridMode) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            )
        }
    }

    private fun switchToFlip() {
        isGridMode = false
        ViewMorphAnimator.morphIn(binding.bookSpreadView)
        binding.gestureOverlay.visibility = View.VISIBLE
        binding.gestureOverlay.alpha = 0f
        binding.gestureOverlay.animate().alpha(1f).setDuration(250).start()
        binding.rvPageGrid.visibility = View.GONE
        updateViewToggleIcons()
        loadSpread()
    }

    private fun setupTitleEdit() {
        binding.tvBookTitle.setOnClickListener {
            val dp = resources.displayMetrics.density
            val currentText = binding.tvBookTitle.text.toString()
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
                setOnClickListener { dialog.dismiss() }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 14f * dp
                }
                layoutParams = FrameLayout.LayoutParams(
                    (260 * dp).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setOnClickListener {}
            }
            card.addView(TextView(this).apply {
                text = "Ubah Judul Buku"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, (16*dp).toInt(), 0, (4*dp).toInt())
            })
            val et = EditText(this).apply {
                setText(currentText)
                selectAll()
                setHintTextColor(Color.parseColor("#666666"))
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 14f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2C"))
                    cornerRadius = 8f * dp
                }
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt()) }
            }
            card.addView(et)
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48*dp).toInt())
            }
            btnRow.addView(TextView(this).apply {
                text = "Batal"
                textSize = 15f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })
            btnRow.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            btnRow.addView(TextView(this).apply {
                text = "Simpan"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    val newTitle = et.text.toString().ifBlank { "Jurnal Baru" }
                    binding.tvBookTitle.text = newTitle
                    lifecycleScope.launch(Dispatchers.IO) {
                        val book = db.bookDao().getBook(bookId)
                        if (book != null) {
                            db.bookDao().update(book.copy(title = newTitle))
                        }
                    }
                    dialog.dismiss()
                }
            })
            card.addView(btnRow)
            root.addView(card)
            dialog.setContentView(root)
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            TransitionHelper.morphDialog(dialog)
            dialog.show()
            et.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun setupSpreadEdit() {
        binding.tvPageInfo.setOnClickListener {
            val dp = resources.displayMetrics.density
            val totalSpreads = (pages.size + 1) / 2
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
                setOnClickListener { dialog.dismiss() }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    cornerRadius = 14f * dp
                }
                layoutParams = FrameLayout.LayoutParams(
                    (260 * dp).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setOnClickListener {}
            }
            card.addView(TextView(this).apply {
                text = "Jumlah Spread"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, (16*dp).toInt(), 0, (4*dp).toInt())
            })
            card.addView(TextView(this).apply {
                text = "Masukkan jumlah spread (halaman akan menyesuaikan)"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                setPadding((16*dp).toInt(), 0, (16*dp).toInt(), (12*dp).toInt())
            })
            val et = EditText(this).apply {
                setText("$totalSpreads")
                selectAll()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setHintTextColor(Color.parseColor("#666666"))
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 14f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2C"))
                    cornerRadius = 8f * dp
                }
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((12*dp).toInt(), 0, (12*dp).toInt(), (12*dp).toInt()) }
            }
            card.addView(et)
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48*dp).toInt())
            }
            btnRow.addView(TextView(this).apply {
                text = "Batal"
                textSize = 15f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })
            btnRow.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            btnRow.addView(TextView(this).apply {
                text = "Simpan"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    val newSpread = et.text.toString().toIntOrNull() ?: totalSpreads
                    if (newSpread >= 1) {
                        val newPageCount = newSpread * 2
                        val currentCount = pages.size
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (newPageCount > currentCount) {
                                for (i in currentCount + 1..newPageCount) {
                                    db.pageDao().insert(Page(bookId = bookId, pageNumber = i))
                                }
                            } else if (newPageCount < currentCount) {
                                for (i in currentCount downTo newPageCount + 1) {
                                    val page = db.pageDao().getPage(bookId, i)
                                    if (page != null) db.pageDao().delete(page)
                                }
                            }
                            val book = db.bookDao().getBook(bookId)
                            if (book != null) {
                                db.bookDao().update(book.copy(pageCount = newPageCount))
                            }
                        }
                        dialog.dismiss()
                        Toast.makeText(this@BookActivity, "Spread diubah ke $newSpread", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@BookActivity, "Minimal 1 spread", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            card.addView(btnRow)
            root.addView(card)
            dialog.setContentView(root)
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            TransitionHelper.morphDialog(dialog)
            dialog.show()
            et.requestFocus()
        }
    }

    private fun switchToGrid() {
        isGridMode = true
        binding.bookSpreadView.visibility = View.GONE
        binding.gestureOverlay.visibility = View.GONE
        ViewMorphAnimator.morphIn(binding.rvPageGrid)
        updateViewToggleIcons()
        loadGridThumbnails()
    }

    private fun setupGrid() {
        gridAdapter = PageGridAdapter { spreadIdx -> openSketch(spreadIdx) }
        binding.rvPageGrid.apply {
            layoutManager = GridLayoutManager(this@BookActivity, 3)
            adapter = gridAdapter
        }
    }

    // ─── GESTURE HANDLING ─────────────────────────────────────────
    private var isDragging = false

    private fun setupGesture() {
        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val totalDx = e2.x - e1.x
                val totalSpreads = (pages.size + 1) / 2

                if (!isDragging && abs(totalDx) > 24) {
                    val dir = if (totalDx > 0) -1 else 1
                    // dir: -1 = swipe RIGHT → previous spread
                    // dir:  1 = swipe LEFT  → next spread
                    if (dir < 0 && currentSpread <= 0) return false
                    if (dir > 0 && currentSpread + 1 >= totalSpreads) return false

                    isDragging = true
                    val outgoing = leftBitmap
                    val targetSpread = currentSpread + dir

                    binding.bookSpreadView.prepareDragFlip(dir > 0, outgoing)

                    lifecycleScope.launch {
                        leftBitmap = getCachedSpread(targetSpread)
                        rightBitmap = null
                        binding.bookSpreadView.invalidate()
                    }
                }

                if (isDragging) {
                    val progress = abs(totalDx / binding.bookSpreadView.width.toFloat())
                    binding.bookSpreadView.setDragProgress(progress.coerceIn(0f, 1f))
                }
                return isDragging
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null || isDragging) return false
                val dx = e2.x - e1.x
                return when {
                    dx < -80 -> { flipNext(); true }
                    dx > 80 -> { flipPrev(); true }
                    else -> false
                }
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isDragging) return false
                openSketch(currentSpread)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isDragging) return
                showTemplatePopup()
            }
        })

        binding.gestureOverlay.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val complete = binding.bookSpreadView.flipProgress > 0.45f
                        if (complete) {
                            currentSpread += if (binding.bookSpreadView.flipToNext) 1 else -1
                        }
                        binding.bookSpreadView.commitFlip(complete) {
                            lifecycleScope.launch {
                                loadSpreadBitmaps()
                                updateSpreadUI()
                            }
                        }
                    }
                }
            }
            gesture.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                isDragging = false
            }
            true
        }
    }

    // ─── DATA OBSERVERS ───────────────────────────────────────────
    private fun observePages() {
        lifecycleScope.launch {
            db.pageDao().getPagesForBook(bookId).collectLatest { list ->
                pages = list
                val totalSpreads = (pages.size + 1) / 2
                
                // Pastikan currentSpread tidak melebihi batas
                if (currentSpread >= totalSpreads) {
                    currentSpread = (totalSpreads - 1).coerceAtLeast(0)
                }
                
                loadSpread()
            }
        }
    }

    // ─── DATA LOADING & RENDERING ─────────────────────────────────
    private fun loadSpread() {
        if (isGridMode) {
            loadGridThumbnails()
        } else {
            lifecycleScope.launch {
                loadSpreadBitmaps()
                updateSpreadUI()
            }
        }
    }

    private suspend fun loadSpreadBitmaps() {
        val spreadIdx = currentSpread
        leftBitmap = getCachedSpread(spreadIdx)
        rightBitmap = null
    }

    private suspend fun getCachedSpread(idx: Int): Bitmap? {
        spreadCache.get(idx)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            FileUtils.loadBitmap(FileUtils.getSpreadFile(this@BookActivity, bookId, idx))
        }
        if (bmp != null) {
            spreadCache.put(idx, bmp)
        }
        return bmp
    }

    private fun invalidateSpreadCache() {
        spreadCache.evictAll()
    }

    private fun updateSpreadUI() {
        val totalSpreads = (pages.size + 1) / 2
        binding.bookSpreadView.update(
            leftBmp  = leftBitmap,
            rightBmp = null,
            pageNum  = currentSpread + 1,
            total    = totalSpreads
        )
        binding.tvPageInfo.text = "${currentSpread + 1}  ·  $totalSpreads spread"
        LoadingOverlay.hide(this)
    }

    private fun loadGridThumbnails() {
        LoadingOverlay.show(this)
        lifecycleScope.launch {
            val totalSpreads = (pages.size + 1) / 2
            val bitmaps = withContext(Dispatchers.IO) {
                (0 until totalSpreads).map { idx ->
                    spreadCache.get(idx) ?: FileUtils.loadBitmap(
                        FileUtils.getSpreadFile(this@BookActivity, bookId, idx)
                    )?.also { spreadCache.put(idx, it) }
                }
            }
            val spreadPages = (0 until totalSpreads).map { 
                pages.getOrNull(it * 2) ?: pages.firstOrNull() ?: Page(bookId = bookId, pageNumber = 1)
            }
            gridAdapter.submitData(spreadPages, bitmaps)
            LoadingOverlay.hide(this@BookActivity)
        }
    }

    // ─── NAVIGATION ACTIONS ───────────────────────────────────────
    private fun flipNext() {
        val totalSpreads = (pages.size + 1) / 2
        if (currentSpread + 1 >= totalSpreads) return
        val outgoing = leftBitmap
        currentSpread += 1
        lifecycleScope.launch {
            loadSpreadBitmaps()
            binding.bookSpreadView.startFlip(toNext = true, outgoing = outgoing)
            updateSpreadUI()
        }
    }

    private fun flipPrev() {
        if (currentSpread <= 0) return
        val outgoing = leftBitmap
        currentSpread -= 1
        lifecycleScope.launch {
            loadSpreadBitmaps()
            binding.bookSpreadView.startFlip(toNext = false, outgoing = outgoing)
            updateSpreadUI()
        }
    }

    private fun openSketch(spreadIdx: Int) {
        val pageNumber = spreadIdx * 2 + 1  // Halaman pertama dari spread ini
        startActivity(Intent(this, SketchActivity::class.java).apply {
            putExtra(SketchActivity.EXTRA_BOOK_ID, bookId)
            putExtra(SketchActivity.EXTRA_PAGE_NUMBER, pageNumber)
        })
        TransitionHelper.morphForward(this@BookActivity)
    }

    // ─── BOTTOM BAR ACTIONS ───────────────────────────────────────
    private fun setupBottomBar() {
        binding.btnMore.setOnClickListener {
            if (isMidnight) binding.btnMore.setColorFilter(Color.parseColor("#1B3E98"))
            showSettingsPopup(onDismiss = {
                if (isMidnight) binding.btnMore.setColorFilter(Color.parseColor("#1C1C1E"))
            })
        }
        binding.btnShare.setOnClickListener {
            if (isMidnight) {
                binding.btnShare.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnShare.postDelayed({
                    binding.btnShare.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            }
            showShareSpreadDialog()
        }
        binding.btnDuplicate.setOnClickListener {
            if (isMidnight) {
                binding.btnDuplicate.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnDuplicate.postDelayed({
                    binding.btnDuplicate.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            }
            duplicatePage()
        }
        binding.btnAddPage.setOnClickListener {
            if (isMidnight) {
                binding.btnAddPage.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnAddPage.postDelayed({
                    binding.btnAddPage.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            }
            addPage()
        }
    }

    private fun showSettingsPopup(onDismiss: () -> Unit = {}) {
        val dp = resources.displayMetrics.density

        data class MenuItem(val label: String, val icon: Int, val subtitle: String = "     ")

        val items = listOf(
            MenuItem("Assets & Purchase", R.drawable.paper_store, ""),
            MenuItem("Storage & Data", R.drawable.ic_storage, ""),
            MenuItem("Notification", R.drawable.ic_notif, "Future ready"),
            MenuItem("Language", R.drawable.ic_language, ""),
            MenuItem("Theme", R.drawable.palette, ""),
            MenuItem("Privacy & Security", R.drawable.locked, ""),
            MenuItem("Help & Feedback", R.drawable.ic_help, ""),
            MenuItem("Support Developer", R.drawable.ic_donate, "     "),
            MenuItem("About", R.drawable.ic_about, "")
        )

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { it.bottomMargin = (32 * dp).toInt() }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_dialog_glass)
            setOnClickListener {}
            elevation = 12f
        }

        container.addView(TextView(this).apply {
            text = "Pengaturan"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })

        items.forEachIndexed { _, item ->
            container.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (1 * dp).toInt(), (12 * dp).toInt(), (1 * dp).toInt())
                minimumHeight = (48 * dp).toInt()
                setOnClickListener {
                    dialog.dismiss()
                    when (item.label) {
                        "Assets & Purchase" -> {
                            startActivity(Intent(this@BookActivity, PaperStoreActivity::class.java))
                            TransitionHelper.morphForward(this@BookActivity)
                        }
                        "Storage & Data" -> showStorageDataDialog()
                        "Notification" -> Toast.makeText(this@BookActivity, "Notification — segera hadir", Toast.LENGTH_SHORT).show()
                        "Language" -> showLanguageDialog()
                        "Theme" -> showThemeDialog()
                        "Privacy & Security" -> showPrivacyDialog()
                        "Help & Feedback" -> Toast.makeText(this@BookActivity, "Help — hubungi kami di paperleaf@support.id", Toast.LENGTH_LONG).show()
                        "About" -> showAboutDialog()
                        "Support Developer" -> {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://saweria.co/paperleaf")))
                            } catch (_: Exception) {
                                Toast.makeText(this@BookActivity, "Buka browser untuk mendukung", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            val iconIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                setImageResource(item.icon)
                setColorFilter(Color.parseColor("#FFFFFF"))
            }
            row.addView(iconIv)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.setMarginStart((10 * dp).toInt()) }
            }
            textCol.addView(TextView(this).apply {
                text = item.label
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
            })
            if (item.subtitle.isNotBlank()) {
                textCol.addView(TextView(this).apply {
                    text = item.subtitle
                    textSize = 9f
                    setTextColor(Color.parseColor("#888888"))
                })
            }
            row.addView(textCol)

            container.addView(row)
        }

        // Developer: Unlock Premium Assets
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val devRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        }
        devRow.addView(TextView(this).apply {
            text = "Unlock Premium Assets"
            textSize = 13f
            setTextColor(Color.parseColor("#FFD95A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val devToggle = android.widget.Switch(this).apply {
            isChecked = ThemeManager.isDevUnlockAll()
            setOnCheckedChangeListener { _, isChecked ->
                ThemeManager.setDevUnlockAll(isChecked)
                val status = if (isChecked) "unlocked" else "locked"
                Toast.makeText(this@BookActivity, "Premium assets $status", Toast.LENGTH_SHORT).show()
            }
        }
        devRow.addView(devToggle)
        container.addView(devRow)

        scrollView.addView(container)
        root.addView(scrollView)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    private fun showLanguageDialog() {
        val dp = resources.displayMetrics.density
        val prefs = getSharedPreferences("paperleaf_prefs", MODE_PRIVATE)
        val current = prefs.getString("locale", "id")
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            layoutParams = FrameLayout.LayoutParams(
                (260 * dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(TextView(this).apply {
            text = "Bahasa"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        listOf("id" to "Bahasa Indonesia", "en" to "English").forEach { (langCode, label) ->
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                setOnClickListener {
                    prefs.edit().putString("locale", langCode).apply()
                    dialog.dismiss()
                    Toast.makeText(this@BookActivity, "Bahasa: $label", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (langCode == current) {
                row.addView(TextView(this).apply {
                    text = "\u2713"
                    textSize = 16f
                    setTextColor(Color.parseColor("#4CAF50"))
                })
            }
            card.addView(row)
        }
        val btnCancel = TextView(this).apply {
            text = "Tutup"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(btnCancel)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun showThemeDialog() {
        val dp = resources.displayMetrics.density
        val themes = ThemeManager.loadAllThemes(this).filter {
            ThemeManager.isThemeOwned(it) || it.category == "free"
        }
        val currentId = ThemeManager.getSelectedThemeId()
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_dialog_glass)
            elevation = 12f
            layoutParams = LinearLayout.LayoutParams(
                (280 * dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(TextView(this).apply {
            text = "Tema"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        if (themes.isEmpty()) {
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            card.addView(TextView(this).apply {
                text = "Belum ada tema tersedia"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
            })
        } else {
            themes.forEach { theme ->
                card.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#18FFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                }
                val preview = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (48 * dp).toInt()).also {
                        it.setMarginEnd((10 * dp).toInt())
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try {
                        val ins = assets.open(theme.previewPath)
                        setImageBitmap(BitmapFactory.decodeStream(ins))
                        ins.close()
                    } catch (_: Exception) {
                        setBackgroundColor(theme.colors.surface)
                    }
                    clipToOutline = true
                    outlineProvider = null
                }
                row.addView(preview)
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this).apply {
                    text = theme.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FFFFFF"))
                })
                textCol.addView(TextView(this).apply {
                    text = theme.description
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    maxLines = 1
                })
                row.addView(textCol)
                val badge = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.setMarginStart((8 * dp).toInt()) }
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    if (theme.id == currentId) {
                        text = "APPLIED"
                        setTextColor(Color.parseColor("#4CAF50"))
                        setBackgroundColor(Color.parseColor("#1B5E20"))
                    } else {
                        text = "APPLY"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#007AFF"))
                    }
                }
                row.addView(badge)
                if (theme.id != currentId) {
                    row.setOnClickListener {
                        ThemeManager.applyTheme(this@BookActivity, theme.id)
                        Toast.makeText(this@BookActivity, "Tema: ${theme.name}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                card.addView(row)
            }
        }
        val btnClose = TextView(this).apply {
            text = "Tutup"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(btnClose)
        scrollView.addView(card)
        root.addView(scrollView)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun showPrivacyDialog() {
        val dp = resources.displayMetrics.density
        val prefs = getSharedPreferences("paperleaf_prefs", MODE_PRIVATE)
        data class ToggleItem(val label: String, val key: String, val summary: String)
        val toggles = listOf(
            ToggleItem("Analytics", "analytics_on", "Kumpulkan data penggunaan untuk meningkatkan aplikasi"),
            ToggleItem("Crash Report", "crash_report_on", "Kirim laporan error secara otomatis"),
            ToggleItem("Personalised Content", "personalised_on", "Rekomendasi konten di Paperstore")
        )
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            layoutParams = FrameLayout.LayoutParams(
                (280 * dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(TextView(this).apply {
            text = "Privasi & Keamanan"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        toggles.forEach { toggle ->
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = toggle.label
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
            })
            textCol.addView(TextView(this).apply {
                text = toggle.summary
                textSize = 9f
                setTextColor(Color.parseColor("#888888"))
            })
            row.addView(textCol)
            val sw = android.widget.Switch(this).apply {
                isChecked = prefs.getBoolean(toggle.key, true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(toggle.key, isChecked).apply()
                }
            }
            row.addView(sw)
            card.addView(row)
        }
        val btnClose = TextView(this).apply {
            text = "Tutup"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(btnClose)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun showStorageDataDialog() {
        val dp = resources.displayMetrics.density
        val cacheSize = try {
            val cacheDir = cacheDir
            var size = 0L
            cacheDir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
            if (size > 1048576L) "${size / 1048576L} MB" else "${size / 1024L} KB"
        } catch (_: Exception) { "—" }
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            layoutParams = FrameLayout.LayoutParams(
                (260 * dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(TextView(this).apply {
            text = "Storage & Data"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(TextView(this).apply {
            text = "Cache: $cacheSize"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        val btnClear = TextView(this).apply {
            text = "Hapus Cache"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener {
                try {
                    cacheDir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
                    Toast.makeText(this@BookActivity, "Cache dihapus", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (_: Exception) {
                    Toast.makeText(this@BookActivity, "Gagal menghapus cache", Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.addView(btnClear)
        val btnClose = TextView(this).apply {
            text = "Tutup"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(btnClose)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun showAboutDialog() {
        val dp = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            layoutParams = FrameLayout.LayoutParams(
                (260 * dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
                .also { it.gravity = Gravity.CENTER; it.topMargin = (20 * dp).toInt() }
            setImageResource(R.drawable.ic_paperleaf)
        })
        card.addView(TextView(this).apply {
            text = "Paperleaf"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "v1.0.0"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(TextView(this).apply {
            text = "A sketchbook & journal app for creative minds.\nCrafted with \u2764 by Said Taufiq."
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        })
        val btnClose = TextView(this).apply {
            text = "Tutup"
            textSize = 14f
            setTextColor(Color.parseColor("#4CAF50"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(btnClose)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun shareCurrentPage() {
        val file = FileUtils.getSpreadFile(this, bookId, currentSpread)
        if (!file.exists()) {
            Toast.makeText(this, "Halaman masih kosong", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan halaman"
        ))
    }

    private fun showShareSpreadDialog() {
        val file = FileUtils.getSpreadFile(this, bookId, currentSpread)
        val dp = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(TextView(this).apply {
            text = "Bagikan Halaman"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        data class SpreadOption(val label: String, val icon: Int, val desc: String)
        val options = listOf(
            SpreadOption("Simpan sebagai JPG", R.drawable.ic_jpg, "Format gambar JPEG"),
            SpreadOption("Simpan sebagai PLF", R.drawable.ic_paperleaf, "Satu canvas untuk diedit lagi"),
            SpreadOption("Share", R.drawable.ic_share, "Bagikan ke aplikasi lain")
        )
        options.forEach { opt ->
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = (160 * dp).toInt()
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    when (opt.label) {
                        "Simpan sebagai JPG" -> saveSpreadAsJpg(file)
                        "Simpan sebagai PLF" -> saveSpreadAsPlf(file)
                        "Share" -> shareCurrentPage()
                    }
                }
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).also {
                    it.setMarginEnd((12 * dp).toInt())
                }
                setImageResource(opt.icon)
                setColorFilter(Color.parseColor("#FFFFFF"))
            })
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = opt.label
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
            })
            textCol.addView(TextView(this).apply {
                text = opt.desc
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
            })
            row.addView(textCol)
            card.addView(row)
        }
        val btnClose = TextView(this).apply {
            text = "Batal"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(btnClose)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        if (file.exists()) dialog.show()
        else Toast.makeText(this, "Halaman masih kosong", Toast.LENGTH_SHORT).show()
    }

    private fun saveSpreadAsJpg(file: java.io.File) {
        val bmp = FileUtils.loadBitmap(file) ?: run {
            Toast.makeText(this, "Gagal membaca halaman", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Paperleaf_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Paperleaf")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookActivity, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookActivity, "Gagal menyimpan", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveSpreadAsPlf(file: java.io.File) {
        if (!file.exists()) {
            Toast.makeText(this, "Halaman masih kosong", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val plfFile = FileUtils.generatePlf(this@BookActivity, listOf(file), "spread_${currentSpread + 1}")
            if (plfFile != null && plfFile.exists()) {
                val uri = FileUtils.getShareUri(this@BookActivity, plfFile)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.paperleaf.document"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "spread_${currentSpread + 1}.plf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Simpan sebagai PLF"
                ))
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookActivity, "Gagal membuat PLF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun duplicatePage() {
        Toast.makeText(this, "Duplikat halaman — segera hadir", Toast.LENGTH_SHORT).show()
    }

    private fun addPage() {
        lifecycleScope.launch {
            val newNum = pages.size + 1
            db.pageDao().insert(Page(bookId = bookId, pageNumber = newNum))
            val book = db.bookDao().getBook(bookId)
            if (book != null) {
                db.bookDao().update(book.copy(pageCount = newNum))
            }
            Toast.makeText(this@BookActivity, "Halaman $newNum ditambahkan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePage() {
        if (pages.isEmpty()) return
        lifecycleScope.launch {
            val spreadPage = currentSpread * 2 + 1
            val page = pages.getOrNull(spreadPage - 1) ?: return@launch
            db.pageDao().delete(page)
            val remaining = db.pageDao().getPageCount(bookId)
            val book = db.bookDao().getBook(bookId)
            if (book != null) {
                db.bookDao().update(book.copy(pageCount = remaining))
            }
            if (currentSpread > 0) currentSpread -= 1
        }
    }

    // ─── TEMPLATE SELECTION POPUP ────────────────────────────────
    private fun showTemplatePopup() {
        val dp = resources.displayMetrics.density
        val thumbW = (80 * dp).toInt()
        val thumbH = (60 * dp).toInt()
        val pageFirst = currentSpread * 2 + 1
        val currentPageObj = pages.getOrNull(pageFirst - 1)

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
            setOnClickListener { dialog.dismiss() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val scrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }

        val thumbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
        }

        for (type in Page.ALL_TEMPLATES) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (thumbW + 16 * dp).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            }

            // Thumbnail with rounded corners
            val thumbIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#333333"))
                    cornerRadius = 14f * dp
                }
                clipToOutline = true
            }
            // Render thumbnail to bitmap
            val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            PaperTemplateEngine.drawThumbnail(cv, thumbW.toFloat(), thumbH.toFloat(), type)
            thumbIv.setImageBitmap(bmp)
            card.addView(thumbIv)

            // Label
            card.addView(TextView(this).apply {
                text = Page.templateName(type)
                textSize = 10f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })

            // Checkmark if currently selected
            val isSelected = currentPageObj?.templateType == type
            if (isSelected) {
                card.addView(TextView(this).apply {
                    text = "\u2713"
                    textSize = 12f
                    setTextColor(Color.parseColor("#4CAF50"))
                    gravity = Gravity.CENTER
                })
            }

            card.setOnClickListener {
                dialog.dismiss()
                if (currentPageObj != null) {
                    val updatedPage = currentPageObj.copy(templateType = type)
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.pageDao().update(updatedPage)
                    }
                }
                Toast.makeText(this@BookActivity,
                    "Template: ${Page.templateName(type)}", Toast.LENGTH_SHORT).show()
            }

            thumbContainer.addView(card)
        }

        scrollView.addView(thumbContainer)
        container.addView(scrollView)

        val bgCard = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { it.bottomMargin = (100 * dp).toInt() }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadii = floatArrayOf(
                    14 * dp, 14 * dp, 14 * dp, 14 * dp, 0f, 0f, 0f, 0f
                )
            }
            addView(container)
        }

        root.addView(bgCard)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    // ─── ADAPTER ──────────────────────────────────────────────────
    class PageGridAdapter(
        private val onPageClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PageGridAdapter.VH>() {

        private var pages = listOf<Page>()
        private var bitmaps = listOf<Bitmap?>()

        fun submitData(p: List<Page>, b: List<Bitmap?>) {
            pages = p
            bitmaps = b
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_page_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(position, bitmaps.getOrNull(position))
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val iv: ImageView = v.findViewById(R.id.ivPage)
            private val tv: TextView = v.findViewById(R.id.tvPageNum)
            
            fun bind(idx: Int, bmp: Bitmap?) {
                iv.setImageBitmap(bmp)
                tv.text = "${idx + 1}"
                itemView.setOnClickListener { onPageClick(idx) }
            }
        }
    }
}

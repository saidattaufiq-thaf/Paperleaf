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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.databinding.ActivityMainBinding
import com.paperleaf.sketchbook.db.AppDatabase
import com.paperleaf.sketchbook.model.Book
import com.paperleaf.sketchbook.model.Page
import com.paperleaf.sketchbook.template.PaperTemplateEngine
import com.paperleaf.sketchbook.utils.FileUtils
import com.paperleaf.sketchbook.utils.TransitionHelper
import com.paperleaf.sketchbook.animation.ViewMorphAnimator
import com.paperleaf.sketchbook.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity(), ThemeManager.OnThemeChangeListener {

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: BookPagerAdapter
    private lateinit var shelfAdapter: BookAdapter
    private val db by lazy { AppDatabase.getInstance(this) }

    private var books = listOf<Book>()
    private val handler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var pendingScrollPos: Int? = null

    // ─── Mode state ───────────────────────────────────────────
    private var isSpreadMode = false
    private var isGridMode = false
    private var prevGridMode = false
    private var spreadBookId = 0L
    private var spreadPagesJob: Job? = null
    private var pages = listOf<Page>()
    private var currentSpread = 0
    private var leftBitmap: Bitmap? = null
    private var rightBitmap: Bitmap? = null
    private lateinit var gridAdapter: PageGridAdapter
    private lateinit var gesture: GestureDetector
    private var isDragging = false
    private val spreadCache = object : android.util.LruCache<Int, Bitmap>(6) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    private var animSourceRect: Rect? = null
    private var animBookColor: Int = Color.GRAY
    private var isClosingAnimation = false

    private val covers = listOf(
        Color.parseColor("#E8D5C4"), Color.parseColor("#C4D4E8"),
        Color.parseColor("#D4E8C4"), Color.parseColor("#E8C4D4"),
        Color.parseColor("#E8E4C4"), Color.parseColor("#C4E8E4"),
        Color.parseColor("#3C3C3C"), Color.parseColor("#8B6355")
    )

    private val isMidnight: Boolean get() = ThemeManager.current.id == "ios_dark"

    // ─── Lifecycle ────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        ThemeManager.addListener(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyThemeColors()
        setupViewPager()
        setupLibraryShelf()
        setupBottomBar()
        setupTitleEdit()
        setupPageCountEdit()
        observeBooks()
        setupViewToggle()
        setupGesture()
        setupSpreadGrid()
        handlePlfIntent()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        handlePlfIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isSpreadMode) {
            invalidateSpreadCache()
            lifecycleScope.launch {
                loadSpreadBitmaps()
                updateSpreadUI()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isSpreadMode && !isClosingAnimation) {
            exitSpreadMode()
        } else if (!isClosingAnimation) {
            TransitionHelper.morphFinish(this)
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun applyThemeColors() {
        if (isMidnight) {
            applyMidnightTheme()
        } else if (ThemeManager.current.id == "deep_ocean") {
            applyDeepOceanTheme()
        } else {
            val c = ThemeManager.getThemeColors()
            binding.root.setBackgroundColor(c.background)
            window.statusBarColor = c.background
            binding.btnViewFlip.setColorFilter(c.toolbarIcon)
            binding.btnViewGrid.setColorFilter(c.toolbarIcon)
            binding.btnPaperStore.setColorFilter(c.toolbarIcon)
            binding.etBookTitle.setTextColor(c.textPrimary)
            binding.tvPageCount.setTextColor(c.accent)
            listOf(binding.btnSettings, binding.btnDelete, binding.btnShare, binding.btnDuplicate, binding.btnAdd).forEach {
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
        window.statusBarColor = Color.parseColor("#124260")

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
        binding.etBookTitle.setTextColor(Color.parseColor("#12405D"))
        binding.tvPageCount.setTextColor(Color.parseColor("#3EBFC5"))

        if (isSpreadMode) {
            binding.bookSpreadView.isDeepOceanShadow = true
        }

        val allBtns = listOf(binding.btnSettings, binding.btnDelete, binding.btnShare, binding.btnDuplicate, binding.btnAdd)
        if (!isSpreadMode) {
            allBtns.forEach {
                it.background = btnDrawable?.constantState?.newDrawable()?.mutate()
                it.setColorFilter(Color.WHITE)
            }
        } else {
            binding.btnViewFlip.setColorFilter(Color.parseColor("#11415D"))
            binding.btnViewGrid.setColorFilter(Color.parseColor("#11415D"))
            binding.btnPaperStore.setColorFilter(Color.parseColor("#11415D"))
            allBtns.forEach {
                it.background = btnDrawable?.constantState?.newDrawable()?.mutate()
                it.setColorFilter(Color.WHITE)
            }
        }
    }

    private fun applyMidnightTheme() {
        window.statusBarColor = Color.parseColor("#1C1C1E")
        binding.root.setBackgroundColor(Color.parseColor("#1C1C1E"))
        binding.etBookTitle.setTextColor(Color.WHITE)
        binding.tvPageCount.setTextColor(Color.parseColor("#1B3E98"))

        updateViewToggleIcons()

        binding.btnPaperStore.setColorFilter(Color.WHITE)

        val bottomIconColor = Color.parseColor("#1C1C1E")
        listOf(binding.btnSettings, binding.btnDelete, binding.btnShare, binding.btnDuplicate, binding.btnAdd).forEach {
            it.setColorFilter(bottomIconColor)
        }
    }

    override fun onThemeChanged(theme: com.paperleaf.sketchbook.theme.ThemeConfig) {
        runOnUiThread { applyThemeColors() }
    }

    // ─── Book Click → Spread (with animation) ────────────────
    private fun onBookClicked(book: Book, clickedView: View) {
        val rect = Rect()
        clickedView.getGlobalVisibleRect(rect)
        val rootPos = IntArray(2)
        binding.root.getLocationOnScreen(rootPos)
        rect.offset(-rootPos[0], -rootPos[1])
        animSourceRect = rect
        animBookColor = book.coverColor
        enterSpreadMode(book)
    }

    // ─── Spread Mode Entry/Exit ───────────────────────────────
    private fun enterSpreadMode(book: Book) {
        isSpreadMode = true
        spreadBookId = book.id
        currentSpread = 0
        prevGridMode = isGridMode
        if (isGridMode) switchToFlip()
        spreadCache.evictAll()
        LoadingOverlay.show(this)

        binding.viewPager.visibility = View.GONE
        binding.rvLibraryShelf.visibility = View.GONE
        binding.spreadContent.visibility = View.VISIBLE
        binding.bookSpreadView.visibility = View.INVISIBLE
        binding.gestureOverlay.visibility = View.INVISIBLE
        binding.rvPageGrid.visibility = View.GONE

        applyThemeColors()
        updateSpreadBottomBar()
        updateHeader(book)

        val sourceRect = animSourceRect
        if (sourceRect != null) {
            animateBookOpen(animBookColor, sourceRect) {
                binding.bookSpreadView.visibility = View.VISIBLE
                binding.gestureOverlay.visibility = View.VISIBLE
            }
        } else {
            binding.bookSpreadView.visibility = View.VISIBLE
            binding.gestureOverlay.visibility = View.VISIBLE
        }

        spreadPagesJob?.cancel()
        spreadPagesJob = lifecycleScope.launch {
            db.pageDao().getPagesForBook(book.id).collectLatest { list ->
                if (!isSpreadMode) return@collectLatest
                pages = list
                val totalSpreads = (pages.size + 1) / 2
                if (currentSpread >= totalSpreads) {
                    currentSpread = (totalSpreads - 1).coerceAtLeast(0)
                }
                loadSpread()
            }
        }
    }

    private fun exitSpreadMode() {
        val sourceRect = animSourceRect
        if (sourceRect != null && animBookColor != Color.GRAY) {
            animateBookClose(animBookColor, sourceRect) {
                finishExitSpreadMode()
            }
        } else {
            finishExitSpreadMode()
        }
    }

    private fun finishExitSpreadMode() {
        isSpreadMode = false
        spreadPagesJob?.cancel()
        spreadBookId = 0L
        spreadCache.evictAll()
        animSourceRect = null

        isGridMode = prevGridMode
        updateViewToggleIcons()
        binding.spreadContent.visibility = View.GONE
        updateLibraryContentVisibility()
        applyThemeColors()
        updateLibraryBottomBar()
    }

    // ─── Book Open/Close Animation ────────────────────────────
    private fun animateBookOpen(color: Int, sourceRect: Rect, onEnd: () -> Unit) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val overlay = View(this).apply {
            setBackgroundColor(color)
            elevation = 20f
            pivotX = 0f
            pivotY = 0f
            cameraDistance = 8000f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleX = sourceRect.width().toFloat() / screenW
            scaleY = sourceRect.height().toFloat() / screenH
            x = sourceRect.left.toFloat()
            y = sourceRect.top.toFloat()
        }
        binding.root.addView(overlay)

        overlay.animate()
            .scaleX(1f)
            .scaleY(1f)
            .x(0f)
            .y(0f)
            .setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.04f))
            .withEndAction {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction {
                        binding.root.removeView(overlay)
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    private fun animateBookClose(color: Int, targetRect: Rect, onEnd: () -> Unit) {
        if (isClosingAnimation) return
        isClosingAnimation = true

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        isSpreadMode = false
        spreadPagesJob?.cancel()
        spreadCache.evictAll()

        isGridMode = prevGridMode
        updateViewToggleIcons()
        binding.spreadContent.visibility = View.GONE
        updateLibraryContentVisibility()
        updateLibraryBottomBar()
        applyThemeColors()

        val overlay = View(this).apply {
            setBackgroundColor(color)
            elevation = 20f
            pivotX = 0f
            pivotY = 0f
            cameraDistance = 8000f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleX = 1f
            scaleY = 1f
            x = 0f
            y = 0f
            alpha = 0f
        }
        binding.root.addView(overlay)

        overlay.animate()
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                overlay.animate()
                    .scaleX(targetRect.width().toFloat() / screenW)
                    .scaleY(targetRect.height().toFloat() / screenH)
                    .x(targetRect.left.toFloat())
                    .y(targetRect.top.toFloat())
                    .setDuration(350)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        binding.root.removeView(overlay)
                        isClosingAnimation = false
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    // ─── View Toggle ──────────────────────────────────────────
    private fun setupViewToggle() {
        binding.btnViewFlip.setOnClickListener { if (isGridMode) switchToFlip() }
        binding.btnViewGrid.setOnClickListener { if (!isGridMode) switchToGrid() }
        binding.btnPaperStore.setOnClickListener {
            if (isMidnight) {
                binding.btnPaperStore.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnPaperStore.postDelayed({
                    binding.btnPaperStore.setColorFilter(Color.WHITE)
                }, 200)
            }
            startActivity(Intent(this, PaperStoreActivity::class.java))
            TransitionHelper.morphForward(this@MainActivity)
        }
    }

    private fun switchToFlip() {
        isGridMode = false
        updateViewToggleIcons()
        if (isSpreadMode) {
            ViewMorphAnimator.morphIn(binding.bookSpreadView)
            binding.gestureOverlay.visibility = View.VISIBLE
            binding.gestureOverlay.alpha = 0f
            binding.gestureOverlay.animate().alpha(1f).setDuration(250).start()
            binding.rvPageGrid.visibility = View.GONE
            loadSpread()
        } else {
            ViewMorphAnimator.morphIn(binding.viewPager)
            binding.rvLibraryShelf.visibility = View.GONE
            updateHeader(books.getOrNull(binding.viewPager.currentItem))
        }
    }

    private fun switchToGrid() {
        isGridMode = true
        updateViewToggleIcons()
        if (isSpreadMode) {
            binding.bookSpreadView.visibility = View.GONE
            binding.gestureOverlay.visibility = View.GONE
            ViewMorphAnimator.morphIn(binding.rvPageGrid)
            loadGridThumbnails()
        } else {
            binding.viewPager.visibility = View.GONE
            ViewMorphAnimator.morphIn(binding.rvLibraryShelf)
            updateHeader(books.firstOrNull())
        }
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

    // ─── Library Content ──────────────────────────────────────
    private fun setupViewPager() {
        pagerAdapter = BookPagerAdapter { book, view -> onBookClicked(book, view) }
        val padding = (56 * resources.displayMetrics.density).toInt()
        binding.viewPager.apply {
            adapter = pagerAdapter
            offscreenPageLimit = 1
            clipToPadding = false
            clipChildren = false
            setPadding(padding, 0, padding, 0)
            setPageTransformer { page, position ->
                val scale = 1f - 0.1f * abs(position)
                page.scaleY = scale
                page.alpha = 1f - 0.35f * abs(position)
            }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateHeader(books.getOrNull(position))
                }
            })
        }
    }

    private fun setupLibraryShelf() {
        shelfAdapter = BookAdapter(
            onBookClick = { book, view -> onBookClicked(book, view) },
            onBookLongClick = { book -> showDeleteBookDialog(book) }
        )
        binding.rvLibraryShelf.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = shelfAdapter
        }
    }

    private fun updateLibraryContentVisibility() {
        val empty = books.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty && !isSpreadMode) {
            if (isGridMode) {
                binding.viewPager.visibility = View.GONE
                binding.rvLibraryShelf.visibility = View.VISIBLE
            } else {
                binding.viewPager.visibility = View.VISIBLE
                binding.rvLibraryShelf.visibility = View.GONE
            }
        }
    }

    // ─── Header ───────────────────────────────────────────────
    private fun setupTitleEdit() {
        binding.etBookTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.etBookTitle.clearFocus()
                saveCurrentTitle()
            }
            false
        }
        binding.etBookTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveRunnable?.let { handler.removeCallbacks(it) }
                saveRunnable = Runnable { saveCurrentTitle() }
                handler.postDelayed(saveRunnable!!, 1200)
            }
        })
    }

    private fun setupPageCountEdit() {
        binding.tvPageCount.setOnClickListener {
            if (isSpreadMode) showEditSpreadCountDialog()
            else {
                val book = books.getOrNull(binding.viewPager.currentItem)
                if (book != null) showPageCountDialog(book)
            }
        }
    }

    private fun showPageCountDialog(book: Book) {
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
            setOnClickListener {}
        }
        card.addView(TextView(this).apply {
            text = "Jumlah Halaman"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16*dp).toInt(), 0, (4*dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "Jumlah halaman harus genap"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding((16*dp).toInt(), 0, (16*dp).toInt(), (12*dp).toInt())
        })
        val et = EditText(this).apply {
            setText("${book.pageCount}")
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
            setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
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
                val input = et.text.toString().toIntOrNull()
                if (input != null) {
                    if (input < 1) {
                        Toast.makeText(this@MainActivity, "Minimal 1 halaman", Toast.LENGTH_SHORT).show()
                    } else if (input % 2 != 0) {
                        Toast.makeText(this@MainActivity, "Jumlah halaman harus genap", Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val existingCount = db.pageDao().getPageCount(book.id)
                            if (input > existingCount) {
                                for (i in existingCount + 1..input) {
                                    db.pageDao().insert(Page(bookId = book.id, pageNumber = i))
                                }
                            } else if (input < existingCount) {
                                for (i in existingCount downTo input + 1) {
                                    val page = db.pageDao().getPage(book.id, i)
                                    if (page != null) db.pageDao().delete(page)
                                }
                            }
                            val currentBook = db.bookDao().getBook(book.id)
                            if (currentBook != null) {
                                db.bookDao().update(currentBook.copy(pageCount = input))
                            }
                        }
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Halaman diubah ke $input", Toast.LENGTH_SHORT).show()
                    }
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
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun showEditSpreadCountDialog() {
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
            setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
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
                                db.pageDao().insert(Page(bookId = spreadBookId, pageNumber = i))
                            }
                        } else if (newPageCount < currentCount) {
                            for (i in currentCount downTo newPageCount + 1) {
                                val page = db.pageDao().getPage(spreadBookId, i)
                                if (page != null) db.pageDao().delete(page)
                            }
                        }
                        val book = db.bookDao().getBook(spreadBookId)
                        if (book != null) {
                            db.bookDao().update(book.copy(pageCount = newPageCount))
                        }
                    }
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Spread diubah ke $newSpread", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Minimal 1 spread", Toast.LENGTH_SHORT).show()
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

    private fun saveCurrentTitle() {
        if (isSpreadMode) {
            val title = binding.etBookTitle.text.toString().ifBlank { "Jurnal Baru" }
            lifecycleScope.launch(Dispatchers.IO) {
                val book = db.bookDao().getBook(spreadBookId)
                if (book != null && title != book.title) {
                    db.bookDao().update(book.copy(title = title))
                }
            }
        } else {
            val pos = binding.viewPager.currentItem
            val book = books.getOrNull(pos) ?: return
            val title = binding.etBookTitle.text.toString().ifBlank { "Jurnal Baru" }
            if (title == book.title) return
            lifecycleScope.launch { db.bookDao().update(book.copy(title = title)) }
        }
    }

    private fun updateHeader(book: Book?) {
        if (book != null) {
            if (!isGridMode && !isSpreadMode) {
                if (binding.etBookTitle.text.toString() != book.title)
                    binding.etBookTitle.setText(book.title)
            }
            val spreads = (book.pageCount + 1) / 2
            if (isSpreadMode) {
                val totalPages = pages.size.coerceAtLeast(1)
                val totalSpreads = (totalPages + 1) / 2
                val pageStart = currentSpread * 2 + 1
                val pageEnd = minOf((currentSpread + 1) * 2, totalPages)
                binding.tvPageCount.text = "$pageStart - $pageEnd hal : $totalSpreads Spread"
            } else {
                binding.tvPageCount.text = "1 - ${book.pageCount} hal : $spreads Spread"
            }
            binding.etBookTitle.visibility = if (isGridMode) View.GONE else View.VISIBLE
            binding.tvPageCount.visibility = if (isGridMode) View.GONE else View.VISIBLE
        } else {
            binding.etBookTitle.visibility = View.GONE
            binding.tvPageCount.visibility = View.INVISIBLE
        }
    }

    // ─── Bottom Bar ───────────────────────────────────────────
    private fun setupBottomBar() {
        binding.btnAdd.setOnClickListener {
            if (isMidnight) binding.btnAdd.setColorFilter(Color.parseColor("#1B3E98"))
            if (isSpreadMode) {
                addPage()
                if (isMidnight) binding.btnAdd.postDelayed({
                    binding.btnAdd.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            } else {
                showNewBookDialog(onDismiss = {
                    if (isMidnight) binding.btnAdd.setColorFilter(Color.parseColor("#1C1C1E"))
                })
            }
        }
        binding.btnDelete.setOnClickListener {
            if (isMidnight) binding.btnDelete.setColorFilter(Color.parseColor("#1B3E98"))
            if (isSpreadMode) {
                deletePage()
                if (isMidnight) binding.btnDelete.postDelayed({
                    binding.btnDelete.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            } else {
                showDeleteBookDialog(
                    books.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener,
                    onDismiss = {
                        if (isMidnight) binding.btnDelete.setColorFilter(Color.parseColor("#1C1C1E"))
                    }
                )
            }
        }
        binding.btnShare.setOnClickListener {
            if (isMidnight) {
                binding.btnShare.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnShare.postDelayed({
                    binding.btnShare.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            }
            if (isSpreadMode) showShareSpreadDialog() else showShareLibraryDialog()
        }
        binding.btnDuplicate.setOnClickListener {
            if (isMidnight) {
                binding.btnDuplicate.setColorFilter(Color.parseColor("#1B3E98"))
                binding.btnDuplicate.postDelayed({
                    binding.btnDuplicate.setColorFilter(Color.parseColor("#1C1C1E"))
                }, 200)
            }
            if (isSpreadMode) duplicatePage()
        }
        binding.btnSettings.setOnClickListener {
            if (isMidnight) binding.btnSettings.setColorFilter(Color.parseColor("#1B3E98"))
            showSettingsPopup(onDismiss = {
                if (isMidnight) binding.btnSettings.setColorFilter(Color.parseColor("#1C1C1E"))
            })
        }
    }

    private fun updateSpreadBottomBar() {
        binding.btnAdd.contentDescription = "Tambah halaman"
        binding.btnDelete.contentDescription = "Hapus halaman"
        binding.btnDuplicate.visibility = View.VISIBLE
    }

    private fun updateLibraryBottomBar() {
        binding.btnAdd.contentDescription = "Buku baru"
        binding.btnDelete.contentDescription = "Hapus buku"
        binding.btnDuplicate.visibility = View.VISIBLE
    }

    // ─── Observer ─────────────────────────────────────────────
    private fun observeBooks() {
        lifecycleScope.launch {
            db.bookDao().getAllBooks().collectLatest { list ->
                books = list
                pagerAdapter.submitList(list)
                shelfAdapter.submitList(list)
                pendingScrollPos?.let { pos ->
                    val safe = pos.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                    binding.viewPager.post { binding.viewPager.setCurrentItem(safe, true) }
                    pendingScrollPos = null
                }
                updateLibraryContentVisibility()
                val currentBook = if (isSpreadMode) {
                    list.find { it.id == spreadBookId }
                } else {
                    list.getOrNull(binding.viewPager.currentItem)
                }
                updateHeader(currentBook)
                setBottomBarActionsEnabled(list.isNotEmpty())
            }
        }
    }

    private fun setBottomBarActionsEnabled(enabled: Boolean) {
        binding.btnDelete.isEnabled = enabled
        binding.btnShare.isEnabled = enabled
        binding.btnDelete.alpha = if (enabled) 1f else 0.35f
        binding.btnShare.alpha = if (enabled) 1f else 0.35f
    }

    // ─── Unified Settings Popup ────────────────────────────────
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
            setOnClickListener {}
        }

        // Title
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
                setBackgroundColor(Color.parseColor("#2A2A2A"))
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
                            startActivity(Intent(this@MainActivity, PaperStoreActivity::class.java))
                            TransitionHelper.morphForward(this@MainActivity)
                        }
                        "Storage & Data" -> showStorageDataDialog()
                        "Notification" -> Toast.makeText(this@MainActivity, "Notification — segera hadir", Toast.LENGTH_SHORT).show()
                        "Language" -> showLanguageDialog()
                        "Theme" -> showThemeDialog()
                        "Privacy & Security" -> showPrivacyDialog()
                        "Help & Feedback" -> Toast.makeText(this@MainActivity, "Help — hubungi kami di paperleaf@support.id", Toast.LENGTH_LONG).show()
                        "About" -> showAboutDialog()
                        "Support Developer" -> {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://saweria.co/paperleaf")))
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "Buka browser untuk mendukung", Toast.LENGTH_SHORT).show()
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
            setBackgroundColor(Color.parseColor("#2A2A2A"))
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
                Toast.makeText(this@MainActivity, "Premium assets $status", Toast.LENGTH_SHORT).show()
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
                setBackgroundColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                setOnClickListener {
                    prefs.edit().putString("locale", langCode).apply()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Bahasa: $label", Toast.LENGTH_SHORT).show()
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 14f * dp
            }
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
                setBackgroundColor(Color.parseColor("#333333"))
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
                    setBackgroundColor(Color.parseColor("#333333"))
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
                        ThemeManager.applyTheme(this@MainActivity, theme.id)
                        Toast.makeText(this@MainActivity, "Tema: ${theme.name}", Toast.LENGTH_SHORT).show()
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
            setBackgroundColor(Color.parseColor("#333333"))
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
                setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(TextView(this).apply {
            text = "Cache: $cacheSize"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
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
                    Toast.makeText(this@MainActivity, "Cache dihapus", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Gagal menghapus cache", Toast.LENGTH_SHORT).show()
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
            setBackgroundColor(Color.parseColor("#333333"))
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

    private fun showNewBookDialog(onDismiss: () -> Unit = {}) {
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
            setOnClickListener {}
        }
        card.addView(TextView(this).apply {
            text = "Buku Baru"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16*dp).toInt(), 0, (4*dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "Beri nama jurnal baru"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12*dp).toInt())
        })
        val et = EditText(this).apply {
            hint = "Nama jurnal..."
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
            ).also { it.setMargins((12*dp).toInt(), 0, (12*dp).toInt(), (12*dp).toInt()) }
        }
        card.addView(et)
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        btnRow.addView(TextView(this).apply {
            text = "Buat"
            textSize = 15f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                val title = et.text.toString().ifBlank { "Jurnal Baru" }
                lifecycleScope.launch {
                    val id = db.bookDao().insert(Book(title = title, coverColor = covers.random()))
                    repeat(30) { i -> db.pageDao().insert(Page(bookId = id, pageNumber = i + 1)) }
                    pendingScrollPos = 0
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
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        et.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun showDeleteBookDialog(book: Book, onDismiss: () -> Unit = {}) {
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
            setOnClickListener {}
        }
        card.addView(TextView(this).apply {
            text = "Hapus Buku?"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16*dp).toInt(), 0, (4*dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "\"${book.title}\" dan semua halamannya\nakan terhapus permanen."
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding((16*dp).toInt(), 0, (16*dp).toInt(), (16*dp).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        btnRow.addView(TextView(this).apply {
            text = "Hapus"
            textSize = 15f
            setTextColor(Color.parseColor("#FF5252"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                lifecycleScope.launch {
                    val pos = books.indexOf(book)
                    db.pageDao().deleteAllPagesForBook(book.id)
                    db.bookDao().delete(book)
                    FileUtils.deleteBookFiles(this@MainActivity, book.id)
                    pendingScrollPos = if (pos < books.size - 1) pos else (pos - 1).coerceAtLeast(0)
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
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    private fun shareCurrentBook() {
        val book = books.getOrNull(binding.viewPager.currentItem) ?: return
        val file = FileUtils.getPageFile(this, book.id, 1)
        if (!file.exists()) {
            Toast.makeText(this, "Belum ada gambar untuk di-share", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, book.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan ${book.title}"
        ))
    }

    private fun showShareLibraryDialog() {
        val book = books.getOrNull(binding.viewPager.currentItem) ?: return
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
            text = "Bagikan Buku"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })
        data class ShareOption(val label: String, val icon: Int, val desc: String)
        val options = listOf(
            ShareOption("Kirim sebagai Pdf", R.drawable.ic_share, "Semua halaman dalam bentuk PDF"),
            ShareOption("Kirim sebagai Plf", R.drawable.ic_book_open, "Buka & edit di Paperleaf lain")
        )
        options.forEach { opt ->
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#333333"))
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
                        "Kirim sebagai Pdf" -> exportBookAsPdf(book)
                        "Kirim sebagai Plf" -> exportBookAsPlf(book)
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
            setBackgroundColor(Color.parseColor("#333333"))
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
        dialog.show()
    }

    private fun exportBookAsPdf(book: Book) {
        val bookDir = FileUtils.getBookDir(this, book.id)
        val pageFiles = bookDir.listFiles { f -> f.name.startsWith("spread_") && f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        if (pageFiles.isEmpty()) {
            Toast.makeText(this, "Belum ada halaman untuk di-export", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val pdfFile = FileUtils.generatePdf(this@MainActivity, book.id, pageFiles)
            if (pdfFile != null && pdfFile.exists()) {
                val uri = FileUtils.getShareUri(this@MainActivity, pdfFile)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "${book.title}.pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Bagikan PDF ${book.title}"
                ))
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal membuat PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportBookAsPlf(book: Book) {
        val bookDir = FileUtils.getBookDir(this, book.id)
        val pageFiles = bookDir.listFiles { f -> f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        if (pageFiles.isEmpty()) {
            Toast.makeText(this, "Belum ada halaman untuk di-export", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val plfFile = FileUtils.generatePlf(this@MainActivity, pageFiles, book.title)
            if (plfFile != null && plfFile.exists()) {
                val uri = FileUtils.getShareUri(this@MainActivity, plfFile)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.paperleaf.document"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "${book.title}.plf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Bagikan PLF ${book.title}"
                ))
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal membuat PLF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─── Spread Data Loading ──────────────────────────────────
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
        leftBitmap = getCachedSpread(currentSpread)
        rightBitmap = null
    }

    private suspend fun getCachedSpread(idx: Int): Bitmap? {
        spreadCache.get(idx)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            FileUtils.loadBitmap(FileUtils.getSpreadFile(this@MainActivity, spreadBookId, idx))
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
            leftBmp = leftBitmap,
            rightBmp = null,
            pageNum = currentSpread + 1,
            total = totalSpreads
        )
        val pageStart = currentSpread * 2 + 1
        val pageEnd = minOf((currentSpread + 1) * 2, pages.size.coerceAtLeast(1))
        binding.tvPageCount.text = "$pageStart - $pageEnd hal : $totalSpreads Spread"
        LoadingOverlay.hide(this)
    }

    private fun loadGridThumbnails() {
        LoadingOverlay.show(this)
        lifecycleScope.launch {
            val totalSpreads = (pages.size + 1) / 2
            val bitmaps = withContext(Dispatchers.IO) {
                (0 until totalSpreads).map { idx ->
                    spreadCache.get(idx) ?: FileUtils.loadBitmap(
                        FileUtils.getSpreadFile(this@MainActivity, spreadBookId, idx)
                    )?.also { spreadCache.put(idx, it) }
                }
            }
            val spreadPages = (0 until totalSpreads).map {
                pages.getOrNull(it * 2) ?: pages.firstOrNull() ?: Page(bookId = spreadBookId, pageNumber = 1)
            }
            gridAdapter.submitData(spreadPages, bitmaps)
            LoadingOverlay.hide(this@MainActivity)
        }
    }

    // ─── Spread Gesture ───────────────────────────────────────
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

    // ─── Spread Navigation ────────────────────────────────────
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
        val pageNumber = spreadIdx * 2 + 1
        startActivity(Intent(this, SketchActivity::class.java).apply {
            putExtra(SketchActivity.EXTRA_BOOK_ID, spreadBookId)
            putExtra(SketchActivity.EXTRA_PAGE_NUMBER, pageNumber)
        })
        TransitionHelper.morphForward(this@MainActivity)
    }

    // ─── Spread Grid ──────────────────────────────────────────
    private fun setupSpreadGrid() {
        gridAdapter = PageGridAdapter { spreadIdx -> openSketch(spreadIdx) }
        binding.rvPageGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = gridAdapter
        }
    }

    // ─── Spread Actions ───────────────────────────────────────
    private fun shareCurrentPage() {
        val file = FileUtils.getSpreadFile(this, spreadBookId, currentSpread)
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
        val file = FileUtils.getSpreadFile(this, spreadBookId, currentSpread)
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
                setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(btnClose)
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        if (file.exists()) {
            TransitionHelper.morphDialog(dialog)
            dialog.show()
        } else Toast.makeText(this, "Halaman masih kosong", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal menyimpan", Toast.LENGTH_SHORT).show()
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
            val plfFile = FileUtils.generatePlf(this@MainActivity, listOf(file), "spread_${currentSpread + 1}")
            if (plfFile != null && plfFile.exists()) {
                val uri = FileUtils.getShareUri(this@MainActivity, plfFile)
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
                    Toast.makeText(this@MainActivity, "Gagal membuat PLF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePlfIntent() {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val path = uri.path?.lowercase() ?: return
        if (!path.endsWith(".plf")) return
        lifecycleScope.launch {
            try {
                val importedBook = db.bookDao().getBookByTitle("Imported")
                    ?: Book(title = "Imported", coverColor = covers.random(), pageCount = 0).let { b ->
                        val id = db.bookDao().insert(b)
                        db.bookDao().getBook(id)
                    } ?: return@launch
                val bookDir = FileUtils.getBookDir(this@MainActivity, importedBook.id)
                val pageCount = withContext(Dispatchers.IO) {
                    FileUtils.importPlf(this@MainActivity, uri, bookDir)
                }
                if (pageCount > 0) {
                    val existing = db.pageDao().getPageCount(importedBook.id)
                    for (i in 0 until pageCount) {
                        db.pageDao().insert(
                            Page(bookId = importedBook.id, pageNumber = existing + i + 1)
                        )
                    }
                    db.bookDao().update(importedBook.copy(pageCount = existing + pageCount))
                    Toast.makeText(this@MainActivity, "Berhasil mengimpor $pageCount halaman", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Gagal mengimpor file PLF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun duplicatePage() {
        Toast.makeText(this, "Halaman diduplikat", Toast.LENGTH_SHORT).show()
    }

    private fun addPage() {
        lifecycleScope.launch {
            val newNum = pages.size + 1
            db.pageDao().insert(Page(bookId = spreadBookId, pageNumber = newNum))
            val book = db.bookDao().getBook(spreadBookId)
            if (book != null) {
                db.bookDao().update(book.copy(pageCount = newNum))
            }
            Toast.makeText(this@MainActivity, "Halaman $newNum ditambahkan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePage() {
        if (pages.isEmpty()) return
        lifecycleScope.launch {
            val spreadPage = currentSpread * 2 + 1
            val page = pages.getOrNull(spreadPage - 1) ?: return@launch
            db.pageDao().delete(page)
            val remaining = db.pageDao().getPageCount(spreadBookId)
            val book = db.bookDao().getBook(spreadBookId)
            if (book != null) {
                db.bookDao().update(book.copy(pageCount = remaining))
            }
            if (currentSpread > 0) currentSpread -= 1
        }
    }

    // ─── Template Popup ───────────────────────────────────────
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

            val thumbIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#333333"))
                    cornerRadius = 14f * dp
                }
                clipToOutline = true
            }
            val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            PaperTemplateEngine.drawThumbnail(cv, thumbW.toFloat(), thumbH.toFloat(), type)
            thumbIv.setImageBitmap(bmp)
            card.addView(thumbIv)

            card.addView(TextView(this).apply {
                text = Page.templateName(type)
                textSize = 10f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })

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
                Toast.makeText(this@MainActivity,
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

    // ─── PageGridAdapter ──────────────────────────────────────
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

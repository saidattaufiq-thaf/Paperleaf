package com.paperleaf.sketchbook.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import kotlin.math.ceil
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.databinding.ActivitySketchBinding
import com.paperleaf.sketchbook.db.AppDatabase
import com.paperleaf.sketchbook.model.BrushSettings
import com.paperleaf.sketchbook.model.Page
import com.paperleaf.sketchbook.utils.FileUtils
import com.paperleaf.sketchbook.view.DrawingView
import com.paperleaf.sketchbook.theme.ThemeManager
import com.paperleaf.sketchbook.utils.TransitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SketchActivity : AppCompatActivity(), ThemeManager.OnThemeChangeListener {

    // ─── CONSTANTS ───────────────────────────────────────────────
    companion object {
        const val EXTRA_BOOK_ID     = "extra_book_id"
        const val EXTRA_PAGE_NUMBER = "extra_page_number"

        const val SIZE_SMALL  = 6f
        const val SIZE_MEDIUM = 16f
        const val SIZE_LARGE  = 36f
    }

    // ─── PROPERTIES & STATE ──────────────────────────────────────
    private lateinit var binding: ActivitySketchBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private var bookId      = 0L
    private var pageNumber  = 0
    private var currentPage: Page? = null
    // Helper untuk mendapatkan index spread berdasarkan pageNumber saat ini
    private val spreadIndex get() = (pageNumber - 1) / 2

    private var isSaving = false

    // Color State
    private val presetColors = mutableListOf<Int>()

    // Preference key
    private val PREFS = "paperleaf_prefs"
    private val KEY_COLORS = "palette_colors"

    private var activeColorIndex = 0
    private val colorViews = mutableListOf<View>()
    
    // Popups
    private var colorPickerPopup: ColorPickerPopup? = null
    private var shapePopup: TooltipPopup? = null
    private var layersPopup: PopupWindow? = null
    private var scissorPopup: TooltipPopup? = null
    private var imageSourcePopup: TooltipPopup? = null
    private var imageEditToolbar: PopupWindow? = null

    // Layer RecyclerView
    private var layerAdapter: LayerAdapter? = null
    private var layerRecyclerView: RecyclerView? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var lastLayerCount: Int = 0
    private var suppressLayersChanged = false

    private val isDeepOcean: Boolean get() = ThemeManager.current.id == "deep_ocean"
    private val isMidnight: Boolean get() = ThemeManager.current.id == "ios_dark"
    private fun deepOceanBg(fallback: Int): Int = if (isDeepOcean) Color.parseColor("#124260") else fallback

    // Helper untuk list tombol tools
    private val toolBtns get() = listOf(
        binding.btnPen, binding.btnPencil, binding.btnMarker,
        binding.btnInkPen, binding.btnEraser,
        binding.btnBrush, binding.btnRuler, binding.btnKeyboard, binding.btnScissors, binding.btnRoller
    )

    private fun decodeUri(uri: Uri): Bitmap? {
        val maxDim = 2048
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    if (info.size.width > maxDim || info.size.height > maxDim) {
                        val scale = maxOf(
                            info.size.width.toFloat() / maxDim,
                            info.size.height.toFloat() / maxDim
                        )
                        decoder.setTargetSampleSize(kotlin.math.ceil(scale).toInt())
                    }
                }
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    val scale = maxOf(
                        opts.outWidth.toFloat() / maxDim,
                        opts.outHeight.toFloat() / maxDim
                    )
                    val sampleSize = kotlin.math.ceil(scale).toInt().coerceAtLeast(1)
                    contentResolver.openInputStream(uri)?.use { input2 ->
                        BitmapFactory.decodeStream(input2, null,
                            BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        )
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            LoadingOverlay.show(this@SketchActivity)
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = decodeUri(uri)
                withContext(Dispatchers.Main) {
                    LoadingOverlay.hide(this@SketchActivity)
                    if (bmp != null) {
                        binding.drawingView.enterImageEditMode(bmp)
                        showImageEditToolbar()
                    }
                }
            }
        }

    // ─── LIFECYCLE ───────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ThemeManager.init(this)
        ThemeManager.addListener(this)

        binding     = ActivitySketchBinding.inflate(layoutInflater)
        bookId      = intent.getLongExtra(EXTRA_BOOK_ID, 0)
        pageNumber  = intent.getIntExtra(EXTRA_PAGE_NUMBER, 1)

        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            insets
        }
        hideSystemUI()
        applyThemeColors()
        loadSavedPalette()
        setupUI()
        loadPage()
    }

    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            binding.drawingView.clearUndoRedo()
        }
    }
	
    override fun onResume() {
        super.onResume()
        binding.drawingView.invalidate()
        if (layersPopup?.isShowing == true) {
            val dv = binding.drawingView
            layerAdapter?.layers = dv.layers
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isSaving) {
            val dv = binding.drawingView
            dv.flattenLayersForSave()
            val bmp = dv.getBitmap()
            lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
                dv.saveLayers(bookId, spreadIndex)
                if (bmp != null) {
                    FileUtils.saveBitmap(bmp, FileUtils.getSpreadFile(this@SketchActivity, bookId, spreadIndex))
                }
            }
            saveTemplate()
            savePalette()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        }
    }

    // ─── UI SETUP ────────────────────────────────────────────────
    private fun setupUI() {
        setupSystemBack()
        val defaultIcon = when {
            isMidnight -> Color.WHITE
            isDeepOcean -> Color.parseColor("#BEEDE9")
            else -> Color.BLACK
        }
        val undoRedoColor = when {
            isMidnight -> Color.WHITE
            else -> Color.parseColor("#12415E")
        }
        binding.btnUndo.setColorFilter(undoRedoColor)
        binding.btnUndo.setOnClickListener { 
            binding.drawingView.undo()
            showTouchEffect(binding.btnUndo)
        }
        binding.btnRedo.setColorFilter(undoRedoColor)
        binding.btnRedo.setOnClickListener { 
            binding.drawingView.redo()
            showTouchEffect(binding.btnRedo)
        }
        binding.btnBrush.setOnClickListener    { onToolClick(DrawingView.TOOL_BRUSH, it) }
        binding.btnRuler.setOnClickListener    { showShapePopup(it) }
        binding.btnKeyboard.setOnClickListener { onKeyboardToolClick(it) }
        binding.btnScissors.setOnClickListener { showScissorPopup(it) }
        binding.btnRoller.setOnClickListener   { onToolClick(DrawingView.TOOL_ROLLER, it) }
        binding.btnLayers.setColorFilter(defaultIcon)
        binding.btnLayers.setOnClickListener   { toggleLayersPopup() }

        binding.btnPen.setOnClickListener    { onToolClick(BrushSettings.TOOL_FOUNTAIN_PEN, it) }
        binding.btnPencil.setOnClickListener { onToolClick(BrushSettings.TOOL_PENCIL, it) }
        binding.btnMarker.setOnClickListener { onToolClick(BrushSettings.TOOL_MARKER, it) }
        binding.btnInkPen.setOnClickListener { onToolClick(BrushSettings.TOOL_INK_PEN, it) }
        binding.btnEraser.setOnClickListener { onToolClick(BrushSettings.TOOL_ERASER, it) }

        binding.btnMove.setOnClickListener { toggleTransformMode() }

        // Callback Layers
        binding.drawingView.onLayersChanged = {
            if (!suppressLayersChanged) {
                val dv = binding.drawingView
                val currentCount = dv.layers.size
                val wasAdded = currentCount > lastLayerCount
                lastLayerCount = currentCount
                if (layersPopup?.isShowing == true) {
                    layerAdapter?.canvasBitmap = dv.getBitmap()
                    layerAdapter?.layers = dv.layers
                    if (wasAdded) {
                        layerRecyclerView?.smoothScrollToPosition(currentCount + 1)
                    }
                }
            }
        }

        binding.drawingView.onSelectionChanged = {
            runOnUiThread {
                if (scissorPopup?.isShowing == true) {
                    scissorPopup?.dismiss()
                    scissorPopup = null
                }
                updateScissorBtnState()
            }
        }

        binding.drawingView.onImageEditModeChanged = { active ->
            runOnUiThread {
                if (!active) dismissImageEditToolbar()
            }
        }

        setupColorPalette()

        // Import Photo Button — show source selection popup
        binding.btnImportPhoto.setOnClickListener {
            showImageSourcePopup(it)
        }

        selectTool(BrushSettings.TOOL_FOUNTAIN_PEN)

        binding.drawingView.onKeyboardTap = { bmpX, bmpY ->
            onKeyboardTap(bmpX, bmpY)
        }

        binding.drawingView.onTextLayerTap = { layer ->
            onTextLayerTap(layer)
        }

        binding.drawingView.onTextLayerWarning = {
            onTextLayerWarning()
        }

        setupTextOverlay()
    }

    // ─── THEME ───────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun applyThemeColors() {
        val c = ThemeManager.getThemeColors()
        window.statusBarColor = c.background

        if (isMidnight) {
            applyMidnightTheme()
        } else if (isDeepOcean) {
            applyDeepOceanTheme()
        } else {
            val iconColor = c.toolbarIcon
            binding.btnUndo.setColorFilter(Color.parseColor("#12415E"))
            binding.btnRedo.setColorFilter(Color.parseColor("#12415E"))
            binding.btnLayers.setColorFilter(iconColor)
            val moveColor = if (binding.drawingView.isTransformMode) Color.parseColor("#3CBEC3") else iconColor
            binding.btnMove.setColorFilter(moveColor)

            toolBtns.forEach {
                it.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            }
        }
    }

    private fun applyDeepOceanTheme() {
        binding.bottomArea.setBackgroundColor(Color.parseColor("#1A6B77"))

        val defaultIcon = Color.parseColor("#BEEDE9")
        binding.btnUndo.setColorFilter(Color.parseColor("#12415E"))
        binding.btnRedo.setColorFilter(Color.parseColor("#12415E"))
        binding.btnLayers.setColorFilter(defaultIcon)
        val moveColor = if (binding.drawingView.isTransformMode) Color.parseColor("#3CBEC3") else defaultIcon
        binding.btnMove.setColorFilter(moveColor)

        toolBtns.forEach {
            it.imageTintList = android.content.res.ColorStateList.valueOf(defaultIcon)
        }
    }

    private fun applyMidnightTheme() {
        binding.root.setBackgroundColor(Color.parseColor("#3C3C3C"))
        binding.drawingView.setBackgroundColor(Color.parseColor("#3C3C3C"))

        val defaultIcon = Color.WHITE
        binding.btnUndo.setColorFilter(defaultIcon)
        binding.btnRedo.setColorFilter(defaultIcon)
        binding.btnLayers.setColorFilter(defaultIcon)
        val moveColor = if (binding.drawingView.isTransformMode) Color.parseColor("#1B3E98") else defaultIcon
        binding.btnMove.setColorFilter(moveColor)

        toolBtns.forEach {
            it.imageTintList = android.content.res.ColorStateList.valueOf(defaultIcon)
        }
    }

    override fun onThemeChanged(theme: com.paperleaf.sketchbook.theme.ThemeConfig) {
        runOnUiThread {
            applyThemeColors()
        }
    }

    // ─── TEXT DIALOG-BASED EDITOR ──────────────────────────────
    // Single field: which layer is being edited (null = new text)
    private var textDialogLayer: DrawingView.ImageLayer? = null
    private var textOverlayActive = false
    private var editingTextLayer: DrawingView.ImageLayer? = null

    @Suppress("UNUSED_PARAMETER")
    private fun onKeyboardToolClick(view: View) {
        if (textOverlayActive) {
            showTextFormattingPopup()
        } else {
            selectTool(DrawingView.TOOL_KEYBOARD)
            val sel = binding.drawingView.selectedLayerOrNull
            showTextOverlay(if (sel?.isTextLayer == true) sel else null)
        }
    }

    private fun showTextEditorDialog(existingLayer: DrawingView.ImageLayer? = null) {
        textDialogLayer = existingLayer
        val dp = resources.displayMetrics.density

        // ── Gather initial values ──
        val initialText = existingLayer?.textContent ?: ""
        val initialFontSize = existingLayer?.textFontSize ?: 48f
        val initialColor = existingLayer?.let {
            it.bitmap.getPixel(2, 2).takeIf { c -> c != 0 }
        } ?: binding.drawingView.brushSettings.color
        val initialAlign = existingLayer?.textAlign ?: Paint.Align.LEFT
        val initialBold = existingLayer?.textIsBold ?: false
        val initialItalic = existingLayer?.textIsItalic ?: false

        // ── Build dialog view ──
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }

        // Title
        container.addView(TextView(this).apply {
            text = if (existingLayer != null) "Edit Text" else "Add Text"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        // Spacer
        container.addView(createSpacer(dp, 12))

        // ── EditText (multi-line) ──
        val editText = EditText(this).apply {
            setTextColor(Color.BLACK)
            textSize = 18f
            hint = "Type here..."
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 10f * dp
            }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (150 * dp).toInt()
            )
            gravity = Gravity.TOP or Gravity.START
            if (initialText.isNotEmpty()) setText(initialText)
            setSelection(length())
        }
        container.addView(editText)

        container.addView(createSpacer(dp, 16))

        // ── Font Row ──
        container.addView(createLabel("Font", dp))
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var currentFontPath = existingLayer?.textTypeface?.let { "" } ?: ""
        val fontDisplay = TextView(this).apply {
            text = "Default"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A3A"))
                cornerRadius = 8f * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (8 * dp).toInt()
            }
            setOnClickListener {
                val fonts = listOf(
                    "Default" to "",
                    "Lora Variable" to "fonts/Lora-VariableFont_wght.ttf",
                    "Lora Italic" to "fonts/Lora-Italic-VariableFont_wght.ttf"
                )
                val currentIdx = fonts.indexOfFirst { it.second == currentFontPath }
                val nextIdx = (currentIdx + 1) % fonts.size
                val (label, path) = fonts[nextIdx]
                text = label
                currentFontPath = path
            }
        }
        fontRow.addView(fontDisplay)

        var currentBold = initialBold
        var currentItalic = initialItalic
        val boldBtn = createToggleButton("B", currentBold, dp).apply {
            setOnClickListener {
                currentBold = !currentBold
                (background as? GradientDrawable)?.setColor(if (currentBold) Color.WHITE else Color.parseColor("#3A3A3A"))
                setTextColor(if (currentBold) Color.BLACK else Color.WHITE)
            }
        }
        val italicBtn = createToggleButton("I", currentItalic, dp).apply {
            setOnClickListener {
                currentItalic = !currentItalic
                (background as? GradientDrawable)?.setColor(if (currentItalic) Color.WHITE else Color.parseColor("#3A3A3A"))
                setTextColor(if (currentItalic) Color.BLACK else Color.WHITE)
            }
        }
        fontRow.addView(boldBtn)
        fontRow.addView(italicBtn.apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply {
                leftMargin = (6 * dp).toInt()
            }
        })
        container.addView(fontRow)

        container.addView(createSpacer(dp, 12))

        // ── Size + Alignment ──
        val sizeAlignRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        sizeAlignRow.addView(createLabel("Size", dp).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        val sizeEdit = android.widget.EditText(this).apply {
            setText(initialFontSize.toInt().toString())
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A3A"))
                cornerRadius = 8f * dp
            }
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((70 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        sizeAlignRow.addView(sizeEdit.apply {
            layoutParams = LinearLayout.LayoutParams((70 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (8 * dp).toInt()
                rightMargin = (16 * dp).toInt()
            }
        })

        // Alignment buttons
        var currentAlign = initialAlign
        listOf<Triple<String, Paint.Align, Unit>>(
            Triple("L", Paint.Align.LEFT, Unit),
            Triple("C", Paint.Align.CENTER, Unit),
            Triple("R", Paint.Align.RIGHT, Unit)
        ).forEach { (label, align, _) ->
            sizeAlignRow.addView(createChip(label, currentAlign == align, dp).apply {
                setOnClickListener {
                    currentAlign = align
                    // Refresh chip states
                    for (i in 0 until (this.parent as ViewGroup).childCount) {
                        val child = (this.parent as ViewGroup).getChildAt(i)
                        if (child is TextView && child.text.length == 1) {
                            child.alpha = if (child == this) 1f else 0.5f
                        }
                    }
                }
                layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (40 * dp).toInt()).apply {
                    leftMargin = if (label == "L") 0 else (4 * dp).toInt()
                }
            })
        }
        container.addView(sizeAlignRow)

        container.addView(createSpacer(dp, 12))

        // ── Color presets ──
        container.addView(createLabel("Color", dp))
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        var selectedColor = initialColor
        val colors = listOf(
            Color.BLACK, Color.WHITE, Color.RED, Color.parseColor("#FF9800"),
            Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
            Color.parseColor("#9C27B0"), Color.GRAY
        )
        val colorViews = mutableListOf<android.view.View>()
        colors.forEach { c ->
            colorRow.addView(android.view.View(this).apply {
                background = GradientDrawable().apply {
                    setColor(c)
                    shape = GradientDrawable.OVAL
                    if (c == selectedColor) setStroke((3 * dp).toInt(), Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                    leftMargin = if (c == colors.first()) 0 else (8 * dp).toInt()
                }
                setOnClickListener {
                    selectedColor = c
                    colorViews.forEach { v ->
                        val gd = v.background as? GradientDrawable
                        gd?.setStroke(0, 0)
                    }
                    (this.background as? GradientDrawable)?.setStroke((3 * dp).toInt(), Color.WHITE)
                    invalidate()
                }
                colorViews.add(this)
            })
        }
        container.addView(colorRow)

        container.addView(createSpacer(dp, 20))

        // ── Buttons: OK / Cancel ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        btnRow.addView(cancelBtn)

        val okBtn = TextView(this).apply {
            text = "OK"
            textSize = 16f
            setTextColor(Color.parseColor("#2196F3"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (12 * dp).toInt() }
        }
        btnRow.addView(okBtn)
        container.addView(btnRow)

        root.addView(container)

        // ── Show as dialog ──
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar)
            .setView(root as android.view.View)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#1E1E1E")))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        TransitionHelper.morphDialog(dialog)
        dialog.show()

        // Wire button actions
        okBtn.setOnClickListener {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                val fontSize = sizeEdit.text.toString().toFloatOrNull() ?: initialFontSize
                doCreateOrEditText(existingLayer, text, fontSize, selectedColor, currentAlign, currentFontPath, currentBold, currentItalic)
            }
            dialog.dismiss()
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
    }

    private fun doCreateOrEditText(
        existingLayer: DrawingView.ImageLayer?,
        text: String,
        fontSize: Float,
        color: Int,
        align: Paint.Align,
        fontPath: String,
        isBold: Boolean = false,
        isItalic: Boolean = false
    ) {
        if (existingLayer != null && binding.drawingView.layers.contains(existingLayer)) {
            existingLayer.textContent = text
            existingLayer.textFontSize = fontSize
            existingLayer.textAlign = align
            existingLayer.textIsBold = isBold
            existingLayer.textIsItalic = isItalic
            rerenderTextLayer(existingLayer, text, fontSize, color, align, fontPath, isBold, isItalic)
            binding.drawingView.invalidate()
            showTextToolbarPopup(existingLayer)
        } else {
            val cx = (binding.drawingView.width / 2f - binding.drawingView.canvasTransX) / binding.drawingView.canvasScale
            val cy = (binding.drawingView.height / 2f - binding.drawingView.canvasTransY) / binding.drawingView.canvasScale
            val bmp = renderTextToBitmap(text, fontSize, color, align, fontPath, isBold, isItalic)
            val layerNum = binding.drawingView.layers.size + 1
            val layer = DrawingView.ImageLayer(
                bitmap = bmp, canvas = Canvas(bmp),
                x = cx - bmp.width / 2f, y = cy - bmp.height / 2f,
                scale = 1f, rotation = 0f,
                name = "Text $layerNum",
                isTextLayer = true,
                textContent = text,
                textFontSize = fontSize,
                textAlign = align,
                textIsBold = isBold,
                textIsItalic = isItalic
            )
            binding.drawingView.layers.forEach { it.isSelected = false }
            binding.drawingView.layers.add(layer)
            binding.drawingView.selectLayer(layer)
            binding.drawingView.onLayersChanged?.invoke()
            binding.drawingView.invalidate()
            showTextToolbarPopup(layer)
        }
    }

    private fun renderTextToBitmap(
        text: String, fontSize: Float, color: Int, align: Paint.Align, fontPath: String,
        isBold: Boolean = false, isItalic: Boolean = false
    ): Bitmap {
        val pad = 20f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = fontSize
            if (fontPath.isNotEmpty()) {
                try { typeface = Typeface.createFromAsset(assets, fontPath) } catch (_: Exception) {}
            }
            if (isBold) {
                if (typeface == null || fontPath.isEmpty()) {
                    typeface = Typeface.defaultFromStyle(Typeface.BOLD)
                } else {
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                }
                isFakeBoldText = true
            }
            if (isItalic) {
                if (typeface == null || fontPath.isEmpty()) {
                    typeface = Typeface.defaultFromStyle(Typeface.ITALIC)
                } else {
                    typeface = Typeface.create(typeface, Typeface.ITALIC)
                }
            }
            this.textAlign = align
        }
        val lines = text.split('\n')
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val textW = paint.measureText(text)
        val textH = lineH * lines.size
        val bmpW = ceil(textW + pad * 2).toInt().coerceAtLeast(1)
        val bmpH = ceil(textH + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        lines.forEachIndexed { i, line ->
            val x = when (align) {
                Paint.Align.CENTER -> bmpW / 2f
                Paint.Align.RIGHT -> bmpW - pad
                else -> pad
            }
            val y = pad + lineH * i + fm.descent
            cv.drawText(line, x, y, paint)
        }
        return bmp
    }

    private fun rerenderTextLayer(
        layer: DrawingView.ImageLayer,
        text: String, fontSize: Float, color: Int, align: Paint.Align, fontPath: String,
        isBold: Boolean = false, isItalic: Boolean = false
    ) {
        val bmp = renderTextToBitmap(text, fontSize, color, align, fontPath, isBold, isItalic)
        layer.bitmap = bmp
        layer.canvas = Canvas(bmp)
    }

    private fun showTextToolbarPopup(layer: DrawingView.ImageLayer) {
        val dp = resources.displayMetrics.density
        val iconSize = (40 * dp).toInt()

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }

        fun iconBtn(res: Int, action: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            setBackgroundResource(android.R.color.transparent)
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setColorFilter(Color.WHITE)
            setOnClickListener { action() }
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        leftCol.addView(iconBtn(R.drawable.rotate) {
            layer.rotation = (layer.rotation + 15f) % 360f
            binding.drawingView.invalidate()
        })
        toolbar.addView(leftCol)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        rightCol.addView(iconBtn(R.drawable.text_setting) {
            showTextEditorDialog(layer)
        })
        rightCol.addView(iconBtn(R.drawable.rename) {
            showTextEditorDialog(layer)
        })
        toolbar.addView(rightCol)

        textDialogLayer = layer

        val popup = TooltipPopup(
            ctx = this, content = toolbar,
            arrowPosition = BubbleDrawable.ArrowPosition.TOP_CENTER,
            backgroundColor = deepOceanBg(Color.parseColor("#2C2C2C"))
        )
        popup.setOnDismissListener {
            textDialogLayer = null
        }
        // Just show at a fixed offset near the layer
        val screenPos = IntArray(2)
        binding.root.getLocationOnScreen(screenPos)
        val sx = screenPos[0] + (layer.x + binding.drawingView.canvasTransX) * binding.drawingView.canvasScale + 50
        val sy = screenPos[1] + (layer.y + binding.drawingView.canvasTransY) * binding.drawingView.canvasScale
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, sx.toInt(), sy.toInt())
    }

    // ── Dialog helper helpers ──
    private fun createSpacer(dp: Float, h: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, (h * dp).toInt())
    }

    private fun createLabel(s: String, dp: Float) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.parseColor("#AAAAAA"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4 * dp).toInt() }
    }

    private fun createToggleButton(label: String, initial: Boolean, dp: Float) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (initial) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (initial) Color.WHITE else Color.parseColor("#3A3A3A"))
            cornerRadius = 6f * dp
        }
        layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
        setOnClickListener {
            val now = textColors?.defaultColor == Color.WHITE
            (background as? GradientDrawable)?.setColor(if (now) Color.WHITE else Color.parseColor("#3A3A3A"))
            setTextColor(if (now) Color.BLACK else Color.WHITE)
        }
    }

    private fun createChip(label: String, selected: Boolean, dp: Float) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        alpha = if (selected) 1f else 0.5f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#444444"))
            cornerRadius = 8f * dp
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt()) }
    }

    private fun dismissTextPopup() {
        textDialogLayer = null
        dismissKeyboard()
    }

    private fun dismissKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ─── TEXT OVERLAY ──────────────────────────────────────────
    private fun setupTextOverlay() {
        with(binding.textOverlay) {
            visibility = View.GONE
            onCommit = { commitTextOverlay() }
            onCancel = { cancelTextOverlay() }
        }
    }

    private fun showTextOverlay(existingLayer: DrawingView.ImageLayer? = null) {
        editingTextLayer = existingLayer
        val dv = binding.drawingView
        val overlay = binding.textOverlay

        if (existingLayer != null) {
            overlay.setText(existingLayer.textContent ?: "")
            overlay.textColor = existingLayer.bitmap.getPixel(2, 2).takeIf { it != 0 }
                ?: dv.brushSettings.color
            overlay.textFontSize = existingLayer.textFontSize
            overlay.isBold = existingLayer.textIsBold
            overlay.isItalic = existingLayer.textIsItalic
            val align = when (existingLayer.textAlign) {
                Paint.Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
                Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            overlay.textAlignment = align

            val (vx, vy) = dv.bitmapToViewCoords(existingLayer.x, existingLayer.y)
            overlay.setTextPosition(vx, vy)
            overlay.setTextScale(existingLayer.scale)
            overlay.setTextRotation(existingLayer.rotation)
        } else {
            overlay.setText("")
            overlay.textColor = dv.brushSettings.color
            overlay.textFontSize = 48f
            overlay.isBold = false
            overlay.isItalic = false
            overlay.fontPath = ""
            overlay.textAlignment = Layout.Alignment.ALIGN_NORMAL
            overlay.setTextScale(1f)
            overlay.setTextRotation(0f)
        }

        overlay.visibility = View.VISIBLE
        overlay.post {
            val cx = (dv.width - overlay.width) / 2f
            val cy = (dv.height / 3f) - overlay.height / 2f
            overlay.setTextPosition(cx.coerceAtLeast(0f), cy.coerceAtLeast(0f))
        }
        overlay.requestEditTextFocus()
        textOverlayActive = true

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(overlay.editTextField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitTextOverlay() {
        val overlay = binding.textOverlay
        val text = overlay.getText().trim()
        if (text.isEmpty()) {
            cancelTextOverlay()
            return
        }

        val dv = binding.drawingView
        val vx = overlay.translationX
        val vy = overlay.translationY
        val scale = overlay.scaleX
        val rotation = overlay.rotation

        val (bmpX, bmpY) = dv.viewToBitmapCoords(vx, vy)

        val color = overlay.textColor
        val fontSize = overlay.textFontSize
        val align = when (overlay.textAlignment) {
            Layout.Alignment.ALIGN_NORMAL -> Paint.Align.LEFT
            Layout.Alignment.ALIGN_CENTER -> Paint.Align.CENTER
            Layout.Alignment.ALIGN_OPPOSITE -> Paint.Align.RIGHT
        }

        if (editingTextLayer != null && dv.layers.contains(editingTextLayer)) {
            val layer = editingTextLayer!!
            layer.textContent = text
            layer.textFontSize = fontSize
            layer.textAlign = align
            layer.textIsBold = overlay.isBold
            layer.textIsItalic = overlay.isItalic
            val bmp = renderTextToBitmap(text, fontSize, color, align, overlay.fontPath, overlay.isBold, overlay.isItalic)
            layer.bitmap = bmp
            layer.canvas = Canvas(bmp)
            layer.x = bmpX
            layer.y = bmpY
            layer.scale = scale
            layer.rotation = rotation
            dv.invalidate()
        } else {
            val bmp = renderTextToBitmap(text, fontSize, color, align, overlay.fontPath, overlay.isBold, overlay.isItalic)
            val layerNum = dv.layers.size + 1
            val layer = DrawingView.ImageLayer(
                bitmap = bmp, canvas = Canvas(bmp),
                x = bmpX, y = bmpY,
                scale = scale, rotation = rotation,
                name = "Text $layerNum",
                isTextLayer = true,
                textContent = text,
                textFontSize = fontSize,
                textAlign = align,
                textIsBold = overlay.isBold,
                textIsItalic = overlay.isItalic
            )
            dv.layers.forEach { it.isSelected = false }
            dv.layers.add(layer)
            dv.selectLayer(layer)
            dv.onLayersChanged?.invoke()
            dv.invalidate()
        }
        hideTextOverlay()
    }

    private fun cancelTextOverlay() {
        hideTextOverlay()
    }

    private fun hideTextOverlay() {
        binding.textOverlay.visibility = View.GONE
        textOverlayActive = false
        editingTextLayer = null
        dismissKeyboard()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    private fun showTextFormattingPopup() {
        val overlay = binding.textOverlay
        val dp = resources.displayMetrics.density

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }

        // ── Size ──
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(TextView(this).apply {
            text = "Size"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sizeEdit = EditText(this).apply {
            setText(overlay.textFontSize.toInt().toString())
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A3A"))
                cornerRadius = 8f * dp
            }
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((70 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        sizeRow.addView(sizeEdit)
        content.addView(sizeRow)

        content.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (12 * dp).toInt())
        })

        // ── Bold / Italic ──
        val styleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        styleRow.addView(TextView(this).apply {
            text = "Style"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val boldBtn = TextView(this).apply {
            text = "B"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (overlay.isBold) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (overlay.isBold) Color.WHITE else Color.parseColor("#3A3A3A"))
                cornerRadius = 6f * dp
            }
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (40 * dp).toInt()).apply {
                rightMargin = (6 * dp).toInt()
            }
        }
        val italicBtn = TextView(this).apply {
            text = "I"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (overlay.isItalic) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (overlay.isItalic) Color.WHITE else Color.parseColor("#3A3A3A"))
                cornerRadius = 6f * dp
            }
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (40 * dp).toInt())
        }
        styleRow.addView(boldBtn)
        styleRow.addView(italicBtn)
        content.addView(styleRow)

        content.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (12 * dp).toInt())
        })

        // ── Alignment ──
        val alignRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alignRow.addView(TextView(this).apply {
            text = "Align"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        data class AlignOption(val label: String, val align: Layout.Alignment)
        val alignOptions = listOf(
            AlignOption("L", Layout.Alignment.ALIGN_NORMAL),
            AlignOption("C", Layout.Alignment.ALIGN_CENTER),
            AlignOption("R", Layout.Alignment.ALIGN_OPPOSITE)
        )
        val alignChips = mutableListOf<TextView>()
        for (opt in alignOptions) {
            alignChips.add(TextView(this).apply {
                text = opt.label
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = if (overlay.textAlignment == opt.align) 1f else 0.5f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 8f * dp
                }
                layoutParams = LinearLayout.LayoutParams(
                    (44 * dp).toInt(), (40 * dp).toInt()
                ).apply { if (opt != alignOptions.first()) leftMargin = (4 * dp).toInt() }
                setOnClickListener {
                    overlay.textAlignment = opt.align
                    alignChips.forEach { it.alpha = 0.5f }
                    this.alpha = 1f
                }
            })
        }
        alignChips.forEach { alignRow.addView(it) }
        content.addView(alignRow)

        content.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (16 * dp).toInt())
        })

        // ── OK button ──
        content.addView(TextView(this).apply {
            text = "Apply"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2196F3"))
                cornerRadius = 8f * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()
            )
            setOnClickListener {
                val newSize = sizeEdit.text.toString().toFloatOrNull()
                if (newSize != null) overlay.textFontSize = newSize
                overlay.isBold = boldBtn.textColors?.defaultColor == Color.BLACK
                overlay.isItalic = italicBtn.textColors?.defaultColor == Color.BLACK
            }
        })

        TooltipPopup(
            ctx = this,
            content = content,
            arrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
            backgroundColor = Color.parseColor("#2C2C2C")
        ).showAt(binding.btnKeyboard, 0)
    }

    // ── CALLBACKS (dipanggil dari DrawingView) ─────────────────
    @Suppress("UNUSED_PARAMETER")
    private fun onKeyboardTap(bmpX: Float, bmpY: Float) {
        if (textOverlayActive) return
        showTextOverlay(null)
    }

    private fun onTextLayerTap(layer: DrawingView.ImageLayer) {
        if (textOverlayActive) {
            showTextOverlay(layer)
        } else if (textDialogLayer == layer) {
            showTextEditorDialog(layer)
        } else {
            showTextToolbarPopup(layer)
        }
    }

    private fun onTextLayerWarning() {
        Toast.makeText(this, "Anda berada di layer teks yang tidak bisa digunakan untuk menggambar", Toast.LENGTH_SHORT).show()
    }

    private fun setupColorPalette() {
        val dp = resources.displayMetrics.density
        val size = (24 * dp).toInt()
        val margin = (4 * dp).toInt()

        presetColors.forEachIndexed { index, color ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.setMargins(margin, 0, margin, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (index == 0) setStroke((2 * dp).toInt(), Color.WHITE)
                }
                setOnClickListener { view ->
                    if (index == activeColorIndex) {
                        showColorPickerFor(index, view)
                    } else {
                        selectColor(index)
                    }
                }
            }
            colorViews.add(v)
            binding.colorPalette.addView(v)
        }
        binding.drawingView.brushSettings.color = presetColors[0]
    }

    // ─── TOOLS & COLOR LOGIC ─────────────────────────────────────
    private fun selectColor(index: Int) {
        val dp = resources.displayMetrics.density
        colorViews.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(
                if (i == index) (3 * dp).toInt() else 0, Color.WHITE
            )
        }
        activeColorIndex = index
        binding.drawingView.brushSettings.color = presetColors[index]
    }

    private fun showColorPickerFor(index: Int, anchor: View) {
        colorPickerPopup?.dismiss()
        colorPickerPopup = ColorPickerPopup(
            context = this,
            anchorView = anchor,
            initialColor = presetColors[index],
            onColorChanged = { color ->
                presetColors[index] = color
                (colorViews[index].background as GradientDrawable).setColor(color)
                binding.drawingView.brushSettings.color = color
            },
            onDismiss = { colorPickerPopup = null }
        )
        colorPickerPopup!!.show()
    }

    private fun showColorPicker() {
        var dialog: AlertDialog? = null
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 7; setPadding(24, 24, 24, 16)
        }
        presetColors.forEach { color ->
            grid.addView(View(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 56; height = 56; setMargins(6, 6, 6, 6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(color)
                }
                setOnClickListener {
                    binding.drawingView.brushSettings.color = color
                    dialog?.dismiss()
                }
            })
        }
        dialog = AlertDialog.Builder(this).setView(grid).create()
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun onToolClick(toolType: Int, view: View) {
        if (binding.drawingView.brushSettings.toolType == toolType) {
            showSizePopup(view)
        } else {
            selectTool(toolType)
        }
    }

    private fun selectTool(toolType: Int) {
        if (textOverlayActive && toolType != DrawingView.TOOL_KEYBOARD) {
            cancelTextOverlay()
        }
        binding.drawingView.brushSettings.toolType = toolType
        val dp = resources.displayMetrics.density
        toolBtns.forEach {
            it.isSelected = false
            it.alpha = 0.35f
            it.imageTintList = null
            // Spring back to neutral position
            SpringAnimation(it, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                start()
            }
            SpringAnimation(it, DynamicAnimation.TRANSLATION_Z, 0f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_HIGH
                start()
            }
        }
        val active = when (toolType) {
            BrushSettings.TOOL_FOUNTAIN_PEN  -> binding.btnPen
            BrushSettings.TOOL_PENCIL        -> binding.btnPencil
            BrushSettings.TOOL_MARKER        -> binding.btnMarker
            BrushSettings.TOOL_INK_PEN       -> binding.btnInkPen
            BrushSettings.TOOL_ERASER        -> binding.btnEraser
            DrawingView.TOOL_BRUSH           -> binding.btnBrush
            DrawingView.TOOL_SCISSOR         -> binding.btnScissors
            DrawingView.TOOL_ROLLER          -> binding.btnRoller
            DrawingView.TOOL_KEYBOARD        -> binding.btnKeyboard
            else -> binding.btnPen
        }
        active.isSelected = true
        active.alpha = 1f
        active.imageTintList = null
        // Spring lift for active tool
        SpringAnimation(active, DynamicAnimation.TRANSLATION_Y, -(6f * dp)).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_LOW
            start()
        }
        SpringAnimation(active, DynamicAnimation.TRANSLATION_Z, 6f * dp).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
    }

    // ─── POPUPS & PANELS ─────────────────────────────────────────
    private fun showSizePopup(anchor: View) {
        val dp = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        listOf(
            Triple("Large",  SIZE_LARGE,  (28 * dp).toInt()),
            Triple("Medium", SIZE_MEDIUM, (18 * dp).toInt()),
            Triple("Small",  SIZE_SMALL,  (10 * dp).toInt())
        ).forEach { (label, size, previewSize) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                minimumWidth = (140 * dp).toInt()
                setOnClickListener {
                    binding.drawingView.brushSettings.size = size
                }
            }
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(previewSize, previewSize).also {
                    it.marginEnd = (12 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            })
            row.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
            })
            layout.addView(row)
        }

        TooltipPopup(
            ctx = this,
            content = layout,
            arrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
            backgroundColor = deepOceanBg(Color.parseColor("#2C2C2C"))
        ).showAt(anchor, 0)
    }

    private fun showShapePopup(anchor: View) {
        shapePopup?.dismiss()
        val dp = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        data class ShapeItem(val label: String, val type: DrawingView.ShapeType, val iconRes: Int)
        val shapes = listOf(
            ShapeItem("Line", DrawingView.ShapeType.LINE, R.drawable.ic_line),
            ShapeItem("Square", DrawingView.ShapeType.RECT, R.drawable.ic_square),
            ShapeItem("Circle", DrawingView.ShapeType.CIRCLE, R.drawable.ic_circle)
        )

        shapes.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (10 * dp).toInt(), (24 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener {
                    binding.drawingView.activeShape = item.type
                    binding.drawingView.brushSettings.toolType = DrawingView.TOOL_RULER
                    highlightRulerBtn()
                    shapePopup?.dismiss()
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).also {
                    it.marginEnd = (12 * dp).toInt()
                }
            })
            row.addView(TextView(this).apply {
                text = item.label; textSize = 14f; setTextColor(Color.WHITE)
            })
            layout.addView(row)
        }

        shapePopup = TooltipPopup(
            ctx = this,
            content = layout,
            arrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
            backgroundColor = deepOceanBg(Color.parseColor("#2C2C2C"))
        )
        shapePopup!!.showAt(anchor, 0)
    }

    private fun updateScissorBtnState() {
        val dv = binding.drawingView
        if (dv.hasSelection) {
            binding.btnScissors.alpha = 1f
            binding.btnScissors.imageTintList = null
        }
    }

    private fun showScissorPopup(anchor: View) {
        val dv = binding.drawingView
        selectTool(DrawingView.TOOL_SCISSOR)

        scissorPopup?.dismiss()
        val dp = resources.displayMetrics.density

        data class ScissorItem(
            val label: String, val mode: Int, val drawableRes: Int, val tint: String
        )

        val items = listOf(
            ScissorItem("Batal", DrawingView.SCISSOR_CANCEL, R.drawable.selection_slash, "#EF5350"),
            ScissorItem("Lasso", DrawingView.SCISSOR_LASSO, R.drawable.lasso, "#FFFFFF"),
            ScissorItem("Magic", DrawingView.SCISSOR_MAGIC_WAND, R.drawable.magic_wand, "#FFFFFF"),
            ScissorItem("Smart", DrawingView.SCISSOR_SMART_CUT, R.drawable.smart_cut, "#81C784"),
            ScissorItem("Invers", DrawingView.SCISSOR_INVERSE, R.drawable.selection_inverse, "#FFFFFF"),
            ScissorItem("Potong", DrawingView.SCISSOR_CUT, R.drawable.scissors, "#FFAB91"),
            ScissorItem("Salin", DrawingView.SCISSOR_COPY, R.drawable.copy, "#FFFFFF"),
            ScissorItem("Tempel", DrawingView.SCISSOR_PASTE, R.drawable.clipboard, "#FFFFFF"),
            ScissorItem("Hapus", DrawingView.SCISSOR_DELETE, R.drawable.trash, "#EF5350")
        )

        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }

        items.forEach { item ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = (66 * dp).toInt()
                    height = (66 * dp).toInt()
                    setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                }
                setOnClickListener {
                    dv.scissorMode = item.mode
                    dv.brushSettings.toolType = DrawingView.TOOL_SCISSOR
                    selectTool(DrawingView.TOOL_SCISSOR)
                    scissorPopup?.dismiss()
                    scissorPopup = null
                    if (item.mode == DrawingView.SCISSOR_CANCEL) {
                        dv.clearSelection()
                        dv.scissorMode = DrawingView.SCISSOR_NONE
                    } else if (item.mode == DrawingView.SCISSOR_CUT) {
                        dv.cutSelection()
                    } else if (item.mode == DrawingView.SCISSOR_COPY) {
                        dv.copySelection()
                        Toast.makeText(this@SketchActivity, "Tersalin ke clipboard", Toast.LENGTH_SHORT).show()
                    } else if (item.mode == DrawingView.SCISSOR_PASTE) {
                        dv.pasteSelection()
                    } else if (item.mode == DrawingView.SCISSOR_DELETE) {
                        dv.deleteSelection()
                    } else if (item.mode == DrawingView.SCISSOR_INVERSE) {
                        dv.invertSelection()
                    }
                }
            }

            cell.addView(ImageView(this).apply {
                setImageResource(item.drawableRes)
                setColorFilter(Color.parseColor(item.tint))
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            cell.addView(TextView(this).apply {
                text = item.label
                textSize = 9f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
            })
            grid.addView(cell)
        }

        scissorPopup = TooltipPopup(
            ctx = this,
            content = grid,
            arrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
            backgroundColor = deepOceanBg(Color.parseColor("#2C2C2C"))
        )
        scissorPopup!!.showAt(anchor, 0)
    }

    // ─── IMAGE SOURCE POPUP (GALLERY / STICKER) ─────────────────────
    private fun launchGalleryPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pickImageLauncher.launch("image/*")
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES), 100)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pickImageLauncher.launch("image/*")
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
    }

    private fun showImageSourcePopup(anchor: View) {
        val dp = resources.displayMetrics.density
        val items = listOf(
            Triple("Gallery", R.drawable.ic_gallery, "#FFFFFF"),
            Triple("Sticker", R.drawable.smart_select, "#FFFFFF")
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        items.forEach { (label, icon, tint) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (140 * dp).toInt(), (48 * dp).toInt()
                ).also { it.setMargins(0, (2 * dp).toInt(), 0, (2 * dp).toInt()) }
                setOnClickListener {
                    imageSourcePopup?.dismiss()
                    imageSourcePopup = null
                    when (label) {
                        "Gallery" -> launchGalleryPicker()
                        "Sticker" -> Toast.makeText(this@SketchActivity, "Sticker — segera hadir", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
                setImageResource(icon)
                setColorFilter(Color.parseColor(tint))
            }
            cell.addView(iv)
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMarginStart((8 * dp).toInt()) }
            }
            cell.addView(tv)
            container.addView(cell)
        }
        imageSourcePopup = TooltipPopup(
            ctx = this, content = container,
            arrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER,
            backgroundColor = Color.parseColor("#2C2C2C")
        )
        imageSourcePopup!!.showAt(anchor, 0)
    }

    // ─── IMAGE EDITING TOOLBAR ────────────────────────────────────
    private fun showImageEditToolbar() {
        dismissImageEditToolbar()
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density

        data class ToolItem(val label: String, val icon: Int, val mode: Int, val tint: String, val isAction: Boolean)
        val tools = listOf(
            ToolItem("Lasso", R.drawable.lasso, DrawingView.SCISSOR_LASSO, "#FFFFFF", false),
            ToolItem("Magic", R.drawable.magic_wand, DrawingView.SCISSOR_MAGIC_WAND, "#FFFFFF", false),
            ToolItem("Smart", R.drawable.smart_cut, DrawingView.SCISSOR_SMART_CUT, "#81C784", true),
            ToolItem("Hapus", R.drawable.ic_delete, -1, "#EF5350", true),
            ToolItem("Simpan", R.drawable.check, -1, "#4CAF50", true)
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
            background = getDrawable(R.drawable.bg_glass_premium)
            elevation = 6f * dp
        }

        // Title
        container.addView(TextView(this).apply {
            text = "Edit"
            textSize = 10f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * dp).toInt())
        })

        tools.forEach { item ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (52 * dp).toInt(), (52 * dp).toInt()
                )
                setOnClickListener {
                    if (item.isAction) {
                        when (item.label) {
                            "Smart" -> dv.applySmartOnEditImage()
                            "Hapus" -> {
                                dv.exitImageEditingMode()
                                dismissImageEditToolbar()
                            }
                            "Simpan" -> {
                                val bmp = dv.editingImageBitmap
                                if (bmp != null && !bmp.isRecycled) {
                                    dv.exitImageEditingMode()
                                    dismissImageEditToolbar()
                                    dv.addImageLayer(bmp)
                                    dv.isTransformMode = true
                                } else {
                                    dv.exitImageEditingMode()
                                    dismissImageEditToolbar()
                                }
                            }
                        }
                    } else {
                        dv.scissorMode = item.mode
                    }
                }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (24 * dp).toInt(), (24 * dp).toInt()
                )
                setImageResource(item.icon)
                setColorFilter(Color.parseColor(item.tint))
            }
            cell.addView(iv)
            cell.addView(TextView(this).apply {
                text = item.label
                textSize = 8f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
            })
            container.addView(cell)

            if (item != tools.last()) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                })
            }
        }

        imageEditToolbar = PopupWindow(container).apply {
            isOutsideTouchable = false
            isFocusable = false
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val x = resources.displayMetrics.widthPixels - (56 * dp).toInt() - (8 * dp).toInt()
            val y = (resources.displayMetrics.heightPixels / 2) - (140 * dp).toInt()
            showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun dismissImageEditToolbar() {
        imageEditToolbar?.dismiss()
        imageEditToolbar = null
    }

    private fun toggleLayersPopup() {
        val defaultIcon = when {
            isMidnight -> Color.WHITE
            isDeepOcean -> Color.parseColor("#BEEDE9")
            else -> Color.BLACK
        }
        val activeIcon = if (isMidnight) Color.parseColor("#1B3E98") else Color.parseColor("#3CBEC3")
        if (layersPopup?.isShowing == true) {
            layersPopup?.dismiss()
            layersPopup = null
            binding.btnLayers.setColorFilter(defaultIcon)
            return
        }
        showLayersPanel()
        binding.btnLayers.setColorFilter(activeIcon)
    }

    private fun showLayersPanel() {
        layersPopup?.dismiss()
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density
        lastLayerCount = dv.layers.size

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
            minimumWidth = (300*dp).toInt()
            background = getDrawable(R.drawable.bg_glass_premium)
            elevation = 8f * dp
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6*dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = "Layers"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams((32*dp).toInt(), (32*dp).toInt())
            setImageResource(R.drawable.ic_add)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#AAAAAA"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                dv.addEmptyLayer()
            }
        })
        container.addView(header)

        // --- RecyclerView ---
        val recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (90*dp).toInt()
            )
            layoutManager = LinearLayoutManager(
                this@SketchActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            itemAnimator = com.paperleaf.sketchbook.animation.ProcreateStyleAnimator()
            isHorizontalScrollBarEnabled = false
            setHasFixedSize(true)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                private val linePaint = Paint().apply {
                    color = Color.parseColor("#3D8EF8")
                    strokeWidth = 2f * dp
                    isAntiAlias = true
                }

                override fun getItemOffsets(
                    outRect: android.graphics.Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos == 0) {
                        outRect.right = (12 * dp).toInt()
                    }
                }

                override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                    val firstChild = parent.getChildAt(0) ?: return
                    val pos = parent.getChildAdapterPosition(firstChild)
                    if (pos != 0) return
                    val x = firstChild.right + (6 * dp)
                    val top = firstChild.top + (8 * dp)
                    val bottom = firstChild.bottom - (8 * dp)
                    c.drawLine(x, top, x, bottom, linePaint)
                }
            })
        }

        val adapter = LayerAdapter(
            density = dp,
            isDeepOcean = isDeepOcean,
            isMidnight = isMidnight,
            onEyeTap = { layerIdx ->
                val layer = dv.layers.getOrNull(layerIdx) ?: return@LayerAdapter
                dv.toggleLayerVisibility(layer)
            },
            onLockTap = { layerIdx ->
                val layer = dv.layers.getOrNull(layerIdx) ?: return@LayerAdapter
                dv.toggleLayerLock(layer)
            },
            onDuplicateTap = { layerIdx ->
                val layer = dv.layers.getOrNull(layerIdx) ?: return@LayerAdapter
                dv.duplicateLayer(layer)
            },
            onThumbTap = { layerIdx ->
                val layer = dv.layers.getOrNull(layerIdx) ?: return@LayerAdapter
                if (layer.isSelected) {
                    showLayerActionPopup(layer)
                } else {
                    dv.selectLayer(layer)
                }
            },
            onRename = { layerIdx, newName ->
                val layer = dv.layers.getOrNull(layerIdx) ?: return@LayerAdapter
                dv.renameLayer(layer, newName)
            },
            onCanvasTap = { showCanvasTemplatePopup() },
            onStartDrag = { viewHolder ->
                itemTouchHelper?.startDrag(viewHolder)
            }
        )
        adapter.canvasBitmap = dv.getBitmap()
        adapter.layers = dv.layers
        recyclerView.adapter = adapter
        layerAdapter = adapter
        layerRecyclerView = recyclerView

        // --- ItemTouchHelper ---
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or
            ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or
            ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                if (from.bindingAdapterPosition < 1 || to.bindingAdapterPosition < 1) return false
                val fromLayerIdx = from.bindingAdapterPosition - 1
                val toLayerIdx = to.bindingAdapterPosition - 1
                val layer = dv.layers.getOrNull(fromLayerIdx) ?: return false
                suppressLayersChanged = true
                adapter.moveLayerItem(from.bindingAdapterPosition, to.bindingAdapterPosition)
                dv.moveLayer(layer, toLayerIdx)
                suppressLayersChanged = false
                dv.onLayersChanged?.invoke()
                recyclerView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE
                )
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        elevation = 20f * dp
                        scaleX = 1.05f
                        scaleY = 1.05f
                        translationZ = 10f * dp
                    }
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply {
                    elevation = 0f
                    scaleX = 1f
                    scaleY = 1f
                    translationZ = 0f
                }
            }
        }
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(recyclerView)
        itemTouchHelper = touchHelper

        container.addView(recyclerView)

        val popupWidth = (320*dp).toInt()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        val popupH = container.measuredHeight

        val popup = PopupWindow(container, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = false
            isFocusable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 20f * dp
        }

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val rootLoc = IntArray(2)
        binding.root.getLocationOnScreen(rootLoc)
        val location = IntArray(2)
        binding.bottomArea.getLocationOnScreen(location)
        val toolbarTop = location[1]
        val gap = (16*dp).toInt()
        val popupY = (toolbarTop - popupH - gap).coerceAtLeast((8*dp).toInt())
        val margin = (16*dp).toInt()
        val maxRight = screenW - margin
        val xPos = ((screenW - popupWidth) / 2).coerceAtMost(maxRight - popupWidth)
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, xPos.coerceAtLeast(margin) - rootLoc[0], popupY - rootLoc[1])
        layersPopup = popup
    }

    private fun showLayerActionPopup(layer: DrawingView.ImageLayer) {
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density
        val idx = dv.layers.indexOf(layer)

        var popupRef: PopupWindow? = null

        data class Action(val label: String, val iconRes: Int, val tint: String, val enabled: Boolean, val action: () -> Unit)

        val actions = mutableListOf<Action>()

        actions.add(Action(
            if (layer.isLocked) "Unlock" else "Lock",
            if (layer.isLocked) R.drawable.unlock else R.drawable.locked,
            if (layer.isLocked) "#FFFFFF" else "#FFD54F",
            true,
            { dv.toggleLayerLock(layer) }
        ))

        actions.add(Action(
            if (layer.isMasking) "Unmask" else "Mask",
            R.drawable.masking,
            if (layer.isMasking) "#3D8EF8" else "#AAAAAA",
            idx > 0,
            {
                if (idx > 0) dv.toggleLayerMasking(layer)
                else Toast.makeText(this@SketchActivity, "Masking hanya untuk layer di atas", Toast.LENGTH_SHORT).show()
            }
        ))

        actions.add(Action(
            "Duplicate",
            R.drawable.copy,
            "#FFFFFF",
            true,
            { dv.duplicateLayer(layer) }
        ))

        actions.add(Action(
            "Merge",
            R.drawable.merge,
            "#FFFFFF",
            idx > 0,
            { dv.mergeLayerLeft(layer) }
        ))

        actions.add(Action(
            if (layer.isVisible) "Hide" else "Show",
            if (layer.isVisible) R.drawable.eye else R.drawable.eye_closed,
            if (layer.isVisible) "#FFFFFF" else "#888888",
            true,
            { dv.toggleLayerVisibility(layer) }
        ))

        actions.add(Action(
            "Rename",
            R.drawable.rename,
            "#AAAAAA",
            true,
            { showRenameDialog(layer.name) { newName -> dv.renameLayer(layer, newName); dv.onLayersChanged?.invoke() } }
        ))

        actions.add(Action(
            "Delete",
            R.drawable.trash,
            "#FF5252",
            true,
            { dv.deleteLayer(layer) }
        ))

        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
            background = getDrawable(R.drawable.bg_glass_premium)
            elevation = 8f * dp
        }

        actions.forEach { item ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = (66*dp).toInt()
                    height = (66*dp).toInt()
                    setMargins((4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt())
                }
                alpha = if (item.enabled) 1f else 0.4f
                isEnabled = item.enabled
                setOnClickListener {
                    if (item.enabled) {
                        item.action()
                        popupRef?.dismiss()
                        dv.onLayersChanged?.invoke()
                    }
                }
            }

            cell.addView(ImageView(this).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.parseColor(item.tint))
                layoutParams = LinearLayout.LayoutParams((28*dp).toInt(), (28*dp).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            cell.addView(TextView(this).apply {
                text = item.label
                textSize = 9f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
            })
            grid.addView(cell)
        }

        val popup = PopupWindow(grid, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 20f * dp
        }
        popupRef = popup
        val loc = IntArray(2)
        binding.btnLayers.getLocationOnScreen(loc)
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, loc[0], loc[1] - (250*dp).toInt())
    }

    private fun showLayerReorderMenu(layer: DrawingView.ImageLayer) {
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density
        val idx = dv.layers.indexOf(layer)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8*dp).toInt(), (4*dp).toInt(), (8*dp).toInt(), (4*dp).toInt())
            background = GradientDrawable().apply {
                setColor(deepOceanBg(Color.parseColor("#EE222222")))
                cornerRadius = 12f * dp
            }
        }
        var popupRef: PopupWindow? = null

        // Move Left (only if idx > 0)
        if (idx > 0) {
            layout.addView(createMenuRow("← Move Left", "#FFFFFF", dp, {
            dv.moveLayer(layer, idx - 1)
            popupRef?.dismiss()
            dv.onLayersChanged?.invoke()
        })) }

        // Move Right (always allowed, but Layer1 can only go right)
        layout.addView(createMenuRow("Move Right →", "#FFFFFF", dp, {
            dv.moveLayer(layer, idx + 1)
            popupRef?.dismiss()
            dv.onLayersChanged?.invoke()
        }))

        // Divider
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1*dp).toInt()
            ).also { it.setMargins((12*dp).toInt(), 0, (12*dp).toInt(), 0) }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // Duplicate
        layout.addView(createMenuRow("Duplicate", "#FFFFFF", dp, {
            dv.duplicateLayer(layer)
            popupRef?.dismiss()
            dv.onLayersChanged?.invoke()
        }))

        // Divider
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1*dp).toInt()
            ).also { it.setMargins((12*dp).toInt(), 0, (12*dp).toInt(), 0) }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // Delete
        layout.addView(createMenuRow("Delete", "#FF5252", dp, {
            dv.deleteLayer(layer)
            popupRef?.dismiss()
            dv.onLayersChanged?.invoke()
        }))

        val popup = PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 20f * dp
        }
        popupRef = popup
        val loc = IntArray(2)
        binding.btnLayers.getLocationOnScreen(loc)
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, loc[0], loc[1] - (200*dp).toInt())
    }

    private fun createMenuRow(text: String, color: String, dp: Float, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor(color))
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
            minimumWidth = (160*dp).toInt()
            setOnClickListener { onClick() }
        }
    }

    private fun showCanvasTemplatePopup() {
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density
        val thumbW = (80 * dp).toInt()
        val thumbH = (70 * dp).toInt()
        val currentTemplateType = dv.pageTemplate

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
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
            setPadding((12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
        }

        for (type in com.paperleaf.sketchbook.model.Page.ALL_TEMPLATES) {
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
                    cornerRadius = 10f * dp
                }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val pad = (8 * dp).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(thumbW + pad*2, thumbH + pad*2, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            cv.drawColor(Color.parseColor("#333333"))
            cv.save()
            cv.clipPath(android.graphics.Path().apply {
                val r = android.graphics.RectF(pad.toFloat(), pad.toFloat(), (thumbW+pad).toFloat(), (thumbH+pad).toFloat())
                addRoundRect(r, 10f*dp, 10f*dp, android.graphics.Path.Direction.CW)
            })
            cv.translate(pad.toFloat(), pad.toFloat())
            com.paperleaf.sketchbook.template.PaperTemplateEngine.drawThumbnail(cv, thumbW.toFloat(), thumbH.toFloat(), type)
            cv.restore()
            thumbIv.setImageBitmap(bmp)
            card.addView(thumbIv)

            card.addView(TextView(this).apply {
                text = com.paperleaf.sketchbook.model.Page.templateName(type)
                textSize = 10f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })

            val isSelected = currentTemplateType == type
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
                dv.pageTemplate = type
                Toast.makeText(this@SketchActivity,
                    "Template: ${com.paperleaf.sketchbook.model.Page.templateName(type)}", Toast.LENGTH_SHORT).show()
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
            )
            background = getDrawable(R.drawable.bg_glass_premium)
            addView(container)
        }

        root.addView(bgCard)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
    }

    private fun showCanvasTexturePopup() {
        val dv = binding.drawingView
        val dp = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8*dp).toInt(), (4*dp).toInt(), (8*dp).toInt(), (4*dp).toInt())
            background = GradientDrawable().apply {
                setColor(deepOceanBg(Color.parseColor("#EE222222")))
                cornerRadius = 12f * dp
            }
        }

        val textures = listOf(
            "Plain" to "#FAF9F0",
            "Textured" to "#F5F0E8",
            "Watercolor" to "#F0F5FA",
            "Canvas" to "#EDE8E0"
        )

        var popupRef: PopupWindow? = null

        textures.forEachIndexed { index, (name, _) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (24*dp).toInt(), (10*dp).toInt())
                minimumWidth = (160*dp).toInt()
                setOnClickListener {
                    dv.backgroundTexture = index
                    popupRef?.dismiss()
                }
            }
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams((20*dp).toInt(), (20*dp).toInt()).also {
                    it.marginEnd = (12*dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#555555"))
                    if (index == dv.backgroundTexture) {
                        setStroke((3*dp).toInt(), Color.parseColor("#3D8EF8"))
                    }
                }
            })
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(if (index == dv.backgroundTexture) Color.WHITE else Color.parseColor("#AAAAAA"))
            })
            layout.addView(row)
            if (index < textures.size - 1) {
                layout.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1*dp).toInt()
                    ).also { it.setMargins((12*dp).toInt(), 0, (12*dp).toInt(), 0) }
                    setBackgroundColor(Color.parseColor("#33FFFFFF"))
                })
            }
        }

        val popup = PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 20f * dp
        }
        popupRef = popup
        val loc = IntArray(2)
        binding.btnLayers.getLocationOnScreen(loc)
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, loc[0], loc[1] - (250*dp).toInt())
    }

    private fun showRenameDialog(currentName: String, onRename: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_glass_premium)
            elevation = 12f
            layoutParams = FrameLayout.LayoutParams(
                (280*dp).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
            setOnClickListener {}
        }
        card.addView(TextView(this).apply {
            text = "Rename Layer"
            textSize = 15f
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12*dp).toInt())
        })
        val input = EditText(this).apply {
            setText(currentName)
            selectAll()
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(deepOceanBg(Color.parseColor("#2C2C2C")))
                cornerRadius = 8f * dp
            }
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
        }
        card.addView(input)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (44*dp).toInt()
            ).also { it.topMargin = (16*dp).toInt() }
            addView(TextView(this@SketchActivity).apply {
                text = "Cancel"
                textSize = 14f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })
            addView(TextView(this@SketchActivity).apply {
                text = "OK"
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) onRename(newName)
                    dialog.dismiss()
                }
            })
        })
        root.addView(card)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        TransitionHelper.morphDialog(dialog)
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ─── ACTIONS & STATE MANAGEMENT ──────────────────────────────
    private fun showTouchEffect(btn: ImageButton) {
        val highlight = if (isMidnight) Color.parseColor("#1B3E98") else Color.parseColor("#3CBEC3")
        val restore = if (isMidnight) Color.WHITE else Color.parseColor("#12415E")
        btn.setColorFilter(highlight)
        btn.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()
        btn.postDelayed({
            btn.setColorFilter(restore)
        }, 200)
    }

    private fun highlightRulerBtn() {
        val dp = resources.displayMetrics.density
        toolBtns.forEach {
            it.isSelected = false; it.alpha = 0.35f
            it.imageTintList = null
            SpringAnimation(it, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                start()
            }
            SpringAnimation(it, DynamicAnimation.TRANSLATION_Z, 0f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_HIGH
                start()
            }
        }
        binding.btnRuler.isSelected = true; binding.btnRuler.alpha = 1f
        binding.btnRuler.imageTintList = null
        SpringAnimation(binding.btnRuler, DynamicAnimation.TRANSLATION_Y, -(6f * dp)).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_LOW
            start()
        }
        SpringAnimation(binding.btnRuler, DynamicAnimation.TRANSLATION_Z, 6f * dp).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
    }

    private fun toggleTransformMode() {
        val active = !binding.drawingView.isTransformMode
        binding.drawingView.isTransformMode = active
        val defaultIcon = when {
            isMidnight -> Color.WHITE
            isDeepOcean -> Color.parseColor("#BEEDE9")
            else -> ThemeManager.getThemeColors().toolbarIcon
        }
        val moveColor = when {
            active && isMidnight -> Color.parseColor("#1B3E98")
            active -> Color.parseColor("#3CBEC3")
            else -> defaultIcon
        }
        binding.btnMove.setColorFilter(moveColor)
    }

    // ─── SYSTEM BACK ──────────────────────────────────────────────
    private fun setupSystemBack() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawingView.isImageEditingMode) {
                    binding.drawingView.exitImageEditingMode()
                    dismissImageEditToolbar()
                } else if (textOverlayActive) {
                    cancelTextOverlay()
                } else if (layersPopup?.isShowing == true) {
                    layersPopup?.dismiss()
                    layersPopup = null
                } else if (!isSaving) {
                    performExitSave()
                }
            }
        })
    }

    // ─── PAGE I/O ────────────────────────────────────────────────
    private fun loadPage() {
        LoadingOverlay.show(this)
        lifecycleScope.launch {
            val dv = binding.drawingView
            dv.isTransformMode = false

            val layersRestored = dv.loadLayers(bookId, spreadIndex)
            if (!layersRestored) {
                val bmp = withContext(Dispatchers.IO) {
                    FileUtils.loadBitmap(FileUtils.getSpreadFile(this@SketchActivity, bookId, spreadIndex))
                }
                bmp?.let { dv.createDefaultLayer(it) }
            }
            dv.onLayersChanged?.invoke()

            val page = withContext(Dispatchers.IO) {
                db.pageDao().getPage(bookId, pageNumber)
            }
            if (page != null) {
                currentPage = page
                dv.pageTemplate = page.templateType
            }
            LoadingOverlay.hide(this@SketchActivity)
        }
    }

    private fun savePage() {
        val dv = binding.drawingView
        dv.flattenLayersForSave()
        val bmp = dv.getBitmap() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            FileUtils.saveBitmap(bmp, FileUtils.getSpreadFile(this@SketchActivity, bookId, spreadIndex))
        }
    }

    private fun performExitSave() {
        isSaving = true
        LoadingOverlay.show(this@SketchActivity, "Menyimpan...")
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                val dv = binding.drawingView

                LoadingOverlay.setText(this@SketchActivity, "Meratakan layer...")
                withContext(Dispatchers.Main) {
                    dv.flattenLayersForSave()
                }
                val bmp = withContext(Dispatchers.Main) { dv.getBitmap() }

                LoadingOverlay.setText(this@SketchActivity, "Menyimpan layer...")
                dv.saveLayers(bookId, spreadIndex)

                if (bmp != null) {
                    LoadingOverlay.setText(this@SketchActivity, "Menyimpan pratinjau...")
                    FileUtils.saveBitmap(bmp, FileUtils.getSpreadFile(this@SketchActivity, bookId, spreadIndex))
                }

                LoadingOverlay.setText(this@SketchActivity, "Menyimpan template...")
                currentPage?.let { page ->
                    db.pageDao().update(page.copy(templateType = dv.pageTemplate))
                }

                LoadingOverlay.setText(this@SketchActivity, "Membersihkan memori...")
                withContext(Dispatchers.Main) {
                    dv.clearUndoRedo()
                    dv.invalidate()
                }

                withContext(Dispatchers.Main) {
                    isSaving = false
                    LoadingOverlay.hide(this@SketchActivity)
                    finish()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    LoadingOverlay.hide(this@SketchActivity)
                    finish()
                }
            }
        }
    }

    private fun saveTemplate() {
        val page = currentPage ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.pageDao().update(page.copy(templateType = binding.drawingView.pageTemplate))
        }
    }

    // ─── UTILS ───────────────────────────────────────────────────
    private fun showPendingHint() {
        Toast.makeText(this, "Ketuk canvas untuk menempatkan foto", Toast.LENGTH_SHORT).show()
    }

    private fun hideSystemUI() {
        // Request fullscreen with edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Hide system bars and keep them hidden on interaction
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Ensure app uses full screen including under notches/cutouts
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = 
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
    }

    private fun loadSavedPalette() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val colorStr = prefs.getString(KEY_COLORS, null)
        if (colorStr != null) {
            try {
                colorStr.split(",").forEach { hex ->
                    val color = hex.trim().toLong(16).toInt()
                    presetColors.add(color)
                }
            } catch (_: Exception) { presetColors.clear() }
        }
        if (presetColors.isEmpty()) {
            presetColors.addAll(listOf(
                0xFF1A1A1A.toInt(), 0xFFE53935.toInt(), 0xFFFDD835.toInt(),
                0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
                0xFFFF8F00.toInt()
            ))
        }
    }

    private fun savePalette() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val colorStr = presetColors.joinToString(",") { it.toLong().toString(16) }
        prefs.edit().putString(KEY_COLORS, colorStr).apply()
    }
}
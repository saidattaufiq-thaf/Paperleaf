package com.paperleaf.sketchbook.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.asset.AssetCategory
import com.paperleaf.sketchbook.asset.AssetData
import com.paperleaf.sketchbook.asset.AssetManager
import com.paperleaf.sketchbook.theme.ThemeConfig
import com.paperleaf.sketchbook.theme.ThemeManager
import com.paperleaf.sketchbook.utils.TransitionHelper

class PaperStoreActivity : AppCompatActivity(), ThemeManager.OnThemeChangeListener {

    private val categories = listOf("Themes", "Covers", "Templates", "Textures", "Fonts", "Brushes", "Stickers")
    private val assetCategories = AssetCategory.values().toList()
    private var selectedCategoryIndex = 0

    private var rvContent: RecyclerView? = null
    private var categoryContainer: LinearLayout? = null
    private var emptyState: View? = null
    private var tvEmptyTitle: TextView? = null
    private var tvEmptySubtitle: TextView? = null

    private var themeAdapter: ThemeGridAdapter? = null
    private var assetAdapter: AssetGridAdapter? = null

    private val isDeepOcean: Boolean get() = ThemeManager.current.id == "deep_ocean"

    override fun finish() {
        super.finish()
        TransitionHelper.morphFinish(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(this)
    }

    override fun onThemeChanged(theme: ThemeConfig) {
        runOnUiThread { applyThemeColors() }
    }

    private fun applyThemeColors() {
        loadOceanBackground()
        if (isDeepOcean) {
            applyDeepOceanTheme()
        }
    }

    private fun loadOceanBackground() {
        val root = findViewById<View>(android.R.id.content).rootView as? ViewGroup
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
            root?.background = BitmapDrawable(resources, cropped)
        } catch (_: Exception) {}
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
        findViewById<TextView>(R.id.tvHeader)?.setTextColor(Color.parseColor("#11415D"))
        findViewById<ImageView>(R.id.btnBack)?.setColorFilter(Color.parseColor("#11415D"))

        rebuildCategoryTabs()
    }

    private fun rebuildCategoryTabs() {
        val dp = resources.displayMetrics.density
        categoryContainer?.removeAllViews()

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        val textColor = Color.parseColor("#A9E5E4")
        val inacColor = Color.parseColor("#A9E5E4")
        val selBg = Color.parseColor("#11415D")

        categories.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(if (index == selectedCategoryIndex) textColor else inacColor)
                typeface = android.graphics.Typeface.create(
                    if (index == selectedCategoryIndex) "sans-serif-medium" else "sans-serif",
                    android.graphics.Typeface.NORMAL
                )
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 20f * dp
                    if (index == selectedCategoryIndex) {
                        setColor(selBg)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                setOnClickListener {
                    selectedCategoryIndex = index
                    rebuildCategoryTabs()
                    loadContentForCategory(index)
                }
            }
            tabs.addView(tab)
        }

        scroll.addView(tabs)
        categoryContainer?.addView(scroll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paper_store)

        ThemeManager.init(this)
        ThemeManager.addListener(this)
        AssetManager.init(this)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val header = findViewById<TextView>(R.id.tvHeader)
        emptyState = findViewById<View>(R.id.emptyState)
        tvEmptyTitle = findViewById<TextView>(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById<TextView>(R.id.tvEmptySubtitle)
        rvContent = findViewById<RecyclerView>(R.id.rvThemes)
        categoryContainer = findViewById<LinearLayout>(R.id.categoryTabs)

        btnBack.setOnClickListener { finish() }
        header.text = "Paper Store"

        applyThemeColors()

        rvContent?.layoutManager = GridLayoutManager(this, 2)

        themeAdapter = ThemeGridAdapter { theme ->
            if (ThemeManager.isThemeOwned(theme)) {
                ThemeManager.applyTheme(this, theme.id)
                Toast.makeText(this, "Applied: ${theme.name}", Toast.LENGTH_SHORT).show()
                themeAdapter?.notifyDataSetChanged()
            } else {
                Toast.makeText(this, "Premium feature coming soon", Toast.LENGTH_SHORT).show()
            }
        }

        assetAdapter = AssetGridAdapter { asset ->
            Toast.makeText(this, "${asset.name} selected", Toast.LENGTH_SHORT).show()
        }

        buildCategoryTabs()
        loadContentForCategory(0)
    }

    private fun buildCategoryTabs() {
        val dp = resources.displayMetrics.density
        categoryContainer?.removeAllViews()

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        categories.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(Color.parseColor("#A9E5E4"))
                typeface = android.graphics.Typeface.create(
                    if (index == 0) "sans-serif-medium" else "sans-serif",
                    android.graphics.Typeface.NORMAL
                )
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 20f * dp
                    if (index == 0) {
                        setColor(Color.parseColor("#11415D"))
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                setOnClickListener {
                    selectedCategoryIndex = index
                    updateTabSelection()
                    loadContentForCategory(index)
                }
            }
            tabs.addView(tab)
        }

        scroll.addView(tabs)
        categoryContainer?.addView(scroll)
    }

    private fun updateTabSelection() {
        val tabs = (categoryContainer?.getChildAt(0) as? LinearLayout)
            ?.getChildAt(0) as? LinearLayout ?: return

        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            val isSelected = i == selectedCategoryIndex
            tab.setTextColor(Color.parseColor("#A9E5E4"))
            tab.typeface = android.graphics.Typeface.create(
                if (isSelected) "sans-serif-medium" else "sans-serif",
                android.graphics.Typeface.NORMAL
            )
            (tab.background as GradientDrawable).setColor(
                if (isSelected) Color.parseColor("#11415D") else Color.TRANSPARENT
            )
        }
    }

    private fun loadContentForCategory(index: Int) {
        if (index == 0) {
            val themes = ThemeManager.loadAllThemes(this)
            rvContent?.adapter = themeAdapter
            themeAdapter?.submitList(themes)

            if (themes.isEmpty()) {
                rvContent?.visibility = View.GONE
                emptyState?.visibility = View.VISIBLE
                tvEmptyTitle?.text = "No Themes Available"
                tvEmptySubtitle?.text = "Check back later for new themes"
            } else {
                rvContent?.visibility = View.VISIBLE
                emptyState?.visibility = View.GONE
            }
        } else {
            val category = assetCategories[index - 1]
            val assetList = AssetManager.getAssetsByCategory(category)
            rvContent?.adapter = assetAdapter
            assetAdapter?.submitList(assetList)

            if (assetList.isEmpty()) {
                rvContent?.visibility = View.GONE
                emptyState?.visibility = View.VISIBLE
                tvEmptyTitle?.text = "No Assets Installed"
                tvEmptySubtitle?.text = "Place assets in Assets/${category.toFolderName()}/"
            } else {
                rvContent?.visibility = View.VISIBLE
                emptyState?.visibility = View.GONE
            }
        }
    }

    // ─── Theme Adapter (from ThemeManager) ────────────────────────

    class ThemeGridAdapter(
        private val onActionClick: (ThemeConfig) -> Unit
    ) : RecyclerView.Adapter<ThemeGridAdapter.ThemeVH>() {

        private var items = listOf<ThemeConfig>()

        fun submitList(list: List<ThemeConfig>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ThemeVH(val root: FrameLayout) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeVH {
            val dp = parent.context.resources.displayMetrics.density
            val cardW = (parent.width / 2 - 16 * dp).toInt().coerceAtLeast((150 * dp).toInt())
            val cardH = (180 * dp).toInt()

            val card = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(cardW, cardH).apply {
                    setMargins((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2B2E"))
                    cornerRadius = 16f * dp
                }
                elevation = 4f * dp
            }
            return ThemeVH(card)
        }

        override fun onBindViewHolder(holder: ThemeVH, position: Int) {
            val theme = items[position]
            val ctx = holder.root.context
            val dp = ctx.resources.displayMetrics.density
            val card = holder.root
            card.removeAllViews()

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            }

            val previewContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (80 * dp).toInt()
                ).apply { bottomMargin = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    setColor(theme.colors.background)
                    cornerRadius = 10f * dp
                }
                clipToOutline = true
            }

            try {
                val inputStream = ctx.assets.open(theme.previewPath)
                val previewBmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (previewBmp != null) {
                    val previewIv = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(previewBmp)
                        clipToOutline = true
                    }
                    previewContainer.addView(previewIv)
                }
            } catch (_: Exception) {
            }

            val accentBar = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (4 * dp).toInt(), (40 * dp).toInt()
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, (6 * dp).toInt(), (6 * dp).toInt())
                }
            }
            (accentBar.background as? GradientDrawable)?.let {
                it.setColor(theme.colors.accent)
            } ?: run {
                accentBar.background = GradientDrawable().apply {
                    cornerRadius = 2f * dp
                    setColor(theme.colors.accent)
                }
            }
            previewContainer.addView(accentBar)

            content.addView(previewContainer)

            val name = TextView(ctx).apply {
                text = theme.name
                textSize = 13f
                setTextColor(Color.parseColor("#EEEEEE"))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(name)

            val desc = TextView(ctx).apply {
                text = theme.description
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
            }
            content.addView(desc)

            val bottomRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val isPremium = ThemeManager.isThemePremium(theme)
            val badge = TextView(ctx).apply {
                text = if (isPremium) "PREMIUM" else "FREE"
                textSize = 9f
                setTextColor(
                    if (isPremium) Color.parseColor("#FFD95A") else Color.parseColor("#4CAF50")
                )
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 8f * dp
                    setColor(
                        if (isPremium) Color.parseColor("#3A2E0A") else Color.parseColor("#1B3A1B")
                    )
                }
            }
            bottomRow.addView(badge)

            val isSelected = ThemeManager.getSelectedThemeId() == theme.id
            val isOwned = ThemeManager.isThemeOwned(theme)
            val actionBtn = TextView(ctx).apply {
                text = if (isSelected) "APPLIED" else if (isOwned) "APPLY" else "GET"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (6 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8f * dp
                    if (isSelected) {
                        setColor(Color.parseColor("#1B3A1B"))
                    } else if (isOwned) {
                        setColor(theme.colors.buttonPrimary)
                    } else {
                        setColor(Color.parseColor("#2A2A2A"))
                        setStroke((1 * dp).toInt(), Color.parseColor("#444444"))
                    }
                }
                setTextColor(
                    if (isSelected) Color.parseColor("#4CAF50")
                    else if (isOwned) theme.colors.buttonPrimaryText
                    else Color.parseColor("#888888")
                )
            }
            bottomRow.addView(actionBtn)

            content.addView(bottomRow)
            card.addView(content)

            card.setOnClickListener {
                if (isOwned || isSelected) {
                    onActionClick(theme)
                } else {
                    Toast.makeText(ctx, "Premium feature coming soon", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    // ─── Asset Adapter (from AssetManager) ────────────────────────

    class AssetGridAdapter(
        private val onItemClick: (AssetData) -> Unit
    ) : RecyclerView.Adapter<AssetGridAdapter.AssetVH>() {

        private var items = listOf<AssetData>()

        fun submitList(list: List<AssetData>) {
            items = list
            notifyDataSetChanged()
        }

        inner class AssetVH(val root: FrameLayout) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetVH {
            val dp = parent.context.resources.displayMetrics.density
            val cardW = (parent.width / 2 - 16 * dp).toInt().coerceAtLeast((150 * dp).toInt())
            val cardH = (180 * dp).toInt()

            val card = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(cardW, cardH).apply {
                    setMargins((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2B2E"))
                    cornerRadius = 16f * dp
                }
                elevation = 4f * dp
            }
            return AssetVH(card)
        }

        override fun onBindViewHolder(holder: AssetVH, position: Int) {
            val asset = items[position]
            val ctx = holder.root.context
            val dp = ctx.resources.displayMetrics.density
            val card = holder.root
            card.removeAllViews()

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            }

            val previewContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (80 * dp).toInt()
                ).apply { bottomMargin = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3A3B3E"))
                    cornerRadius = 10f * dp
                }
                clipToOutline = true
            }

            if (asset.thumbnail.isNotEmpty()) {
                try {
                    val bmp = BitmapFactory.decodeFile(asset.thumbnail)
                    if (bmp != null) {
                        val iv = ImageView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(bmp)
                            clipToOutline = true
                        }
                        previewContainer.addView(iv)
                    }
                } catch (_: Exception) {
                }
            }

            val placeholder = TextView(ctx).apply {
                text = asset.name.take(2).uppercase()
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#666666"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            previewContainer.addView(placeholder)

            content.addView(previewContainer)

            val name = TextView(ctx).apply {
                text = asset.name
                textSize = 13f
                setTextColor(Color.parseColor("#EEEEEE"))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(name)

            val authorLine = TextView(ctx).apply {
                text = asset.author
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
            }
            content.addView(authorLine)

            val bottomRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val badge = TextView(ctx).apply {
                text = "v${asset.version}"
                textSize = 9f
                setTextColor(Color.parseColor("#4CAF50"))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 8f * dp
                    setColor(Color.parseColor("#1B3A1B"))
                }
            }
            bottomRow.addView(badge)

            val statusBtn = TextView(ctx).apply {
                text = if (asset.enabled) "Enabled" else "Disabled"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (6 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8f * dp
                    if (asset.enabled) {
                        setColor(Color.parseColor("#2D5BFF"))
                    } else {
                        setColor(Color.parseColor("#2A2A2A"))
                        setStroke((1 * dp).toInt(), Color.parseColor("#444444"))
                    }
                }
                setTextColor(
                    if (asset.enabled) Color.WHITE else Color.parseColor("#888888")
                )
            }
            bottomRow.addView(statusBtn)

            content.addView(bottomRow)
            card.addView(content)

            card.setOnClickListener { onItemClick(asset) }
        }

        override fun getItemCount() = items.size
    }
}

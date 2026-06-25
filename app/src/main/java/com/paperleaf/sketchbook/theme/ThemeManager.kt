package com.paperleaf.sketchbook.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONObject

object ThemeManager {

    private const val DEV_MODE = true
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_SELECTED_THEME = "selected_theme_id"
    private const val KEY_OWNED_PREFIX = "theme_owned_"
    private const val KEY_DEV_UNLOCK = "dev_unlock_premium"

    private var _currentTheme: ThemeConfig? = null
    private val listeners = mutableListOf<OnThemeChangeListener>()
    private lateinit var prefs: SharedPreferences

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_SELECTED_THEME, "ios_light")
        loadTheme(context, savedId ?: "ios_light")
        initialized = true
    }

    fun purchaseTheme(@Suppress("UNUSED_PARAMETER") context: Context, themeId: String): Boolean {
        prefs.edit().putBoolean("$KEY_OWNED_PREFIX$themeId", true).apply()
        return true
    }

    interface OnThemeChangeListener {
        fun onThemeChanged(theme: ThemeConfig)
    }

    fun addListener(listener: OnThemeChangeListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: OnThemeChangeListener) {
        listeners.remove(listener)
    }

    val current: ThemeConfig
        get() = _currentTheme ?: loadFallbackTheme()

    fun getThemeColors(): ThemeColors = current.colors

    fun loadAllThemes(context: Context): List<ThemeConfig> {
        val themes = mutableListOf<ThemeConfig>()
        try {
            val assetManager = context.assets
            val themeDirs = assetManager.list("themes") ?: return themes
            for (dir in themeDirs) {
                try {
                    val jsonStr = assetManager.open("themes/$dir/theme.json")
                        .bufferedReader().use { it.readText() }
                    val theme = parseTheme(jsonStr)
                    theme.previewPath = "themes/$dir/preview.webp"
                    themes.add(theme)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return themes.sortedBy { it.name }
    }

    fun applyTheme(context: Context, themeId: String) {
        loadTheme(context, themeId)
        prefs.edit().putString(KEY_SELECTED_THEME, themeId).apply()
        _currentTheme?.let { theme ->
            listeners.forEach { it.onThemeChanged(theme) }
        }
    }

    fun getSelectedThemeId(): String {
        return prefs.getString(KEY_SELECTED_THEME, "ios_light") ?: "ios_light"
    }

    fun isDevModeEnabled(): Boolean = DEV_MODE

    fun setDevUnlockAll(unlock: Boolean) {
        prefs.edit().putBoolean(KEY_DEV_UNLOCK, unlock).apply()
    }

    fun isDevUnlockAll(): Boolean {
        if (!DEV_MODE) return false
        return prefs.getBoolean(KEY_DEV_UNLOCK, false)
    }

    fun isThemeOwned(theme: ThemeConfig): Boolean {
        if (theme.category == "free") return true
        if (isDevUnlockAll()) return true
        return prefs.getBoolean("$KEY_OWNED_PREFIX${theme.id}", false)
    }

    fun isThemePremium(theme: ThemeConfig): Boolean =
        theme.category == "premium"

    // ─── WCAG AA Contrast Utilities ───────────────────────────────

    private fun relativeLuminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        fun linearize(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    private fun contrastRatio(c1: Int, c2: Int): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun ensureContrast(textColor: Int, bgColor: Int): Int {
        if (contrastRatio(textColor, bgColor) >= 4.5) return textColor
        val whiteCR = contrastRatio(Color.WHITE, bgColor)
        val blackCR = contrastRatio(Color.BLACK, bgColor)
        return if (whiteCR >= blackCR) Color.WHITE else Color.BLACK
    }

    private fun addOpacity(color: Int, alphaFraction: Float): Int {
        val alpha = (alphaFraction * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ─── Load / Parse ─────────────────────────────────────────────

    private fun loadTheme(context: Context, themeId: String) {
        try {
            val jsonStr = context.assets.open("themes/$themeId/theme.json")
                .bufferedReader().use { it.readText() }
            val theme = parseTheme(jsonStr)
            theme.previewPath = "themes/$themeId/preview.webp"
            _currentTheme = theme
        } catch (e: Exception) {
            _currentTheme = loadFallbackTheme()
        }
    }

    private fun loadFallbackTheme(): ThemeConfig {
        val bg = Color.parseColor("#F2F2F7")
        val surface = Color.parseColor("#FFFFFF")
        val surfaceVariant = Color.parseColor("#E5E5EA")
        val primary = Color.parseColor("#007AFF")
        val accent = Color.parseColor("#5AC8FA")
        val textPrimary = Color.parseColor("#1C1C1E")
        val textSecondary = Color.parseColor("#8E8E93")
        return ThemeConfig(
            id = "ios_light",
            name = "iOS Light",
            category = "free",
            description = "iOS-style light interface",
            colors = deriveColors(bg, surface, surfaceVariant, primary, accent, textPrimary, textSecondary)
        )
    }

    private fun parseTheme(jsonStr: String): ThemeConfig {
        val root = JSONObject(jsonStr)
        val colorsObj = root.getJSONObject("colors")

        val bg = colorsObj.getColor("background")
        val surface = colorsObj.getColor("surface")
        val surfaceVariant = colorsObj.getColor("surfaceVariant")
        val primary = colorsObj.getColor("primary")
        val accent = colorsObj.getColor("accent")
        val textPrimary = colorsObj.getColor("textPrimary")
        val textSecondary = colorsObj.getColor("textSecondary")

        val colors = deriveColors(bg, surface, surfaceVariant, primary, accent, textPrimary, textSecondary)

        return ThemeConfig(
            id = root.getString("id"),
            name = root.getString("name"),
            category = root.optString("category", "free"),
            description = root.optString("description", ""),
            colors = colors
        )
    }

    private fun deriveColors(
        bg: Int, surface: Int, surfaceVariant: Int,
        primary: Int, accent: Int,
        textPrimary: Int, textSecondary: Int
    ): ThemeColors {
        val iconOnSurfaceVariant = ensureContrast(textPrimary, surfaceVariant)
        val textOnPrimary = ensureContrast(textPrimary, primary)
        val textOnSurface = ensureContrast(textPrimary, surface)
        val textOnBg = ensureContrast(textPrimary, bg)

        return ThemeColors(
            background = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            primary = primary,
            accent = accent,
            textPrimary = textOnBg,
            textSecondary = ensureContrast(textSecondary, bg),
            toolbar = surfaceVariant,
            toolbarIcon = iconOnSurfaceVariant,
            buttonPrimary = primary,
            buttonPrimaryText = textOnPrimary,
            buttonSecondary = surface,
            buttonSecondaryText = textOnSurface,
            cardBackground = surface,
            dialogBackground = surface,
            bottomSheet = surface,
            navigationBar = surfaceVariant,
            navigationIcon = iconOnSurfaceVariant,
            border = addOpacity(textOnBg, 0.12f),
            divider = addOpacity(textOnBg, 0.08f),
            shadow = addOpacity(textOnBg, 0.10f),
            selection = accent
        )
    }

    private fun JSONObject.getColor(key: String): Int {
        return try {
            Color.parseColor(getString(key))
        } catch (_: Exception) {
            Color.BLACK
        }
    }
}

data class ThemeConfig(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val colors: ThemeColors,
    var previewPath: String = ""
)

data class ThemeColors(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val primary: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val toolbar: Int,
    val toolbarIcon: Int,
    val buttonPrimary: Int,
    val buttonPrimaryText: Int,
    val buttonSecondary: Int,
    val buttonSecondaryText: Int,
    val cardBackground: Int,
    val dialogBackground: Int,
    val bottomSheet: Int,
    val navigationBar: Int,
    val navigationIcon: Int,
    val border: Int,
    val divider: Int,
    val shadow: Int,
    val selection: Int
)

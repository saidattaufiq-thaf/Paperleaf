package com.paperleaf.sketchbook.asset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.paperleaf.sketchbook.model.Page
import com.paperleaf.sketchbook.template.PaperTemplateEngine
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AssetManager {

    private val assets = mutableListOf<AssetData>()
    private var scanned = false

    private data class TemplateDef(val id: String, val name: String, val type: Int)

    private val TEMPLATES = listOf(
        TemplateDef("blank", "Blank", Page.TEMPLATE_BLANK),
        TemplateDef("lined", "Lined", Page.TEMPLATE_LINED),
        TemplateDef("grid", "Grid", Page.TEMPLATE_GRID),
        TemplateDef("dot_grid", "Dot Grid", Page.TEMPLATE_DOTTED),
        TemplateDef("isometric", "Isometric", Page.TEMPLATE_ISOMETRIC),
        TemplateDef("music_sheet", "Music Sheet", Page.TEMPLATE_MUSIC_SHEET),
        TemplateDef("cornell", "Cornell Notes", Page.TEMPLATE_CORNELL),
        TemplateDef("weekly", "Weekly Planner", Page.TEMPLATE_WEEKLY),
        TemplateDef("monthly", "Monthly Planner", Page.TEMPLATE_MONTHLY),
        TemplateDef("storyboard", "Storyboard", Page.TEMPLATE_STORYBOARD),
        TemplateDef("comic", "Comic Layout", Page.TEMPLATE_COMIC),
    )

    fun init(context: Context) {
        createAssetDirectories(context)
        seedTemplates(context)
        scanAssets(context)
    }

    private fun getAssetsDir(context: Context): File =
        File(context.filesDir, "Assets")

    private fun createAssetDirectories(context: Context) {
        val base = getAssetsDir(context)
        base.mkdirs()
        for (cat in AssetCategory.values()) {
            File(base, cat.toFolderName()).mkdirs()
        }
    }

    private fun seedTemplates(context: Context) {
        val templatesDir = File(getAssetsDir(context), AssetCategory.TEMPLATE.toFolderName())
        val existing = templatesDir.listFiles()?.filter { it.isDirectory && File(it, "config.json").exists() } ?: emptyList()
        if (existing.size == TEMPLATES.size) return

        for (template in TEMPLATES) {
            val folder = File(templatesDir, template.id)
            folder.mkdirs()

            val configFile = File(folder, "config.json")
            if (!configFile.exists()) {
                val config = JSONObject().apply {
                    put("id", template.id)
                    put("name", template.name)
                    put("category", AssetCategory.TEMPLATE.name)
                    put("version", 1)
                    put("author", "PaperLeaf")
                }
                configFile.writeText(config.toString(2))
            }

            val thumbFile = File(folder, "thumbnail.png")
            if (!thumbFile.exists()) {
                try {
                    val bmp = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    PaperTemplateEngine.drawThumbnail(canvas, 200f, 120f, template.type)
                    FileOutputStream(thumbFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    bmp.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun scanAssets(context: Context) {
        assets.clear()
        val base = getAssetsDir(context)
        if (!base.exists()) return

        for (catDir in base.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val category = AssetCategory.fromFolderName(catDir.name) ?: continue
            for (assetFolder in catDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val configFile = File(assetFolder, "config.json")
                if (!configFile.exists()) continue

                try {
                    val jsonStr = configFile.readText()
                    val json = JSONObject(jsonStr)
                    val asset = AssetData(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        category = AssetCategory.fromJsonValue(json.optString("category", ""))
                            ?: category,
                        version = json.optInt("version", 1),
                        author = json.optString("author", "PaperLeaf"),
                        thumbnail = if (File(assetFolder, "thumbnail.png").exists())
                            File(assetFolder, "thumbnail.png").absolutePath else "",
                        preview = if (File(assetFolder, "preview.png").exists())
                            File(assetFolder, "preview.png").absolutePath else "",
                        installed = true,
                        enabled = true
                    )
                    assets.add(asset)
                } catch (_: Exception) {
                }
            }
        }
        scanned = true
    }

    fun getAllAssets(): List<AssetData> = assets.toList()

    fun getAssetsByCategory(category: AssetCategory): List<AssetData> =
        assets.filter { it.category == category }

    fun getInstalledAssets(): List<AssetData> =
        assets.filter { it.installed }

    fun enableAsset(assetId: String): Boolean {
        val asset = assets.find { it.id == assetId } ?: return false
        asset.enabled = true
        return true
    }

    fun disableAsset(assetId: String): Boolean {
        val asset = assets.find { it.id == assetId } ?: return false
        asset.enabled = false
        return true
    }

    fun isAssetInstalled(assetId: String): Boolean =
        assets.any { it.id == assetId && it.installed }

    fun getAssetById(assetId: String): AssetData? =
        assets.find { it.id == assetId }

    fun hasScanned(): Boolean = scanned
}

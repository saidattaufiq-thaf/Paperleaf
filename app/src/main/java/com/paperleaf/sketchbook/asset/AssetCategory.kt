package com.paperleaf.sketchbook.asset

enum class AssetCategory {
    THEME,
    COVER,
    TEMPLATE,
    TEXTURE,
    FONT,
    BRUSH,
    STICKER;

    fun toFolderName(): String = when (this) {
        THEME -> "Themes"
        COVER -> "Covers"
        TEMPLATE -> "Templates"
        TEXTURE -> "Textures"
        FONT -> "Fonts"
        BRUSH -> "Brushes"
        STICKER -> "Stickers"
    }

    companion object {
        fun fromFolderName(folder: String): AssetCategory? =
            values().find { it.toFolderName() == folder }

        fun fromJsonValue(value: String): AssetCategory? =
            values().find { it.name == value }
    }
}

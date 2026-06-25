package com.paperleaf.sketchbook.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pages")
data class Page(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageNumber: Int,
    val filePath: String = "",
    val templateType: Int = TEMPLATE_BLANK,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TEMPLATE_BLANK       = 0
        const val TEMPLATE_LINED       = 1
        const val TEMPLATE_GRID        = 2
        const val TEMPLATE_DOTTED      = 3
        const val TEMPLATE_ISOMETRIC   = 4
        const val TEMPLATE_MUSIC_SHEET = 5
        const val TEMPLATE_CORNELL     = 6
        const val TEMPLATE_WEEKLY      = 7
        const val TEMPLATE_MONTHLY     = 8
        const val TEMPLATE_STORYBOARD  = 9
        const val TEMPLATE_COMIC       = 10

        val ALL_TEMPLATES = listOf(
            TEMPLATE_BLANK, TEMPLATE_LINED, TEMPLATE_GRID, TEMPLATE_DOTTED,
            TEMPLATE_ISOMETRIC, TEMPLATE_MUSIC_SHEET, TEMPLATE_CORNELL,
            TEMPLATE_WEEKLY, TEMPLATE_MONTHLY, TEMPLATE_STORYBOARD, TEMPLATE_COMIC
        )

        fun templateName(type: Int): String = when (type) {
            TEMPLATE_BLANK       -> "Blank"
            TEMPLATE_LINED       -> "Lined"
            TEMPLATE_GRID        -> "Grid"
            TEMPLATE_DOTTED      -> "Dot Grid"
            TEMPLATE_ISOMETRIC   -> "Isometric"
            TEMPLATE_MUSIC_SHEET -> "Music Sheet"
            TEMPLATE_CORNELL     -> "Cornell Notes"
            TEMPLATE_WEEKLY      -> "Weekly Planner"
            TEMPLATE_MONTHLY     -> "Monthly Planner"
            TEMPLATE_STORYBOARD  -> "Storyboard"
            TEMPLATE_COMIC       -> "Comic Layout"
            else                 -> "Blank"
        }
    }
}
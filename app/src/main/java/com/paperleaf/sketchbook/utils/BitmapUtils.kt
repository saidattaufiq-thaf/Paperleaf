package com.paperleaf.sketchbook.utils

import android.graphics.*

object BitmapUtils {

    fun createBlankPage(w: Int, h: Int, bg: Int = Color.parseColor("#FAFAF8")): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(bg) }

    fun createLinedPage(w: Int, h: Int): Bitmap {
        val bmp = createBlankPage(w, h)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8C0B8"); strokeWidth = 1.5f }
        var y = 80f
        while (y < h) { c.drawLine(48f, y, w - 48f, y, p); y += 56f }
        return bmp
    }

    fun createGridPage(w: Int, h: Int): Bitmap {
        val bmp = createBlankPage(w, h)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D8D0C8"); strokeWidth = 1f }
        var x = 0f; while (x <= w) { c.drawLine(x, 0f, x, h.toFloat(), p); x += 40f }
        var y = 0f; while (y <= h) { c.drawLine(0f, y, w.toFloat(), y, p); y += 40f }
        return bmp
    }

    fun createDottedPage(w: Int, h: Int): Bitmap {
        val bmp = createBlankPage(w, h)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAAAAA") }
        var x = 40f
        while (x < w) { var y = 40f; while (y < h) { c.drawCircle(x, y, 2f, p); y += 40f }; x += 40f }
        return bmp
    }
}
package com.paperleaf.sketchbook.template

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.paperleaf.sketchbook.model.Page
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object PaperTemplateEngine {

    private const val BG_COLOR = 0xFFFAF9F0.toInt()
    private const val LINE_COLOR = 0x70000000.toInt()
    private const val LINE_COLOR_LIGHT = 0x50000000.toInt()
    private const val LINE_COLOR_DIM = 0x40000000.toInt()
    private const val MARGIN_COLOR = 0x60CC4444.toInt()
    private const val HEADER_COLOR = 0x40CC4444.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR
        strokeWidth = 4f
    }

    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR_LIGHT
        strokeWidth = 3f
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR_DIM
        strokeWidth = 2.5f
    }

    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MARGIN_COLOR
        strokeWidth = 5f
    }

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HEADER_COLOR
        strokeWidth = 0f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR
        style = Paint.Style.FILL
        strokeWidth = 3f
    }

    private val thickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE_COLOR
        strokeWidth = 6f
    }

    /**
     * Render a template onto the given canvas.
     * All measurements are relative to (width, height) — no fixed pixel sizes.
     */
    fun drawTemplate(canvas: Canvas, width: Float, height: Float, templateType: Int) {
        when (templateType) {
            Page.TEMPLATE_BLANK       -> drawBlank(canvas, width, height)
            Page.TEMPLATE_LINED       -> drawLined(canvas, width, height)
            Page.TEMPLATE_GRID        -> drawGrid(canvas, width, height)
            Page.TEMPLATE_DOTTED      -> drawDotGrid(canvas, width, height)
            Page.TEMPLATE_ISOMETRIC   -> drawIsometric(canvas, width, height)
            Page.TEMPLATE_MUSIC_SHEET -> drawMusicSheet(canvas, width, height)
            Page.TEMPLATE_CORNELL     -> drawCornell(canvas, width, height)
            Page.TEMPLATE_WEEKLY      -> drawWeekly(canvas, width, height)
            Page.TEMPLATE_MONTHLY     -> drawMonthly(canvas, width, height)
            Page.TEMPLATE_STORYBOARD  -> drawStoryboard(canvas, width, height)
            Page.TEMPLATE_COMIC       -> drawComic(canvas, width, height)
        }
    }

    /**
     * Render a template thumbnail for the popup preview.
     * Smaller canvas, same proportional layout.
     */
    fun drawThumbnail(canvas: Canvas, width: Float, height: Float, templateType: Int) {
        val scale = min(width / 420f, height / 297f)
        val scaledW = 420f * scale
        val scaledH = 297f * scale
        val left = (width - scaledW) / 2f
        val top = (height - scaledH) / 2f

        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, scaledW, scaledH)
        canvas.drawColor(BG_COLOR)

        val savedWidth = linePaint.strokeWidth
        val savedLight = lightPaint.strokeWidth
        val savedDim = dimPaint.strokeWidth
        val savedMargin = marginPaint.strokeWidth
        val savedDot = dotPaint.strokeWidth
        val savedThick = thickPaint.strokeWidth
        linePaint.strokeWidth = linePaint.strokeWidth * scale
        lightPaint.strokeWidth = lightPaint.strokeWidth * scale
        dimPaint.strokeWidth = dimPaint.strokeWidth * scale
        marginPaint.strokeWidth = marginPaint.strokeWidth * scale
        dotPaint.strokeWidth = dotPaint.strokeWidth * scale
        thickPaint.strokeWidth = thickPaint.strokeWidth * scale

        drawTemplate(canvas, scaledW, scaledH, templateType)

        // Border
        val borderPaint = Paint().apply {
            color = 0x80000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        canvas.drawRect(0f, 0f, scaledW, scaledH, borderPaint)

        linePaint.strokeWidth = savedWidth
        lightPaint.strokeWidth = savedLight
        dimPaint.strokeWidth = savedDim
        marginPaint.strokeWidth = savedMargin
        dotPaint.strokeWidth = savedDot
        thickPaint.strokeWidth = savedThick
        canvas.restore()
    }

    // ─── BLANK ────────────────────────────────────────────────────
    private fun drawBlank(canvas: Canvas, @Suppress("UNUSED_PARAMETER") width: Float, @Suppress("UNUSED_PARAMETER") height: Float) {
        canvas.drawColor(BG_COLOR)
    }

    // ─── LINED ────────────────────────────────────────────────────
    private fun drawLined(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val marginL = width * 0.05f
        val marginR = width * 0.05f
        val lineSpacing = height * 0.035f
        val textWidth = width - marginL - marginR

        // Left margin line (red)
        canvas.drawLine(marginL, 0f, marginL, height, marginPaint)

        var y = lineSpacing
        while (y < height) {
            canvas.drawLine(marginL, y, marginL + textWidth, y, linePaint)
            y += lineSpacing
        }
    }

    // ─── GRID ─────────────────────────────────────────────────────
    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val spacing = min(width, height) * 0.03f

        var x = spacing
        while (x < width) {
            canvas.drawLine(x, 0f, x, height, lightPaint)
            x += spacing
        }

        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width, y, lightPaint)
            y += spacing
        }

        // Center lines slightly thicker
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx, 0f, cx, height, linePaint)
        canvas.drawLine(0f, cy, width, cy, linePaint)
    }

    // ─── DOT GRID ─────────────────────────────────────────────────
    private fun drawDotGrid(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val spacing = min(width, height) * 0.03f
        val radius = spacing * 0.1f

        var x = spacing
        while (x < width) {
            var y = spacing
            while (y < height) {
                canvas.drawCircle(x, y, radius, dotPaint)
                y += spacing
            }
            x += spacing
        }
    }

    // ─── ISOMETRIC ────────────────────────────────────────────────
    private fun drawIsometric(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val spacing = min(width, height) * 0.04f
        val stepX = spacing * 0.866f  // cos(30°)
        val stepY = spacing * 0.5f    // sin(30°)

        // Right-tilted lines (/)
        var startX = -height * stepX / stepY
        while (startX < width + height * stepX / stepY) {
            canvas.drawLine(startX, 0f, startX + height * stepX / stepY, height, lightPaint)
            startX += stepX * 2
        }

        // Left-tilted lines (\)
        startX = -height * stepX / stepY
        while (startX < width + height * stepX / stepY) {
            canvas.drawLine(startX, 0f, startX - height * stepX / stepY, height, lightPaint)
            startX += stepX * 2
        }

        // Vertical lines (|) through intersections
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height, dimPaint)
            x += spacing * 2
        }
    }

    // ─── MUSIC SHEET ──────────────────────────────────────────────
    private fun drawMusicSheet(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val lineSpacing = height * 0.012f
        val staffGap = height * 0.08f
        val marginL = width * 0.06f
        val marginR = width * 0.04f
        val staffWidth = width - marginL - marginR

        var topY = staffGap
        while (topY + lineSpacing * 4 < height) {
            // Draw 5 lines per staff
            for (i in 0 until 5) {
                val ly = topY + i * lineSpacing
                canvas.drawLine(marginL, ly, marginL + staffWidth, ly, linePaint)
            }

            // Staff bracket (thick vertical bar at start)
            canvas.drawLine(marginL, topY, marginL, topY + lineSpacing * 4, thickPaint)

            topY += lineSpacing * 4 + staffGap
        }
    }

    // ─── CORNELL NOTES ────────────────────────────────────────────
    private fun drawCornell(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val leftColW = width * 0.25f
        val summaryH = height * 0.15f
        val marginL = width * 0.04f
        val marginR = width * 0.04f
        val marginT = height * 0.03f
        val marginB = height * 0.03f
        val contentW = width - marginL - marginR
        val contentH = height - marginT - marginB
        val noteAreaH = contentH - summaryH

        // Vertical divider: left column (cues/questions) vs right (notes)
        val dividerX = marginL + leftColW
        canvas.drawLine(dividerX, marginT, dividerX, marginT + noteAreaH, thickPaint)

        // Horizontal divider for summary area at bottom
        val summaryY = marginT + noteAreaH
        canvas.drawLine(marginL, summaryY, marginL + contentW, summaryY, thickPaint)

        // Horizontal lines in note area (right column)
        val lineSpacing = height * 0.03f
        var y = marginT + lineSpacing
        while (y < summaryY) {
            canvas.drawLine(dividerX, y, marginL + contentW, y, linePaint)
            y += lineSpacing
        }

        // Horizontal lines in cue area (left column)
        y = marginT + lineSpacing
        while (y < summaryY) {
            canvas.drawLine(marginL, y, dividerX, y, linePaint)
            y += lineSpacing
        }

        // "Summary" label area
        val summaryLabel = Paint().apply {
            color = 0x60000000.toInt()
            textSize = summaryH * 0.25f
            isAntiAlias = true
        }
        canvas.drawText("Summary", marginL + 4f, summaryY + summaryH * 0.6f, summaryLabel)

        val cueLabel = Paint().apply {
            color = 0x40000000.toInt()
            textSize = height * 0.015f
            isAntiAlias = true
        }
        canvas.save()
        canvas.rotate(-90f, marginL + 4f, marginT + 4f)
        canvas.drawText("Cues / Questions", marginL + 4f, marginT + noteAreaH * 0.15f, cueLabel)
        canvas.restore()
    }

    // ─── WEEKLY PLANNER ───────────────────────────────────────────
    private fun drawWeekly(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val headerH = height * 0.08f
        val marginL = width * 0.02f
        val marginR = width * 0.02f
        val marginT = height * 0.02f
        val marginB = height * 0.02f
        val usableW = width - marginL - marginR
        val usableH = height - marginT - marginB - headerH
        val colW = usableW / 7f

        // Draw column dividers
        for (i in 0..7) {
            val x = marginL + i * colW
            canvas.drawLine(x, marginT, x, marginT + headerH + usableH, linePaint)
        }

        // Header row
        canvas.drawRect(marginL, marginT, marginL + usableW, marginT + headerH, headerPaint)
        canvas.drawLine(marginL, marginT + headerH, marginL + usableW, marginT + headerH, thickPaint)

        val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80000000.toInt()
            textSize = headerH * 0.4f
            textAlign = Paint.Align.CENTER
        }
        for (i in 0 until 7) {
            val cx = marginL + (i + 0.5f) * colW
            canvas.drawText(dayLabels[i], cx, marginT + headerH * 0.6f, labelPaint)
        }

        // Horizontal lines in each day column
        val lineSpacing = height * 0.03f
        var y = marginT + headerH + lineSpacing
        while (y < marginT + headerH + usableH) {
            canvas.drawLine(marginL, y, marginL + usableW, y, lightPaint)
            y += lineSpacing
        }
    }

    // ─── MONTHLY PLANNER ──────────────────────────────────────────
    private fun drawMonthly(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val marginL = width * 0.02f
        val marginR = width * 0.02f
        val marginT = height * 0.02f
        val marginB = height * 0.02f

        // 7 columns (days) x ~5 rows (weeks) = grid
        val cols = 7
        val rows = 5
        val usableW = width - marginL - marginR
        val usableH = height - marginT - marginB

        // For Monthly, give a bit more to header row
        val headerH = usableH * 0.1f
        val cellH = (usableH - headerH) / rows

        // Month name header
        val monthLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80000000.toInt()
            textSize = headerH * 0.5f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("Month", marginL + 8f, marginT + headerH * 0.65f, monthLabel)

        // Day-of-week header row
        val dayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x60000000.toInt()
            textSize = cellH * 0.25f
            textAlign = Paint.Align.CENTER
        }
        for (col in 0 until cols) {
            val cx = marginL + (col + 0.5f) * usableW / cols
            canvas.drawText(dayLabels[col], cx, marginT + headerH + cellH * 0.25f, dayLabelPaint)
        }

        // Grid lines
        for (col in 0..cols) {
            val x = marginL + col * (usableW / cols)
            canvas.drawLine(x, marginT, x, marginT + usableH, linePaint)
        }

        for (row in 0..rows) {
            val y = marginT + headerH + row * cellH
            canvas.drawLine(marginL, y, marginL + usableW, y, linePaint)
        }
    }

    // ─── STORYBOARD ───────────────────────────────────────────────
    private fun drawStoryboard(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val cols = 3
        val rows = 3
        val gap = min(width, height) * 0.02f
        val margin = gap

        val usableW = width - margin * 2 - gap * (cols - 1)
        val usableH = height - margin * 2 - gap * (rows - 1)
        val panelW = usableW / cols
        val panelH = usableH / rows

        val panelNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000.toInt()
            textSize = min(panelW, panelH) * 0.15f
            textAlign = Paint.Align.CENTER
        }

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val px = margin + col * (panelW + gap)
                val py = margin + row * (panelH + gap)
                canvas.drawRect(px, py, px + panelW, py + panelH, framePaint)

                // Panel number
                val num = row * cols + col + 1
                val label = "$num"
                val textX = px + panelW / 2f
                val textY = py + panelH / 2f + panelNumPaint.textSize / 3f
                canvas.drawText(label, textX, textY, panelNumPaint)
            }
        }
    }

    // ─── COMIC LAYOUT ─────────────────────────────────────────────
    private fun drawComic(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(BG_COLOR)
        val margin = min(width, height) * 0.015f
        val gap = margin * 0.5f
        val usableW = width - margin * 2
        val usableH = height - margin * 2

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        val panelNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000.toInt()
            textSize = min(usableW, usableH) * 0.06f
            textAlign = Paint.Align.CENTER
        }

        // Comic layout: top banner + 3 rows of panels
        // Row 1: full-width banner (top 25%)
        // Row 2: 2 equal panels
        // Row 3: 3 equal panels
        val bannerH = usableH * 0.25f
        val row2H = usableH * 0.35f
        val row3H = usableH * 0.40f

        // Banner (top row, full width)
        canvas.drawRect(margin, margin, margin + usableW, margin + bannerH, framePaint)
        drawComicPanelLabel(canvas, margin, margin, usableW, bannerH, 1, panelNumPaint)

        // Row 2: 2 panels
        val row2W = (usableW - gap) / 2f
        val row2Y = margin + bannerH + gap
        canvas.drawRect(margin, row2Y, margin + row2W, row2Y + row2H, framePaint)
        drawComicPanelLabel(canvas, margin, row2Y, row2W, row2H, 2, panelNumPaint)

        canvas.drawRect(margin + row2W + gap, row2Y, margin + usableW, row2Y + row2H, framePaint)
        drawComicPanelLabel(canvas, margin + row2W + gap, row2Y, row2W, row2H, 3, panelNumPaint)

        // Row 3: 3 panels
        val row3W = (usableW - gap * 2f) / 3f
        val row3Y = row2Y + row2H + gap
        for (i in 0 until 3) {
            val px = margin + i * (row3W + gap)
            canvas.drawRect(px, row3Y, px + row3W, row3Y + row3H, framePaint)
            drawComicPanelLabel(canvas, px, row3Y, row3W, row3H, 4 + i, panelNumPaint)
        }
    }

    private fun drawComicPanelLabel(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        num: Int, paint: Paint
    ) {
        canvas.drawText(
            "$num",
            x + w / 2f,
            y + h / 2f + paint.textSize / 3f,
            paint
        )
    }
}

package com.paperleaf.sketchbook.selection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

object SelectionProcessor {

    // Set this to true once OpenCV is bundled for improved algorithms
    private var openCvAvailable = false
    private var tolerance = 40
    private val morphologyRadius = 2

    fun init(): Boolean {
        // Try loading OpenCV; stays false if not available
        return try {
            val cl = Class.forName("org.opencv.android.OpenCVLoader")
            val m = cl.getMethod("initDebug")
            openCvAvailable = m.invoke(null) as Boolean
            openCvAvailable
        } catch (_: Throwable) {
            openCvAvailable = false
            false
        }
    }

    fun isInitialized(): Boolean = openCvAvailable

    // ─── LASSO SMOOTHING ──────────────────────────────────────────

    suspend fun smoothLassoPath(
        points: List<Pair<Float, Float>>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Path? = withContext(Dispatchers.Default) {
        try {
            if (openCvAvailable) openCvSmoothLasso(points, bitmapWidth, bitmapHeight)
            else nativeSmoothLasso(points, bitmapWidth, bitmapHeight)
        } catch (_: Throwable) {
            nativeSmoothLasso(points, bitmapWidth, bitmapHeight)
        }
    }

    private fun nativeSmoothLasso(
        points: List<Pair<Float, Float>>,
        w: Int, h: Int
    ): Path {
        val simplified = rdpSimplify(points, 2.5f)
        val mask = BooleanArray(w * h) { false }
        val minY = simplified.minOf { it.second.toInt().coerceIn(0, h - 1) }
        val maxY = simplified.maxOf { it.second.toInt().coerceIn(0, h - 1) }
        val minX = simplified.minOf { it.first.toInt().coerceIn(0, w - 1) }
        val maxX = simplified.maxOf { it.first.toInt().coerceIn(0, w - 1) }

        for (y in minY..maxY) {
            val intersections = mutableListOf<Int>()
            var inside = false
            for (x in minX..maxX) {
                if (pointInPolygon(simplified, x.toFloat(), y.toFloat())) {
                    if (!inside) { intersections.add(x); inside = true }
                } else {
                    if (inside) { intersections.add(x - 1); inside = false }
                }
            }
            if (inside) intersections.add(maxX)
            for (i in intersections.indices step 2) {
                if (i + 1 < intersections.size) {
                    for (x in intersections[i]..intersections[i + 1]) {
                        if (x in 0 until w && y in 0 until h) mask[y * w + x] = true
                    }
                }
            }
        }

        val smoothedMask = morphologyClose(mask, w, h, morphologyRadius)
        val edgeMask = morphologyOpen(smoothedMask, w, h, morphologyRadius)

        return maskToOutlinePath(edgeMask, w, h)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun openCvSmoothLasso(
        points: List<Pair<Float, Float>>,
        w: Int, h: Int
    ): Path {
        // Placeholder — replaced when OpenCV is bundled
        return nativeSmoothLasso(points, w, h)
    }

    // ─── MAGIC WAND (FLOOD FILL) ─────────────────────────────────

    suspend fun floodFillSelect(
        bitmap: Bitmap,
        tapX: Int,
        tapY: Int,
        tolerance: Int
    ): Path? = withContext(Dispatchers.Default) {
        try {
            if (openCvAvailable) openCvFloodFill(bitmap, tapX, tapY, tolerance)
            else nativeFloodFill(bitmap, tapX, tapY, tolerance)
        } catch (_: Throwable) {
            nativeFloodFill(bitmap, tapX, tapY, tolerance)
        }
    }

    private fun nativeFloodFill(
        bitmap: Bitmap, tx: Int, ty: Int, tol: Int
    ): Path {
        val w = bitmap.width; val h = bitmap.height
        val targetColor = bitmap.getPixel(tx.coerceIn(0, w - 1), ty.coerceIn(0, h - 1))
        val visited = BooleanArray(w * h)
        val selected = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        val idx = ty * w + tx
        queue.addLast(idx)
        visited[idx] = true
        var count = 0
        val maxPixels = 500000

        while (queue.isNotEmpty() && count < maxPixels) {
            val cur = queue.removeFirst()
            val cx = cur % w; val cy = cur / w
            selected[cur] = true
            count++
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val nx = cx + dx; val ny = cy + dy
                if (nx in 0 until w && ny in 0 until h) {
                    val ni = ny * w + nx
                    if (!visited[ni]) {
                        visited[ni] = true
                        val c = bitmap.getPixel(nx, ny)
                        if (colorDist(c, targetColor) <= tol) queue.addLast(ni)
                    }
                }
            }
        }

        val opened = morphologyOpen(selected, w, h, 2)
        val closed = morphologyClose(opened, w, h, 2)

        return maskToOutlinePath(closed, w, h)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun openCvFloodFill(
        bitmap: Bitmap, tx: Int, ty: Int, tol: Int
    ): Path {
        // Placeholder — replaced when OpenCV is bundled
        return nativeFloodFill(bitmap, tx, ty, tol)
    }

    // ─── SMART CUT (SIMPLIFIED GRABCUT) ──────────────────────────

    suspend fun grabCutSelect(
        bitmap: Bitmap,
        rect: Rect,
        scaleX: Float,
        scaleY: Float
    ): Path? = withContext(Dispatchers.Default) {
        try {
            if (openCvAvailable) openCvGrabCut(bitmap, rect, scaleX, scaleY)
            else nativeGrabCut(bitmap, rect, scaleX, scaleY)
        } catch (_: Throwable) {
            nativeGrabCut(bitmap, rect, scaleX, scaleY)
        }
    }

    private fun nativeGrabCut(
        bitmap: Bitmap, rect: Rect, scaleX: Float, scaleY: Float
    ): Path {
        val w = bitmap.width; val h = bitmap.height
        val bx = (rect.left / scaleX).toInt().coerceIn(0, w - 1)
        val by = (rect.top / scaleY).toInt().coerceIn(0, h - 1)
        val bw = (rect.width() / scaleX).toInt().coerceIn(2, w - bx)
        val bh = (rect.height() / scaleY).toInt().coerceIn(2, h - by)
        val ex = (bx + bw).coerceAtMost(w)
        val ey = (by + bh).coerceAtMost(h)

        // Sample background colors outside rect (4 sides)
        val bgColors = mutableListOf<Int>()
        val border = 4
        for (x in 0 until w) {
            for (y in listOf(0, h - 1)) {
                if (x < bx - border || x > ex + border || y < by - border || y > ey + border) {
                    bgColors.add(bitmap.getPixel(x, y))
                }
            }
        }
        for (y in 0 until h) {
            for (x in listOf(0, w - 1)) {
                if (x < bx - border || x > ex + border || y < by - border || y > ey + border) {
                    bgColors.add(bitmap.getPixel(x, y))
                }
            }
        }
        val avgBg = if (bgColors.isNotEmpty()) averageColor(bgColors) else 0xFF808080.toInt()

        // Flood fill from seeds inside rect
        val seeds = listOf(
            (bx + bw / 4) to (by + bh / 4),
            (bx + 3 * bw / 4) to (by + bh / 4),
            (bx + bw / 4) to (by + 3 * bh / 4),
            (bx + 3 * bw / 4) to (by + 3 * bh / 4),
            (bx + bw / 2) to (by + bh / 2)
        )

        val selected = BooleanArray(w * h)
        val visited = BooleanArray(w * h)
        val rectTol = 35

        for ((sx, sy) in seeds) {
            val si = sy.coerceIn(by + 1, ey - 1) * w + sx.coerceIn(bx + 1, ex - 1)
            if (visited[si]) continue

            val seedColor = bitmap.getPixel(si % w, si / w)
            val queue = ArrayDeque<Int>()
            queue.addLast(si)
            visited[si] = true

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cx = cur % w; val cy = cur / w
                val pixel = bitmap.getPixel(cx, cy)
                if (colorDist(pixel, avgBg) < 30 && colorDist(pixel, seedColor) > 50) continue
                selected[cur] = true

                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx in bx until ex && ny in by until ey) {
                        val ni = ny * w + nx
                        if (!visited[ni]) {
                            visited[ni] = true
                            val nc = bitmap.getPixel(nx, ny)
                            if (colorDist(nc, seedColor) <= rectTol) queue.addLast(ni)
                        }
                    }
                }
            }
        }

        val closed = morphologyClose(selected, w, h, 3)
        val opened = morphologyOpen(closed, w, h, 2)

        return maskToOutlinePath(opened, w, h)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun openCvGrabCut(
        bitmap: Bitmap, rect: Rect, scaleX: Float, scaleY: Float
    ): Path {
        // Placeholder — replaced when OpenCV is bundled
        return nativeGrabCut(bitmap, rect, scaleX, scaleY)
    }

    // ─── MORPHOLOGY OPERATIONS ────────────────────────────────────

    private fun morphologyOpen(
        mask: BooleanArray, w: Int, h: Int, radius: Int
    ): BooleanArray {
        val eroded = erode(mask, w, h, radius)
        return dilate(eroded, w, h, radius)
    }

    private fun morphologyClose(
        mask: BooleanArray, w: Int, h: Int, radius: Int
    ): BooleanArray {
        val dilated = dilate(mask, w, h, radius)
        return erode(dilated, w, h, radius)
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val result = BooleanArray(w * h) { true }
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x
            if (!mask[idx]) { result[idx] = false; continue }
            var ok = true
            run loop@ {
                for (dy in -r..r) for (dx in -r..r) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        if (!mask[ny * w + nx]) { ok = false; return@loop }
                    }
                }
            }
            result[idx] = ok
        }
        return result
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val result = BooleanArray(w * h) { false }
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            for (dy in -r..r) for (dx in -r..r) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) result[ny * w + nx] = true
            }
        }
        return result
    }

    // ─── CONTOUR → PATH ───────────────────────────────────────────

    private fun maskToOutlinePath(mask: BooleanArray, w: Int, h: Int): Path {
        val edgePoints = mutableListOf<Pair<Int, Int>>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val idx = y * w + x
            if (mask[idx]) {
                var isEdge = false
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    if (!mask[(y + dy) * w + (x + dx)]) { isEdge = true; break }
                }
                if (isEdge) edgePoints.add(x to y)
            }
        }

        val path = Path()
        if (edgePoints.isEmpty()) return path

        val sorted = traceContour(edgePoints)
        path.moveTo(sorted[0].first.toFloat(), sorted[0].second.toFloat())
        for (i in 1 until sorted.size) {
            path.lineTo(sorted[i].first.toFloat(), sorted[i].second.toFloat())
        }
        path.close()
        return path
    }

    private fun traceContour(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (points.isEmpty()) return points
        val visited = mutableSetOf<Pair<Int, Int>>()
        val result = mutableListOf<Pair<Int, Int>>()
        var current = points.first()
        result.add(current)
        visited.add(current)

        var prevDir = 0 to 0
        var stuckCount = 0

        while (result.size < points.size && stuckCount < 1000) {
            val (cx, cy) = current
            val dirs = listOf(
                1 to 0, 0 to 1, -1 to 0, 0 to -1,
                1 to 1, -1 to 1, 1 to -1, -1 to -1
            )
            // Prioritize direction similar to previous
            val sortedDirs = if (prevDir.first != 0 || prevDir.second != 0) {
                dirs.sortedByDescending { (dx, dy) -> dx * prevDir.first + dy * prevDir.second }
            } else dirs

            var found = false
            for ((dx, dy) in sortedDirs) {
                val np = (cx + dx) to (cy + dy)
                if (np in points && np !in visited) {
                    result.add(np)
                    visited.add(np)
                    current = np
                    prevDir = dx to dy
                    found = true
                    break
                }
            }
            if (!found) {
                stuckCount++
                // Find nearest unvisited point
                val nearest = points.filter { it !in visited }
                    .minByOrNull { (px, py) -> (px - cx) * (px - cx) + (py - cy) * (py - cy) }
                if (nearest != null) {
                    result.add(nearest)
                    visited.add(nearest)
                    current = nearest
                    prevDir = nearest.first - cx to nearest.second - cy
                }
            }
        }
        return result
    }

    // ─── GEOMETRY HELPERS ─────────────────────────────────────────

    private fun rdpSimplify(points: List<Pair<Float, Float>>, epsilon: Float): List<Pair<Float, Float>> {
        if (points.size <= 2) return points
        var maxDist = 0f; var maxIdx = 0
        val first = points.first(); val last = points.last()
        for (i in 1 until points.size - 1) {
            val d = perpendicularDist(points[i], first, last)
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        return if (maxDist > epsilon) {
            rdpSimplify(points.subList(0, maxIdx + 1), epsilon) +
                rdpSimplify(points.subList(maxIdx, points.size), epsilon).drop(1)
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDist(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first; val dy = b.second - a.second
        val len = hypot(dx, dy)
        if (len == 0f) return hypot(p.first - a.first, p.second - a.second)
        return abs(dy * p.first - dx * p.second + b.first * a.second - b.second * a.first) / len
    }

    private fun pointInPolygon(polygon: List<Pair<Float, Float>>, px: Float, py: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            if ((polygon[i].second > py) != (polygon[j].second > py) &&
                px < (polygon[j].first - polygon[i].first) * (py - polygon[i].second) /
                    (polygon[j].second - polygon[i].second) + polygon[i].first
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun colorDist(c1: Int, c2: Int): Int {
        return abs(Color.red(c1) - Color.red(c2)) +
            abs(Color.green(c1) - Color.green(c2)) +
            abs(Color.blue(c1) - Color.blue(c2))
    }

    private fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        var r = 0; var g = 0; var b = 0
        for (c in colors) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        return Color.rgb(r / colors.size, g / colors.size, b / colors.size)
    }
}

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
    var tolerance = 40  // Made configurable for better control
    private val morphologyRadius = 2
    
    // Edge detection sensitivity for smart cut
    private val edgeThreshold = 30
    private val maxIterations = 5

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
    
    fun setTolerance(value: Int) {
        tolerance = value.coerceIn(10, 100)
    }

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
        if (points.size < 3) return Path()
        
        // Use higher precision simplification for smoother curves
        val simplified = rdpSimplify(points, 1.5f)
        if (simplified.size < 3) return Path()
        
        val mask = BooleanArray(w * h) { false }
        val minY = simplified.minOfOrNull { it.second.toInt().coerceIn(0, h - 1) } ?: 0
        val maxY = simplified.maxOfOrNull { it.second.toInt().coerceIn(0, h - 1) } ?: h - 1
        val minX = simplified.minOfOrNull { it.first.toInt().coerceIn(0, w - 1) } ?: 0
        val maxX = simplified.maxOfOrNull { it.first.toInt().coerceIn(0, w - 1) } ?: w - 1

        // Fill the polygon using scanline algorithm
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

        // Apply morphological operations for smooth edges
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
        tolerance: Int = 40
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
        if (tx !in 0 until w || ty !in 0 until h) return Path()
        
        val targetColor = bitmap.getPixel(tx, ty)
        val visited = BooleanArray(w * h)
        val selected = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        val idx = ty * w + tx
        queue.addLast(idx)
        visited[idx] = true
        var count = 0
        val maxPixels = w * h // Allow full selection if needed

        // Use 8-way connectivity for better fill
        val neighbors = listOf(
            -1 to 0, 1 to 0, 0 to -1, 0 to 1,  // 4-way
            -1 to -1, -1 to 1, 1 to -1, 1 to 1  // diagonals
        )

        while (queue.isNotEmpty() && count < maxPixels) {
            val cur = queue.removeFirst()
            val cx = cur % w; val cy = cur / w
            selected[cur] = true
            count++
            
            for ((dx, dy) in neighbors) {
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

        // Apply morphological operations for cleaner edges
        val opened = morphologyOpen(selected, w, h, 1)
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
        scaleX: Float = 1f,
        scaleY: Float = 1f
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

        // Sample background colors from border region outside rect
        val bgColors = mutableListOf<Int>()
        val border = 8
        val sampleStep = 3
        
        // Top and bottom borders
        for (y in listOf(by - border, ey + border)) {
            if (y in 0 until h) {
                for (x in bx until ex step sampleStep) {
                    bgColors.add(bitmap.getPixel(x, y))
                }
            }
        }
        
        // Left and right borders
        for (x in listOf(bx - border, ex + border)) {
            if (x in 0 until w) {
                for (y in by until ey step sampleStep) {
                    bgColors.add(bitmap.getPixel(x, y))
                }
            }
        }
        
        val avgBg = if (bgColors.isNotEmpty()) averageColor(bgColors) else 0xFF808080.toInt()

        // Sample foreground colors from inside rect (center region)
        val fgColors = mutableListOf<Int>()
        val margin = 5
        for (y in (by + margin) until (ey - margin) step sampleStep) {
            for (x in (bx + margin) until (ex - margin) step sampleStep) {
                fgColors.add(bitmap.getPixel(x, y))
            }
        }
        val avgFg = if (fgColors.isNotEmpty()) averageColor(fgColors) else avgBg

        // Initialize selection with foreground area
        val selected = BooleanArray(w * h)
        for (y in by until ey) {
            for (x in bx until ex) {
                selected[y * w + x] = true
            }
        }

        // Iterative refinement based on color similarity
        for (iteration in 0 until maxIterations) {
            var changed = false
            
            // Check boundary pixels
            for (y in (by - 2).coerceAtLeast(0) until (ey + 2).coerceAtMost(h)) {
                for (x in (bx - 2).coerceAtLeast(0) until (ex + 2).coerceAtMost(w)) {
                    val idx = y * w + x
                    val pixel = bitmap.getPixel(x, y)
                    val distBg = colorDist(pixel, avgBg)
                    val distFg = colorDist(pixel, avgFg)
                    
                    // Pixel should be selected if closer to foreground
                    val shouldBeSelected = distFg < distBg - edgeThreshold
                    
                    if (shouldBeSelected != selected[idx]) {
                        selected[idx] = shouldBeSelected
                        changed = true
                    }
                }
            }
            
            if (!changed) break
        }

        // Apply morphological operations for smooth edges
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

        val sorted = traceContourOptimized(edgePoints)
        if (sorted.isEmpty()) return path
        
        // Start from the topmost-leftmost point for consistent ordering
        val startPoint = sorted.minByOrNull { it.second * w + it.first } ?: sorted[0]
        path.moveTo(startPoint.first.toFloat(), startPoint.second.toFloat())
        
        for (i in 1 until sorted.size) {
            path.lineTo(sorted[i].first.toFloat(), sorted[i].second.toFloat())
        }
        path.close()
        return path
    }

    private fun traceContourOptimized(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (points.isEmpty()) return points
        
        val pointSet = points.toHashSet()
        val visited = mutableSetOf<Pair<Int, Int>>()
        val result = mutableListOf<Pair<Int, Int>>()
        
        // Start from topmost-leftmost point
        var current = points.minByOrNull { it.second * 10000 + it.first } ?: return points
        result.add(current)
        visited.add(current)

        var prevDir = 0 to 0
        var stuckCount = 0
        val maxStuck = points.size / 2

        while (result.size < points.size && stuckCount < maxStuck) {
            val (cx, cy) = current
            
            // Priority order: continue in same direction, then clockwise search
            val dirs = listOf(
                1 to 0, 0 to 1, -1 to 0, 0 to -1,
                1 to 1, -1 to 1, 1 to -1, -1 to -1
            )
            
            val sortedDirs = if (prevDir.first != 0 || prevDir.second != 0) {
                dirs.sortedByDescending { (dx, dy) -> dx * prevDir.first + dy * prevDir.second }
            } else dirs

            var found = false
            for ((dx, dy) in sortedDirs) {
                val np = (cx + dx) to (cy + dy)
                if (np in pointSet && np !in visited) {
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
                // Find nearest unvisited point using Euclidean distance
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
        
        return if (result.size >= points.size / 2) result else points
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
        return if (maxDist > epsilon && maxIdx > 0) {
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
        // Use weighted Euclidean distance for better perceptual accuracy
        val r1 = Color.red(c1); val g1 = Color.green(c1); val b1 = Color.blue(c1)
        val r2 = Color.red(c2); val g2 = Color.green(c2); val b2 = Color.blue(c2)
        val rMean = (r1 + r2) / 2
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        // Approximation of CIE76 color difference
        return kotlin.math.sqrt(((512 + rMean) * dr * dr shr 8) + 4 * dg * dg + ((767 - rMean) * db * db shr 8)).toInt()
    }

    private fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        var r = 0; var g = 0; var b = 0
        for (c in colors) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        return Color.rgb(r / colors.size, g / colors.size, b / colors.size)
    }
}

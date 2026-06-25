package com.paperleaf.sketchbook.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.sin

class BookSpreadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─── DIMENSIONS & CONSTANTS ───────────────────────────────────
    private val dp = resources.displayMetrics.density
    private val cornerR = 20f * dp
    private val stackSh = 9f * dp
    private val stackIn = 2f * dp
    private val MAX_ST = 5

    // ─── PAINTS & MATRICES ────────────────────────────────────────
    private val stackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAF9F0")
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camera = Camera()
    private val camMatrix = Matrix()

    // ─── PUBLIC STATE ─────────────────────────────────────────────
    private var leftBitmap: Bitmap? = null
    private var rightBitmap: Bitmap? = null
    var currentPageNum: Int = 1
    var totalPages: Int = 30
    var isDeepOceanShadow: Boolean = false

    // ─── FLIP STATE ───────────────────────────────────────────────
    private var isFlipping = false
    var flipProgress = 0f
        private set
    var flipToNext = true
        private set
    private var isDragFlip = false

    // Snapshot halaman SEBELUM flip (akan "melipat keluar")
    private var outgoingLeft: Bitmap? = null
    private var outgoingRight: Bitmap? = null

    // Stack count di-capture saat flip mulai
    private var snapRightCount = 0
    private var snapLeftCount = 0

    private var flipAnimator: ValueAnimator? = null

    // ─── LIFECYCLE / MEASUREMENT ──────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: 400
        val stackSpace = (MAX_ST * stackSh + 6f * dp).toInt()
        val pageW = w - 2 * stackSpace
        val pageH = (pageW / 1.4142f).toInt().coerceAtLeast(1)
        val h = pageH + (16 * dp).toInt()
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    // ─── PUBLIC API ───────────────────────────────────────────────
    /**
     * Dipanggil dari BookActivity setiap kali spread berubah.
     * leftBmp  = halaman kiri (sudah di-load sebelum startFlip)
     * rightBmp = halaman kanan
     */
    fun update(leftBmp: Bitmap?, rightBmp: Bitmap?, pageNum: Int, total: Int) {
        leftBitmap = leftBmp
        rightBitmap = rightBmp
        currentPageNum = pageNum
        totalPages = total
        if (!isFlipping) invalidate()
    }

    /**
     * Memulai flip penuh dengan animasi (dari 0 ke 1).
     */
    fun startFlip(toNext: Boolean, outgoing: Bitmap?) {
        flipAnimator?.cancel()
        isDragFlip = false
        captureOutgoingState(toNext, outgoing)
        animateFlip(0f, 1f, 350) {
            isFlipping = false
            flipProgress = 0f
            outgoingLeft = null
            outgoingRight = null
            invalidate()
        }
    }

    /**
     * Menyiapkan flip yang digerakkan oleh drag jari (tanpa animasi).
     */
    fun prepareDragFlip(toNext: Boolean, outgoing: Bitmap?) {
        flipAnimator?.cancel()
        isDragFlip = true
        captureOutgoingState(toNext, outgoing)
        flipProgress = 0f
        isFlipping = true
        invalidate()
    }

    /**
     * Memperbarui progress flip saat drag.
     */
    fun setDragProgress(progress: Float) {
        if (!isDragFlip || !isFlipping) return
        flipProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Menyelesaikan atau membatalkan flip berdasarkan progress.
     */
    fun commitFlip(shouldComplete: Boolean, onFinish: (() -> Unit)? = null) {
        if (!isDragFlip || !isFlipping) return
        isDragFlip = false
        val dur = if (shouldComplete)
            (200 * (1f - flipProgress) + 80).toLong()
        else
            (200 * flipProgress + 80).toLong()
        animateFlip(flipProgress, if (shouldComplete) 1f else 0f, dur) {
            isFlipping = false
            flipProgress = 0f
            outgoingLeft = null
            outgoingRight = null
            invalidate()
            onFinish?.invoke()
        }
    }

    // ─── PRIVATE HELPERS ──────────────────────────────────────────
    private fun captureOutgoingState(toNext: Boolean, outgoing: Bitmap?) {
        flipToNext = toNext
        isFlipping = true
        flipProgress = 0f

        outgoingLeft = outgoing
        outgoingRight = outgoing

        if (toNext) {
            snapRightCount = minOf(totalPages - currentPageNum - 1, MAX_ST).coerceAtLeast(0)
            snapLeftCount = minOf(currentPageNum - 1, MAX_ST)
        } else {
            snapRightCount = minOf(totalPages - currentPageNum, MAX_ST)
            snapLeftCount = minOf(currentPageNum - 2, MAX_ST).coerceAtLeast(0)
        }
    }

    private fun animateFlip(from: Float, to: Float, dur: Long, onEnd: () -> Unit) {
        flipAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = dur.coerceAtLeast(80)
            interpolator = DecelerateInterpolator()
            addUpdateListener { flipProgress = animatedValue as Float; invalidate() }
            doOnEnd { onEnd() }
            start()
        }
    }

    // ─── MAIN DRAW ────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        val stackSpace = MAX_ST * stackSh + 6f * dp
        val pageRect = RectF(stackSpace, 8f * dp, w - stackSpace, height - 8f * dp)
        val cx = w / 2f
        val cy = (pageRect.top + pageRect.bottom) / 2f

        val rightCount = if (isFlipping) snapRightCount else minOf(totalPages - currentPageNum, MAX_ST)
        val leftCount = if (isFlipping) snapLeftCount else minOf(currentPageNum - 1, MAX_ST)

        drawStacks(canvas, pageRect, rightCount, leftCount)

        if (isFlipping) drawFlipAnimation(canvas, pageRect, cx, cy)
        else drawStaticSpread(canvas, pageRect)

        drawFoldShadow(canvas, pageRect, cx)
    }

    // ─── DRAWING COMPONENTS ───────────────────────────────────────
    private fun drawStaticSpread(canvas: Canvas, page: RectF) {
        val cx = (page.left + page.right) / 2f
        val leftPage = RectF(page.left, page.top, cx, page.bottom)
        val rightPage = RectF(cx, page.top, page.right, page.bottom)

        canvas.save()
        canvas.clipPath(roundRectPath(leftPage, cornerR, false))
        drawPage(canvas, leftPage, leftBitmap, false)
        canvas.restore()

        canvas.save()
        canvas.clipPath(roundRectPath(rightPage, cornerR, true))
        drawPage(canvas, rightPage, rightBitmap ?: leftBitmap, true)
        canvas.restore()
    }

    /**
     * Animasi page-turn kontinu — satu lembar melipat dari kanan ke kiri
     * (flipToNext) atau kiri ke kanan (flipToPrev).
     *
     * flipToNext (geser kiri → spread berikutnya):
     *   0-50%: halaman kanan (B) melipat dr tepi kanan ke tengah,
     *          memperlihatkan halaman D (kanan spread baru) di bawahnya.
     *  50-100%: halaman kiri (A) melipat dr tengah ke kiri,
     *          memperlihatkan halaman C (kiri spread baru) di bawahnya.
     *
     * flipToPrev (geser kanan → spread sebelumnya):
     *   0-50%: halaman kiri (A) melipat dr tepi kiri ke tengah,
     *          memperlihatkan C (kiri spread lama) di bawahnya.
     *  50-100%: halaman kanan (B) melipat dr tengah ke kanan,
     *          memperlihatkan D (kanan spread lama) di bawahnya.
     */
    private fun drawFlipAnimation(canvas: Canvas, page: RectF, cx: Float, cy: Float) {
        val newBmp = leftBitmap
        val leftPage = RectF(page.left, page.top, cx, page.bottom)
        val rightPage = RectF(cx, page.top, page.right, page.bottom)

        var foldX: Float

        if (flipToNext) {
            if (flipProgress < 0.5f) {
                // Phase 1: B (old right) folds right → center, D revealed underneath
                val t = flipProgress / 0.5f
                val angle = t * 90f
                foldX = page.right - (page.right - cx) * t

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                drawPage(canvas, leftPage, outgoingLeft, false)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                drawPage(canvas, rightPage, newBmp, true)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                applyFold(canvas, cx, cy, angle)
                drawPage(canvas, rightPage, outgoingRight, true)
                canvas.restore()
            } else {
                // Phase 2: A (old left) folds center → left, C revealed underneath
                val t = (flipProgress - 0.5f) / 0.5f
                val angle = t * 90f
                foldX = cx - (cx - page.left) * t

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                drawPage(canvas, leftPage, newBmp, false)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                drawPage(canvas, rightPage, newBmp, true)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                applyFold(canvas, cx, cy, angle)
                drawPage(canvas, leftPage, outgoingLeft, false)
                canvas.restore()
            }
        } else {
            if (flipProgress < 0.5f) {
                // Phase 1: A (old left) folds left → center, C revealed underneath
                val t = flipProgress / 0.5f
                val angle = t * 90f
                foldX = page.left + (cx - page.left) * t

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                drawPage(canvas, leftPage, newBmp, false)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                drawPage(canvas, rightPage, outgoingRight, true)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                applyFold(canvas, cx, cy, -angle)
                drawPage(canvas, leftPage, outgoingLeft, false)
                canvas.restore()
            } else {
                // Phase 2: B (old right) folds center → right, D revealed underneath
                val t = (flipProgress - 0.5f) / 0.5f
                val angle = t * 90f
                foldX = cx + (page.right - cx) * t

                canvas.save()
                canvas.clipPath(roundRectPath(leftPage, cornerR, false))
                drawPage(canvas, leftPage, newBmp, false)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                drawPage(canvas, rightPage, newBmp, true)
                canvas.restore()

                canvas.save()
                canvas.clipPath(roundRectPath(rightPage, cornerR, true))
                applyFold(canvas, cx, cy, -angle)
                drawPage(canvas, rightPage, outgoingRight, true)
                canvas.restore()
            }
        }

        // ── Bayangan dinamis di garis lipatan ──
        val phaseT = if (flipProgress < 0.5f) flipProgress / 0.5f else (flipProgress - 0.5f) / 0.5f
        val alpha = (sin(phaseT * Math.PI) * 80).toInt()
        if (alpha > 0) {
            val sw = 18f * dp
            val sa = if (isDeepOceanShadow) 26 else 0
            val sb = if (isDeepOceanShadow) 108 else 0
            val sc = if (isDeepOceanShadow) 121 else 0
            shadowPaint.shader = LinearGradient(
                foldX - sw, 0f, foldX + sw, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(alpha, sa, sb, sc), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(foldX - sw, page.top, foldX + sw, page.bottom, shadowPaint)
        }
    }

    private fun drawStacks(canvas: Canvas, page: RectF, right: Int, left: Int) {
        val baseR = if (isDeepOceanShadow) 26 else 245
        val baseG = if (isDeepOceanShadow) 108 else 242
        val baseB = if (isDeepOceanShadow) 121 else 237
        val baseL = if (isDeepOceanShadow) 26 else 238
        val baseLG = if (isDeepOceanShadow) 108 else 235
        val baseLB = if (isDeepOceanShadow) 121 else 230
        for (i in right downTo 1) {
            val dx = i * stackSh
            val dy = i * stackIn
            stackPaint.color = Color.argb(
                (255 * (1f - i * 0.14f)).toInt().coerceIn(80, 255), baseR, baseG, baseB
            )
            canvas.drawRoundRect(
                RectF(page.left + dx, page.top + dy, page.right + dx, page.bottom - dy),
                cornerR, cornerR, stackPaint
            )
        }
        for (i in left downTo 1) {
            val dx = i * stackSh
            val dy = i * stackIn
            stackPaint.color = Color.argb(
                (255 * (1f - i * 0.14f)).toInt().coerceIn(80, 255), baseL, baseLG, baseLB
            )
            canvas.drawRoundRect(
                RectF(page.left - dx, page.top + dy, page.right - dx, page.bottom - dy),
                cornerR, cornerR, stackPaint
            )
        }
    }

    private fun drawFoldShadow(canvas: Canvas, page: RectF, cx: Float) {
        val fw = 10f * dp
        val sr = if (isDeepOceanShadow) 26 else 0
        val sg = if (isDeepOceanShadow) 108 else 0
        val sb = if (isDeepOceanShadow) 121 else 0
        foldPaint.shader = LinearGradient(
            cx - fw, 0f, cx + fw, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(10, sr, sg, sb),
                Color.argb(45, sr, sg, sb),
                Color.argb(10, sr, sg, sb),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        // Clip ke page agar tidak meluber ke luar sudut
        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(page, cornerR, cornerR, Path.Direction.CW)
        })
        canvas.drawRect(cx - fw, page.top, cx + fw, page.bottom, foldPaint)
        canvas.restore()
    }

    // ─── HELPERS ──────────────────────────────────────────────────
    private fun drawPage(canvas: Canvas, dest: RectF, bmp: Bitmap?, isRight: Boolean) {
        canvas.drawRect(dest, pageBgPaint)
        bmp ?: return
        val src = if (isRight) {
            Rect(bmp.width / 2, 0, bmp.width, bmp.height)
        } else {
            Rect(0, 0, bmp.width / 2, bmp.height)
        }
        canvas.drawBitmap(bmp, src, dest, bmpPaint)
    }

    /**
     * isRight = true  → sudut membulat di kanan (TR & BR)
     * isRight = false → sudut membulat di kiri  (TL & BL)
     * Sudut yang bertemu di tengah (fold) = 0 radius
     */
    private fun roundRectPath(rect: RectF, r: Float, isRight: Boolean): Path {
        val path = Path()
        if (isRight) {
            // kiri atas → kanan atas (radius) → kanan bawah (radius) → kiri bawah → tutup
            path.moveTo(rect.left, rect.top)
            path.lineTo(rect.right - r, rect.top)
            path.quadTo(rect.right, rect.top, rect.right, rect.top + r)
            path.lineTo(rect.right, rect.bottom - r)
            path.quadTo(rect.right, rect.bottom, rect.right - r, rect.bottom)
            path.lineTo(rect.left, rect.bottom)
            path.close()
        } else {
            // kiri atas (radius) → kanan atas → kanan bawah → kiri bawah (radius) → tutup
            path.moveTo(rect.left + r, rect.top)
            path.quadTo(rect.left, rect.top, rect.left, rect.top + r)
            path.lineTo(rect.left, rect.bottom - r)
            path.quadTo(rect.left, rect.bottom, rect.left + r, rect.bottom)
            path.lineTo(rect.right, rect.bottom)
            path.lineTo(rect.right, rect.top)
            path.close()
        }
        return path
    }

    private fun applyFold(canvas: Canvas, cx: Float, cy: Float, angle: Float) {
        camera.save()
        camera.rotateY(angle)
        camera.getMatrix(camMatrix)
        camMatrix.preTranslate(-cx, -cy)
        camMatrix.postTranslate(cx, cy)
        canvas.concat(camMatrix)
        camera.restore()
    }
}

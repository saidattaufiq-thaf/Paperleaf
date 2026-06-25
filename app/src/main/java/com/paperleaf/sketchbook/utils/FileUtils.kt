package com.paperleaf.sketchbook.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileUtils {

    private fun getBooksDir(context: Context): File =
        File(context.filesDir, "paperleaf/books").also { it.mkdirs() }

    fun getSpreadFile(context: Context, bookId: Long, spreadIndex: Int): File =
        File(getBookDir(context, bookId), "spread_$spreadIndex.png")

    fun getBookDir(context: Context, bookId: Long): File =
        File(getBooksDir(context), bookId.toString()).also { it.mkdirs() }

    fun getPageFile(context: Context, bookId: Long, pageNumber: Int): File =
        File(getBookDir(context, bookId), "page_$pageNumber.png")

    fun saveBitmap(bitmap: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.isSuccess

    fun loadBitmap(file: File): Bitmap? =
        if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        else null

    fun deleteBookFiles(context: Context, bookId: Long) {
        getBookDir(context, bookId).deleteRecursively()
    }

    fun getTempExportDir(context: Context): File =
        File(context.cacheDir, "export").also { it.mkdirs() }

    fun generatePdf(context: Context, bookId: Long, pages: List<File>): File? = runCatching {
        val doc = PdfDocument()
        for ((i, file) in pages.withIndex()) {
            val bmp = loadBitmap(file) ?: continue
            val pageInfo = PdfDocument.PageInfo.Builder(
                bmp.width, bmp.height, i + 1
            ).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
        }
        val pdfFile = File(getTempExportDir(context), "book_${bookId}_pages.pdf")
        FileOutputStream(pdfFile).use { doc.writeTo(it) }
        doc.close()
        pdfFile
    }.getOrNull()

    fun generatePlf(context: Context, files: List<File>, name: String): File? = runCatching {
        val plfFile = File(getTempExportDir(context), "$name.plf")
        ZipOutputStream(FileOutputStream(plfFile)).use { zos ->
            for ((i, file) in files.withIndex()) {
                val bmp = loadBitmap(file) ?: continue
                val entry = ZipEntry("page_${i + 1}.png")
                zos.putNextEntry(entry)
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
                zos.closeEntry()
            }
        }
        plfFile
    }.getOrNull()

    fun importPlf(context: Context, plfUri: Uri, destDir: File): Int {
        destDir.mkdirs()
        var count = 0
        val resolver = context.contentResolver
        runCatching {
            resolver.openInputStream(plfUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".png")) {
                            val outFile = File(destDir, "page_${++count}.png")
                            FileOutputStream(outFile).use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
        return count
    }

    fun getShareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    fun saveBitmapAsJpeg(bitmap: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }.isSuccess
}
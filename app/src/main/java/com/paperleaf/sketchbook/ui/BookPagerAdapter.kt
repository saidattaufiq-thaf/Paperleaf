package com.paperleaf.sketchbook.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.paperleaf.sketchbook.databinding.ItemBookPagerBinding
import com.paperleaf.sketchbook.model.Book
import com.paperleaf.sketchbook.utils.FileUtils

class BookPagerAdapter(
    private val onBookClick: (Book, View) -> Unit
) : RecyclerView.Adapter<BookPagerAdapter.VH>() {

    private var books = listOf<Book>()

    fun submitList(list: List<Book>) { books = list; notifyDataSetChanged() }
    fun getBook(pos: Int): Book?     = books.getOrNull(pos)
    fun getCount(): Int              = books.size

    override fun getItemCount() = books.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBookPagerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(books[position])

    inner class VH(private val b: ItemBookPagerBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(book: Book) {
    val dp     = b.root.resources.displayMetrics.density
    val smallR = 4f * dp
    val bigR   = 24f * dp

    // Outer container — sudut kiri buku membulat, spine ter-clip otomatis
    val outerShape = ShapeAppearanceModel.builder()
        .setTopLeftCornerSize(smallR)
        .setBottomLeftCornerSize(smallR)
        .setTopRightCornerSize(bigR)
        .setBottomRightCornerSize(bigR)
        .build()

    val outerDrawable = MaterialShapeDrawable(outerShape).apply {
        fillColor = ColorStateList.valueOf(book.coverColor)
    }
    b.bookOuter.apply {
        background = outerDrawable
        clipToOutline = true
    }

    // Shadow — offset ke kanan & bawah agar efek bayangan muncul
    b.bookShadow.translationX = 8f * dp
    b.bookShadow.translationY = 8f * dp

    // Body — background sendiri dgn semua sudut (terminasi visual body)
    val bodyShape = ShapeAppearanceModel.builder()
        .setTopLeftCornerSize(smallR)
        .setBottomLeftCornerSize(smallR)
        .setTopRightCornerSize(bigR)
        .setBottomRightCornerSize(bigR)
        .build()

    val bodyDrawable = MaterialShapeDrawable(bodyShape).apply {
        fillColor = ColorStateList.valueOf(book.coverColor)
    }
    b.bookBody.background = bodyDrawable

    // Spine — warna lebih gelap, sudut diklip oleh outer container
    b.bookSpine.setBackgroundColor(darken(book.coverColor, 0.75f))

    // Cover — hanya sudut kanan atas membulat (bawah lurus, tersambung body)
    val coverShape = ShapeAppearanceModel.builder()
        .setTopLeftCornerSize(0f)
        .setBottomLeftCornerSize(0f)
        .setTopRightCornerSize(bigR)
        .setBottomRightCornerSize(0f)
        .build()

    val coverDrawable = MaterialShapeDrawable(coverShape).apply {
        fillColor = ColorStateList.valueOf(book.coverColor)
    }
    b.bookCover.background = coverDrawable

    // Thumbnail TIDAK ditampilkan — cover tetap bersih
    b.ivPagePreview.visibility = android.view.View.GONE

    b.root.setOnClickListener { onBookClick(book, b.root) }
}

        private fun darken(color: Int, factor: Float) = Color.argb(
            Color.alpha(color),
            (Color.red(color)   * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color)  * factor).toInt()
        )
    }
}

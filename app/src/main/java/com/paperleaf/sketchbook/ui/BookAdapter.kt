package com.paperleaf.sketchbook.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.paperleaf.sketchbook.databinding.ItemBookBinding
import com.paperleaf.sketchbook.model.Book

class BookAdapter(
    private val onBookClick: (Book, View) -> Unit,
    private val onBookLongClick: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(book: Book) {
            val dp = b.root.resources.displayMetrics.density
            val smallR = 3f * dp
            val bigR = 18f * dp

            b.tvTitle.text = book.title.ifEmpty { "(tanpa judul)" }
            b.tvTitle.visibility = View.VISIBLE
            b.tvPageCount.text = "${book.pageCount} halaman"

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
            b.bookBody.clipToOutline = true

            b.bookSpine.setBackgroundColor(darken(book.coverColor, 0.75f))

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

            b.bookShadow.translationX = 6f * dp
            b.bookShadow.translationY = 6f * dp

            b.root.setOnClickListener { onBookClick(book, b.root) }
            b.root.setOnLongClickListener { onBookLongClick(book); true }
        }

        private fun darken(color: Int, factor: Float) = Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(o: Book, n: Book) = o.id == n.id
            override fun areContentsTheSame(o: Book, n: Book) = o == n
        }
    }
}

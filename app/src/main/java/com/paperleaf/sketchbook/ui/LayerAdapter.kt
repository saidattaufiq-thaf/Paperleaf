package com.paperleaf.sketchbook.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.paperleaf.sketchbook.R
import com.paperleaf.sketchbook.databinding.LayerItemBinding
import com.paperleaf.sketchbook.utils.TransitionHelper
import com.paperleaf.sketchbook.view.DrawingView

class LayerAdapter(
    private val density: Float,
    private val isDeepOcean: Boolean,
    private val isMidnight: Boolean,
    private val onEyeTap: (Int) -> Unit,
    private val onLockTap: (Int) -> Unit,
    private val onDuplicateTap: (Int) -> Unit,
    private val onThumbTap: (Int) -> Unit,
    private val onRename: (Int, String) -> Unit,
    private val onCanvasTap: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<LayerAdapter.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_CANVAS = 0
        const val VIEW_TYPE_LAYER = 1
    }

    private val items = mutableListOf<LayerItem>()

    sealed class LayerItem {
        data object Canvas : LayerItem()
        data class LayerData(val layer: DrawingView.ImageLayer) : LayerItem()
    }

    var layers: List<DrawingView.ImageLayer> = emptyList()
        set(v) {
            field = v
            rebuildItems()
        }

    var canvasBitmap: Bitmap? = null
        set(v) {
            field = v
            notifyItemChanged(0)
        }

    private fun rebuildItems() {
        items.clear()
        items.add(LayerItem.Canvas)
        layers.forEach { items.add(LayerItem.LayerData(it)) }
        notifyDataSetChanged()
    }

    fun moveLayerItem(fromAdapterPos: Int, toAdapterPos: Int) {
        val fromLayerIdx = fromAdapterPos - 1
        val toLayerIdx = toAdapterPos - 1
        if (fromLayerIdx < 0 || toLayerIdx < 0) return

        val item = items.removeAt(fromAdapterPos)
        items.add(toAdapterPos, item)
        notifyItemMoved(fromAdapterPos, toAdapterPos)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is LayerItem.Canvas -> VIEW_TYPE_CANVAS
            is LayerItem.LayerData -> VIEW_TYPE_LAYER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        when (viewType) {
            VIEW_TYPE_LAYER -> {
                val binding = LayerItemBinding.inflate(inflater, parent, false)
                return LayerViewHolder(binding)
            }
            VIEW_TYPE_CANVAS -> {
                val binding = LayerItemBinding.inflate(inflater, parent, false)
                return CanvasViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (holder) {
            is LayerViewHolder -> {
                val layer = (items[position] as LayerItem.LayerData).layer
                holder.bind(layer, position)
            }
            is CanvasViewHolder -> {
                holder.bind()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun getLayerAtAdapterPosition(pos: Int): DrawingView.ImageLayer? {
        return (items.getOrNull(pos) as? LayerItem.LayerData)?.layer
    }

    abstract class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class CanvasViewHolder(
        private val binding: LayerItemBinding
    ) : ViewHolder(binding.root) {
        private var alignmentFixed = false

        fun bind() {
            val card = binding.cardRoot
            card.setCardBackgroundColor(Color.TRANSPARENT)
            card.strokeWidth = (1 * density).toInt()
            card.strokeColor = Color.parseColor("#33FFFFFF")
            card.setOnClickListener { onCanvasTap() }

            binding.iconRow.visibility = View.GONE
            binding.maskingIndicator.visibility = View.GONE

            if (!alignmentFixed) {
                val innerLayout = binding.thumbnail.parent as? LinearLayout
                if (innerLayout != null) {
                    val thumbIndex = innerLayout.indexOfChild(binding.thumbnail)
                    innerLayout.addView(View(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (14 * density).toInt()
                        )
                    }, thumbIndex)
                }
                alignmentFixed = true
            }

            val thumbSize = (52 * density).toInt()
            binding.thumbnail.apply {
                layoutParams.apply {
                    width = thumbSize
                    height = thumbSize
                }
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 6f * density
                }
                clipToOutline = true
                canvasBitmap?.let { setImageBitmap(it) }
                setOnClickListener { onCanvasTap() }
            }

            binding.layerName.apply {
                text = "Canvas"
                setTextColor(Color.parseColor("#AAAAAA"))
                setOnClickListener(null)
            }
        }
    }

    inner class LayerViewHolder(
        private val binding: LayerItemBinding
    ) : ViewHolder(binding.root) {

        private var layerPosition = -1

        fun bind(layer: DrawingView.ImageLayer, position: Int) {
            layerPosition = position
            val layerIdx = position - 1

            val card = binding.cardRoot
            val selectedBg = if (isMidnight) Color.parseColor("#1B3E98") else Color.parseColor("#3CBEC3")
            card.setCardBackgroundColor(
                if (layer.isSelected) selectedBg
                else Color.parseColor("#33FFFFFF")
            )
            card.strokeWidth = 0
            card.setOnClickListener(null)

            // Icon row
            binding.iconRow.visibility = View.VISIBLE

            // Eye
            binding.eyeIcon.apply {
                setImageResource(if (layer.isVisible) R.drawable.eye else R.drawable.eye_closed)
                setColorFilter(if (layer.isVisible) Color.WHITE else Color.parseColor("#888888"))
                setOnClickListener { onEyeTap(layerIdx) }
            }

            // Duplicate
            binding.dupIcon.apply {
                setImageResource(R.drawable.copy)
                setColorFilter(Color.WHITE)
                setOnClickListener { onDuplicateTap(layerIdx) }
            }

            // Lock
            binding.lockIcon.apply {
                setImageResource(if (layer.isLocked) R.drawable.locked else R.drawable.unlock)
                setColorFilter(
                    if (layer.isLocked) Color.parseColor("#FFD54F")
                    else Color.parseColor("#AAAAAA")
                )
                setOnClickListener { onLockTap(layerIdx) }
            }

            // Thumbnail
            val thumbSize = if (layer.isMasking) (44 * density).toInt() else (52 * density).toInt()
            binding.thumbnail.apply {
                layoutParams.apply {
                    width = thumbSize
                    height = thumbSize
                }
                setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#555555"))
                    cornerRadius = 6f * density
                }
                clipToOutline = true
                setImageBitmap(layer.bitmap)
                setOnClickListener { onThumbTap(layerIdx) }
            }

            // Masking indicator
            binding.maskingIndicator.visibility =
                if (layer.isMasking) View.VISIBLE else View.GONE
            binding.maskingIndicator.layoutParams.width = thumbSize

            // Name
            binding.layerName.apply {
                text = layer.name
                setTextColor(
                    if (layer.isSelected) Color.WHITE
                    else Color.parseColor("#AAAAAA")
                )
                setOnClickListener {
                    showRenameDialog(layer.name) { newName ->
                        onRename(layerIdx, newName)
                    }
                }
            }

            // Long press for drag (root + all children)
            val dragListener = View.OnLongClickListener {
                this@LayerViewHolder.itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                onStartDrag(this@LayerViewHolder)
                true
            }
            binding.root.setOnLongClickListener(dragListener)
            binding.thumbnail.setOnLongClickListener(dragListener)
            binding.eyeIcon.setOnLongClickListener(dragListener)
            binding.dupIcon.setOnLongClickListener(dragListener)
            binding.lockIcon.setOnLongClickListener(dragListener)
            binding.layerName.setOnLongClickListener(dragListener)
        }

        private fun showRenameDialog(currentName: String, onRename: (String) -> Unit) {
            binding.root.context.let { ctx ->
                val dp = density
                val dialog = android.app.Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
                val root = android.widget.FrameLayout(ctx).apply {
                    setBackgroundColor(Color.parseColor("#99000000"))
                    setOnClickListener { dialog.dismiss() }
                }
                val card = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        val bg = if (isDeepOcean) Color.parseColor("#124260") else Color.parseColor("#1E1E1E")
                        setColor(bg)
                        cornerRadius = 16f * dp
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        (280 * dp).toInt(),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
                    setOnClickListener { }
                }
                card.addView(android.widget.TextView(ctx).apply {
                    text = "Rename Layer"
                    textSize = 15f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, (12 * dp).toInt())
                })
                val inputEditText = android.widget.EditText(ctx)
                inputEditText.setText(currentName)
                inputEditText.selectAll()
                inputEditText.setHintTextColor(Color.parseColor("#666666"))
                inputEditText.setTextColor(Color.parseColor("#FFFFFF"))
                inputEditText.textSize = 14f
                val inputBg = GradientDrawable()
                inputBg.setColor(if (isDeepOcean) Color.parseColor("#1A6B77") else Color.parseColor("#2C2C2C"))
                inputBg.cornerRadius = 8f * dp
                inputEditText.background = inputBg
                inputEditText.setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                card.addView(inputEditText)

                card.addView(android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()
                    ).also { it.topMargin = (16 * dp).toInt() }
                    addView(android.widget.TextView(ctx).apply {
                        text = "Cancel"
                        textSize = 14f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        setOnClickListener { dialog.dismiss() }
                    })
                    addView(android.widget.TextView(ctx).apply {
                        text = "OK"
                        textSize = 14f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        setOnClickListener {
                            val newName = inputEditText.text.toString().trim()
                            if (newName.isNotEmpty()) onRename(newName)
                            dialog.dismiss()
                        }
                    })
                })
                root.addView(card)
                dialog.setContentView(root)
                dialog.window?.apply {
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
                TransitionHelper.morphDialog(dialog)
                dialog.show()
                inputEditText.requestFocus()
                dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }
        }
    }
}

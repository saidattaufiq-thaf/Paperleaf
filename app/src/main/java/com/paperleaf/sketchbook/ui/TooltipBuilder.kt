package com.paperleaf.sketchbook.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.paperleaf.sketchbook.R

class TooltipBuilder(private val context: Context) {

    private val items = mutableListOf<TooltipItem>()
    private var backgroundColor: Int = TooltipPopup.getThemeBgColor(context)
    private var arrowPos: BubbleDrawable.ArrowPosition = BubbleDrawable.ArrowPosition.BOTTOM_CENTER
    private var width: Int = 0
    private var title: String? = null

    sealed class TooltipItem {
        data class IconText(val iconRes: Int, val label: String, val onClick: () -> Unit) : TooltipItem()
        data class IconOnly(val iconRes: Int, val onClick: () -> Unit) : TooltipItem()
        data class Slider(val min: Int = 0, val max: Int = 100, val progress: Int = 50,
                         val label: String = "", val onProgress: (Int) -> Unit) : TooltipItem()
        data class Divider(val height: Int = 1, val color: Int = Color.parseColor("#20000000")) : TooltipItem()
        data class Custom(val view: View) : TooltipItem()
    }

    fun title(text: String) = apply { this.title = text }

    fun iconText(iconRes: Int, label: String, onClick: () -> Unit) = apply {
        items.add(TooltipItem.IconText(iconRes, label, onClick))
    }

    fun iconOnly(iconRes: Int, onClick: () -> Unit) = apply {
        items.add(TooltipItem.IconOnly(iconRes, onClick))
    }

    fun slider(min: Int = 0, max: Int = 100, progress: Int = 50,
               label: String = "", onProgress: (Int) -> Unit) = apply {
        items.add(TooltipItem.Slider(min, max, progress, label, onProgress))
    }

    fun divider(height: Int = 1, color: Int = Color.parseColor("#20000000")) = apply {
        items.add(TooltipItem.Divider(height, color))
    }

    fun custom(view: View) = apply {
        items.add(TooltipItem.Custom(view))
    }

    fun arrow(pos: BubbleDrawable.ArrowPosition) = apply { this.arrowPos = pos }

    fun bgColor(color: Int) = apply { this.backgroundColor = color }

    fun maxWidth(px: Int) = apply { this.width = px }

    fun build(): TooltipPopup {
        val dp = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (width > 0) {
                layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                layoutParams = LinearLayout.LayoutParams(
                    (200 * dp).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        title?.let { t ->
            container.addView(TextView(context).apply {
                text = t
                textSize = 14f
                setTextColor(if (isDarkBg()) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
            })
        }

        items.forEach { item ->
            when (item) {
                is TooltipItem.IconText -> container.addView(createIconTextRow(item, dp))
                is TooltipItem.IconOnly -> container.addView(createIconOnlyRow(item, dp))
                is TooltipItem.Slider -> container.addView(createSliderRow(item, dp))
                is TooltipItem.Divider -> container.addView(createDivider(item, dp))
                is TooltipItem.Custom -> container.addView(item.view)
            }
        }

        return TooltipPopup(
            ctx = context,
            content = container,
            arrowPosition = arrowPos,
            backgroundColor = backgroundColor
        )
    }

    private fun createIconTextRow(item: TooltipItem.IconText, dp: Float): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = createRippleBg()
            setOnClickListener { item.onClick() }

            addView(ImageView(context).apply {
                setImageResource(item.iconRes)
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    (28 * dp).toInt(), (28 * dp).toInt()
                ).apply { marginEnd = (10 * dp).toInt() }
                if (!isDarkBg()) setColorFilter(Color.parseColor("#333333"))
            })

            addView(TextView(context).apply {
                text = item.label
                textSize = 15f
                setTextColor(if (isDarkBg()) Color.WHITE else Color.parseColor("#222222"))
            })
        }
    }

    private fun createIconOnlyRow(item: TooltipItem.IconOnly, dp: Float): View {
        return FrameLayout(context).apply {
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            addView(ImageButton(context).apply {
                setImageResource(item.iconRes)
                background = createRippleBg()
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    (40 * dp).toInt(), (40 * dp).toInt()
                ).apply { gravity = Gravity.CENTER }
                if (!isDarkBg()) setColorFilter(Color.parseColor("#333333"))
                setOnClickListener { item.onClick() }
            })
        }
    }

    private fun createSliderRow(item: TooltipItem.Slider, dp: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        if (item.label.isNotEmpty()) {
            row.addView(TextView(context).apply {
                text = item.label
                textSize = 12f
                setTextColor(if (isDarkBg()) Color.parseColor("#888888") else Color.parseColor("#666666"))
                setPadding(0, 0, 0, (6 * dp).toInt())
            })
        }

        row.addView(SeekBar(context).apply {
            max = item.max
            progress = item.progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) item.onProgress(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        return row
    }

    private fun createDivider(item: TooltipItem.Divider, dp: Float): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (item.height * dp).toInt()
            ).apply {
                setMargins((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            }
            setBackgroundColor(item.color)
        }
    }

    private fun createRippleBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }
    }

    private fun isDarkBg(): Boolean = backgroundColor == Color.parseColor("#2C2C2C")
}

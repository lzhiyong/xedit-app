/*
 * Copyright © 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.editor.app.view

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import x.editor.app.R
import com.google.android.material.R as MaterialR

/**
 * A list to pick one row from, in a bottom sheet: the outline of a document, the code
 * actions at the cursor, the references of a symbol. Each row has a badge in the color of
 * its kind (a letter, or an icon), a title and a subtitle, something on the right (a line
 * number, a tag), and the guides of its depth in a tree; rows can be grouped under
 * headers, and one can be marked as the current one (shown, and scrolled to). A long list
 * gets a search field: what is typed is matched against the titles (also inside them),
 * the matching part in bold, the tree flattened while searching.
 */
class PickerSheet(private val activity: Activity) {

    /** A row of the list. */
    sealed class Row {
        /** The name of a group of the items after it. */
        data class Header(val text: CharSequence) : Row()

        /**
         * One item: [badge] (a letter or two) or [icon] in [color], [depth] in a tree,
         * [current] (the one the cursor is in: marked and scrolled to), [tag] (whatever the
         * caller needs back).
         */
        data class Item(
            val title: CharSequence,
            val subtitle: CharSequence? = null,
            val trailing: CharSequence? = null,
            val badge: String = "",
            val icon: Int = 0,
            val color: Int = 0,
            val depth: Int = 0,
            val current: Boolean = false,
            val monospaceTitle: Boolean = false,
            /** Shown in the accent color on the right, as a tag (`preferred`), instead of [trailing]. */
            val tag: CharSequence? = null,
            val data: Any? = null,
        ) : Row()
    }

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_picker, null)
    private val dialog = BottomSheetDialog(activity)
    private val titleView = view.findViewById<TextView>(R.id.picker_title)
    private val iconView = view.findViewById<ImageView>(R.id.picker_icon)
    private val countView = view.findViewById<TextView>(R.id.picker_count)
    private val searchLayout = view.findViewById<TextInputLayout>(R.id.picker_search_layout)
    private val searchField = view.findViewById<TextInputEditText>(R.id.picker_search)
    private val list = view.findViewById<RecyclerView>(R.id.picker_list)
    private val empty = view.findViewById<TextView>(R.id.picker_empty)

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(activity, attr, fallback)

    private val onSurface = color(MaterialR.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, 0xFFA0A0A0.toInt())
    private val primary = color(androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())
    private val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer, 0xFF3D4A5C.toInt())
    private val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer, 0xFFE6E6E6.toInt())
    private val outlineVariant = color(MaterialR.attr.colorOutlineVariant, 0xFF44474E.toInt())
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val mono: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    private var rows: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private var query = ""
    private var onPick: ((Row.Item) -> Unit)? = null

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size
        override fun getItemViewType(position: Int) = if (shown[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(inflater.inflate(R.layout.item_picker_header, parent, false))
            else ItemHolder(inflater.inflate(R.layout.item_picker_row, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = shown[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Item -> (holder as ItemHolder).bind(row)
            }
        }
    }

    /**
     * Shows [rows] under [title] (with [icon]); [count]: the pill by the title (the number
     * of items if null). [searchable]: a search field, for a long list. A tap on an item
     * closes the sheet and calls [onPick] with it.
     */
    fun show(
        title: CharSequence,
        rows: List<Row>,
        icon: Int = 0,
        count: CharSequence? = null,
        searchable: Boolean = false,
        onPick: (Row.Item) -> Unit,
    ) {
        this.rows = rows
        this.onPick = onPick
        titleView.text = title
        iconView.isVisible = icon != 0
        if (icon != 0) iconView.setImageResource(icon)
        countView.text = count ?: rows.count { it is Row.Item }.toString()
        countView.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(secondaryContainer)
        }
        countView.setTextColor(onSecondaryContainer)

        searchLayout.isVisible = searchable
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        filter("")

        dialog.setContentView(view)
        dialog.behavior.apply {
            // over half of the screen at first, all of it pulled up
            peekHeight = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
            state = BottomSheetBehavior.STATE_COLLAPSED
            skipCollapsed = false
        }
        dialog.show()
        // the current one in sight, a few rows above it shown too
        val current = shown.indexOfFirst { it is Row.Item && it.current }
        if (current > 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(current, dp(96))
    }

    fun dismiss() = dialog.dismiss()

    // the rows whose titles have [text] in them, the tree flattened; all of them for nothing
    private fun filter(text: String) {
        query = text.trim()
        shown = if (query.isEmpty()) rows else rows.filterIsInstance<Row.Item>()
            .filter { it.title.toString().contains(query, ignoreCase = true) }
            .sortedBy { if (it.title.toString().startsWith(query, ignoreCase = true)) 0 else 1 }
        adapter.notifyDataSetChanged()
        empty.isVisible = shown.isEmpty()
        list.isVisible = shown.isNotEmpty()
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text = view as TextView
        fun bind(row: Row.Header) {
            text.text = row.text
        }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val guides = view.findViewById<PickerGuidesView>(R.id.row_guides)
        private val badge = view.findViewById<View>(R.id.row_badge)
        private val badgeText = view.findViewById<TextView>(R.id.row_badge_text)
        private val badgeIcon = view.findViewById<ImageView>(R.id.row_badge_icon)
        private val title = view.findViewById<TextView>(R.id.row_title)
        private val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
        private val trailing = view.findViewById<TextView>(R.id.row_trailing)

        fun bind(row: Row.Item) {
            val depth = if (query.isEmpty()) row.depth else 0
            guides.set(depth, dp(18), outlineVariant)
            guides.layoutParams = guides.layoutParams.apply { width = depth * dp(18) }

            val tint = if (row.color != 0) row.color else primary
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(ColorUtils.setAlphaComponent(tint, 0x33))
            }
            badgeIcon.isVisible = row.icon != 0
            badgeText.isVisible = row.icon == 0
            if (row.icon != 0) {
                badgeIcon.setImageResource(row.icon)
                badgeIcon.imageTintList = ColorStateList.valueOf(tint)
            } else {
                badgeText.text = row.badge
                badgeText.setTextColor(tint)
                badgeText.typeface = Typeface.create(mono, Typeface.BOLD)
            }

            title.text = highlighted(row.title)
            title.typeface = if (row.monospaceTitle) mono else Typeface.DEFAULT
            title.setTextColor(onSurface)
            subtitle.isVisible = !row.subtitle.isNullOrEmpty()
            subtitle.text = row.subtitle

            when {
                row.tag != null -> {
                    trailing.isVisible = true
                    trailing.text = row.tag
                    trailing.typeface = Typeface.DEFAULT_BOLD
                    trailing.setTextColor(onSecondaryContainer)
                    trailing.setPadding(dp(8), dp(2), dp(8), dp(2))
                    trailing.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(secondaryContainer)
                    }
                }
                row.trailing != null -> {
                    trailing.isVisible = true
                    trailing.text = row.trailing
                    trailing.typeface = mono
                    trailing.setTextColor(onSurfaceVariant)
                    trailing.setPadding(0, 0, 0, 0)
                    trailing.background = null
                }
                else -> trailing.isVisible = false
            }

            // the current one on a tinted, rounded ground; all with a rounded ripple
            val shape = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (row.current) ColorUtils.setAlphaComponent(secondaryContainer, 0xCC) else 0)
            }
            val mask = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            itemView.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x1F)), shape, mask)
            itemView.setOnClickListener {
                dialog.dismiss()
                onPick?.invoke(row)
            }
        }

        // what was searched for, in bold and the accent color
        private fun highlighted(text: CharSequence): CharSequence {
            if (query.isEmpty()) return text
            val at = text.toString().indexOf(query, ignoreCase = true)
            if (at < 0) return text
            return SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(primary), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

/** The guides of a row's depth in a tree (PickerSheet): a thin line for each level above it. */
class PickerGuidesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = resources.displayMetrics.density }
    private var depth = 0
    private var step = 0

    fun set(depth: Int, step: Int, color: Int) {
        this.depth = depth
        this.step = step
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (level in 0 until depth) {
            val x = level * step + step / 2f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
    }
}

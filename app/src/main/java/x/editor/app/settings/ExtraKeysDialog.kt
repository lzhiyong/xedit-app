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

package x.editor.app.settings

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import x.editor.app.R
import x.editor.app.recyclerview.SwipeDragCallback

import com.google.android.material.R as MaterialR

/**
 * The symbols of the row of extra keys over the keyboard (see ExtraKeysController), in a
 * dialog: a grid of tiles in the order of the row (a tap takes one away, held it can be
 * dragged elsewhere) and a field to add more, several at once. Nothing is kept until OK;
 * "default" puts the default symbols back into the grid. [onSaved] is called once they
 * are stored.
 */
class ExtraKeysDialog(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val onSaved: () -> Unit,
) {

    private val keys = ArrayList<String>()

    private val view: View = inflater.inflate(R.layout.dialog_extra_keys, null)
    private val grid: RecyclerView = view.findViewById(R.id.extra_keys_grid)
    private val empty: TextView = view.findViewById(R.id.extra_keys_empty)
    private val count: TextView = view.findViewById(R.id.extra_keys_count)
    private val field: TextInputLayout = view.findViewById(R.id.extra_keys_input_layout)
    private val input: TextInputEditText = view.findViewById(R.id.extra_keys_input)

    private val adapter = TileAdapter()

    fun show() {
        keys.addAll(EditorSettings.getExtraKeys(context))

        grid.layoutManager = GridLayoutManager(context, SPAN_COUNT)
        grid.adapter = adapter
        // held: dragged anywhere in the grid; never swiped away (a tap removes)
        val dragDirections = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        val callback = SwipeDragCallback(dragDirections, 0).apply {
            onItemMoved = { from, to ->
                keys.add(to, keys.removeAt(from))
                adapter.notifyItemMoved(from, to)
                true
            }
            // the tiles have no fill: the one picked up gets one while it is dragged
            onGestureStarted = { holder ->
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                (holder.itemView as? MaterialCardView)?.setCardBackgroundColor(
                    MaterialColors.getColor(holder.itemView, MaterialR.attr.colorSecondaryContainer)
                )
                holder.itemView.animate().translationZ(dp(6f)).scaleX(1.08f).scaleY(1.08f)
                    .setDuration(LIFT_MILLIS).start()
            }
            onGestureEnded = { holder ->
                (holder.itemView as? MaterialCardView)?.setCardBackgroundColor(Color.TRANSPARENT)
                holder.itemView.animate().translationZ(0f).scaleX(1f).scaleY(1f)
                    .setDuration(LIFT_MILLIS).start()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(grid)

        field.setEndIconOnClickListener { addFromField() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                addFromField()
                true
            } else {
                false
            }
        }
        input.doAfterTextChanged { field.error = null }
        update()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_editor_extra_keys_title)
            .setIcon(R.drawable.ic_keyboard)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pref_editor_extra_keys_reset, null)
            .create()
        dialog.setOnShowListener {
            // the buttons are taken over: a wrong entry in the field keeps the dialog open,
            // and "default" only fills the grid
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                // what is still in the field counts as well
                if (!input.text.isNullOrBlank() && !addFromField()) return@setOnClickListener
                // the default ones are not stored: a later default is taken then
                EditorSettings.setExtraKeys(
                    context,
                    if (keys == EditorSettings.DEFAULT_EXTRA_KEYS) null else keys.toList()
                )
                onSaved()
                dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                keys.clear()
                keys.addAll(EditorSettings.DEFAULT_EXTRA_KEYS)
                @Suppress("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
                field.error = null
                update()
            }
        }
        dialog.show()
    }

    // The symbols in the field, separated by white space, at the end of the grid. Those
    // already there are left out; returns false (and says why under the field) if there
    // is nothing new, one is too long, or there would be too many.
    private fun addFromField(): Boolean {
        val tokens = input.text?.toString().orEmpty()
            .split(' ', '\t', '\n', '\r')
            .filter { it.isNotEmpty() }
            .distinct()
        if (tokens.isEmpty()) return true

        val tooLong = tokens.filter { it.length > EditorSettings.EXTRA_KEY_MAX_LENGTH }
        if (tooLong.isNotEmpty()) {
            field.error = context.getString(
                R.string.pref_editor_extra_keys_too_long,
                EditorSettings.EXTRA_KEY_MAX_LENGTH,
                tooLong.joinToString(" ")
            )
            return false
        }
        val fresh = tokens.filter { it !in keys }
        if (fresh.isEmpty()) {
            field.error = context.getString(R.string.pref_editor_extra_keys_duplicate, tokens.joinToString(" "))
            return false
        }
        if (keys.size + fresh.size > EditorSettings.EXTRA_KEYS_MAX) {
            field.error = context.getString(R.string.pref_editor_extra_keys_full, EditorSettings.EXTRA_KEYS_MAX)
            return false
        }

        val start = keys.size
        keys.addAll(fresh)
        adapter.notifyItemRangeInserted(start, fresh.size)
        input.setText("")
        field.error = null
        update()
        grid.post { grid.smoothScrollToPosition(keys.size - 1) }
        return true
    }

    private fun remove(position: Int) {
        if (position !in keys.indices) return
        keys.removeAt(position)
        adapter.notifyItemRemoved(position)
        update()
    }

    // the count, the grid as high as its rows (up to MAX_ROWS, then it scrolls), or
    // the words for none
    private fun update() {
        count.text = context.getString(
            R.string.pref_editor_extra_keys_count, keys.size, EditorSettings.EXTRA_KEYS_MAX
        )
        empty.isVisible = keys.isEmpty()
        grid.isVisible = keys.isNotEmpty()
        val rows = (keys.size + SPAN_COUNT - 1) / SPAN_COUNT
        val tile = context.resources.getDimensionPixelSize(R.dimen.extra_key_tile_height) +
            2 * context.resources.getDimensionPixelSize(R.dimen.extra_key_tile_gap)
        val height = rows.coerceAtMost(MAX_ROWS) * tile + grid.paddingTop + grid.paddingBottom
        // half a row more when it scrolls, so that it can be seen that it does
        val wanted = if (rows > MAX_ROWS) height + tile / 2 else height
        if (grid.layoutParams.height != wanted) {
            grid.updateLayoutParams<ViewGroup.LayoutParams> { this.height = wanted }
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private inner class TileAdapter : RecyclerView.Adapter<TileHolder>() {
        override fun getItemCount(): Int = keys.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder =
            TileHolder(inflater.inflate(R.layout.item_extra_key_tile, parent, false))

        override fun onBindViewHolder(holder: TileHolder, position: Int) = holder.bind(keys[position])
    }

    private inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tile_label)

        init {
            // the position when tapped: the one of bind time is wrong after a move
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) remove(position)
            }
        }

        fun bind(key: String) {
            label.text = key
            itemView.contentDescription = context.getString(R.string.pref_editor_extra_keys_remove, key)
        }
    }

    private companion object {
        const val SPAN_COUNT = 6
        // the default symbols fit without scrolling
        const val MAX_ROWS = 6
        const val LIFT_MILLIS = 120L
    }
}

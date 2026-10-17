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

package x.editor.app.preference

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.core.os.bundleOf
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton

import x.editor.app.R

/**
 * The dialog of [RadioListPreference]: a Material 3 dialog with a scrolling list of
 * radio buttons. A tap on an entry picks it and closes the dialog; the entry that
 * is chosen now is in view when it opens.
 *
 * The entries are read from the preference each time they are shown, so an entry
 * removed from the dialog, or one added while it is open, shows up at once.
 */
class RadioListPreferenceDialog : PreferenceDialogFragmentCompat() {

    // the entry tapped, -1 for none
    private var clickedIndex = -1

    private val listPreference: RadioListPreference
        get() = preference as RadioListPreference

    private var adapter: ChoiceAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clickedIndex = savedInstanceState?.getInt(STATE_CLICKED_INDEX, -1) ?: -1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CLICKED_INDEX, clickedIndex)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 8.dp, 0, 8.dp)
        }
        val choices = ChoiceAdapter().also { adapter = it }
        list.adapter = choices
        list.scrollToPosition(maxOf(0, choices.selectedIndex))

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(listPreference.dialogTitle ?: listPreference.title)
            .setIcon(listPreference.icon)
            .setView(list)
            .setNegativeButton(android.R.string.cancel, this)
        listPreference.extraAction?.let { action ->
            builder.setNeutralButton(action.label) { dialog, which ->
                // not a pick: the dialog closes as if cancelled, and the action runs
                onClick(dialog, which)
                action.run()
            }
        }
        return builder.create()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val preference = listPreference
        val values = preference.entryValues ?: return
        if (positiveResult && clickedIndex in values.indices) {
            val value = values[clickedIndex].toString()
            if (value != preference.value && preference.callChangeListener(value)) {
                preference.value = value
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private inner class ChoiceHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: MaterialRadioButton = view.findViewById(R.id.radio)
        val title: TextView = view.findViewById(R.id.title)
        val preview: TextView = view.findViewById(R.id.preview)
        val swatches: SwatchesView = view.findViewById(R.id.swatches)
        val remove: MaterialButton = view.findViewById(R.id.remove)
    }

    private inner class ChoiceAdapter : RecyclerView.Adapter<ChoiceHolder>() {

        private val entries: Array<CharSequence>
            get() = listPreference.entries ?: emptyArray()

        private val values: Array<CharSequence>
            get() = listPreference.entryValues ?: emptyArray()

        val selectedIndex: Int
            get() = listPreference.findIndexOfValue(listPreference.value)

        override fun getItemCount(): Int = minOf(entries.size, values.size)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChoiceHolder =
            ChoiceHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_radio_choice, parent, false)
            )

        override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
            val value = values[position].toString()
            val details = listPreference.entryDetails?.invoke(value)
            val selected = position == selectedIndex

            holder.title.text = entries[position]
            holder.radio.isChecked = selected

            holder.preview.visibility = if (details?.preview != null) View.VISIBLE else View.GONE
            holder.preview.text = details?.preview
            holder.preview.typeface = details?.previewTypeface

            val swatches = details?.swatches
            holder.swatches.visibility = if (swatches != null && swatches.isNotEmpty()) View.VISIBLE else View.GONE
            holder.swatches.colors = swatches ?: IntArray(0)

            val removable = details?.removable == true && listPreference.onRemoveEntry != null
            holder.remove.visibility = if (removable) View.VISIBLE else View.GONE
            holder.remove.setOnClickListener {
                listPreference.onRemoveEntry?.invoke(value)
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

            holder.itemView.isSelected = selected
            holder.itemView.contentDescription = entries[position]
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                clickedIndex = index
                holder.radio.isChecked = true
                // picked: as if OK was pressed
                val dialog = requireDialog()
                onClick(dialog, DialogInterface.BUTTON_POSITIVE)
                dialog.dismiss()
            }
        }
    }

    companion object {
        private const val STATE_CLICKED_INDEX = "RadioListPreferenceDialog.clickedIndex"

        // the argument PreferenceDialogFragmentCompat finds its preference by (its ARG_KEY)
        private const val ARG_PREFERENCE_KEY = "key"

        fun newInstance(key: String): RadioListPreferenceDialog =
            RadioListPreferenceDialog().apply { arguments = bundleOf(ARG_PREFERENCE_KEY to key) }
    }
}

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

import android.content.Context
import android.util.AttributeSet
import android.view.View

import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder

/**
 * A [ListPreference] whose choices drop down over its own row ([SimpleMenuPopup]),
 * instead of in a dialog: the "dark theme" list of the settings, as in Shizuku.
 * Everything else is a ListPreference: entries, entry values, summary provider,
 * the change listener (which can still refuse a value).
 */
class SimpleMenuPreference : ListPreference {

    // the constructors of ListPreference, so that its default style is found the same way
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    // the row the preference is bound to now, where the menu opens
    private var anchor: View? = null

    private var popup: SimpleMenuPopup? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        anchor = holder.itemView
    }

    override fun onClick() {
        val row = anchor
        val entries = entries
        if (row == null || !row.isAttachedToWindow || entries.isNullOrEmpty()) {
            // no row to open over (cannot happen for a tap): the dialog of ListPreference
            super.onClick()
            return
        }
        val menu = popup ?: SimpleMenuPopup(row.context).also { popup = it }
        menu.show(row, entries, findIndexOfValue(value)) { index ->
            val newValue = entryValues[index].toString()
            if (newValue != value && callChangeListener(newValue)) {
                value = newValue
            }
        }
    }

    override fun onDetached() {
        popup?.dismiss()
        popup = null
        anchor = null
        super.onDetached()
    }
}

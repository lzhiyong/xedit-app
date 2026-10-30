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
import android.graphics.Typeface
import android.util.AttributeSet

import androidx.preference.ListPreference

/**
 * A [ListPreference] whose choices are a list of radio buttons in a Material 3
 * dialog ([RadioListPreferenceDialog]): for lists that can grow long, such as the
 * highlight themes or the fonts, where a drop-down would not do.
 *
 * Each entry can show more than its name ([entryDetails]): a preview line (in a
 * typeface of its own, say the font it stands for), color swatches, and a button
 * to remove it ([onRemoveEntry]). The dialog can also have one more button
 * ([extraAction]), such as "import".
 */
class RadioListPreference : ListPreference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    /** What an entry shows under (and next to) its name. */
    class EntryDetails(
        val preview: CharSequence? = null,
        val previewTypeface: Typeface? = null,
        val swatches: IntArray? = null,
        val removable: Boolean = false
    )

    /** A button of the dialog besides cancel. */
    class ExtraAction(val label: CharSequence, val run: () -> Unit)

    /** The details of the entry with the value given; null for just the name. */
    var entryDetails: ((value: String) -> EntryDetails?)? = null

    /**
     * Removes the entry with the value given, and updates [setEntries] and
     * [setEntryValues] (and the value, if it was that one). The dialog shows the
     * entries as they are afterwards.
     */
    var onRemoveEntry: ((value: String) -> Unit)? = null

    var extraAction: ExtraAction? = null
}

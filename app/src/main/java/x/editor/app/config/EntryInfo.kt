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

package x.editor.app.config

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

import x.editor.app.R

/**
 * The one button at the foot of an info window (EntryProblemPopup), or of a status card: what
 * to do next. [danger]: in the colour of errors (Stop all). [dismisses]: the window closes as
 * it is tapped; not: it stays (what it does asks first, and may be cancelled).
 */
internal class PopupAction(
    val label: CharSequence,
    val danger: Boolean = false,
    val dismisses: Boolean = true,
    val run: () -> Unit,
)

/**
 * What the info window of an entry says (a tap on a row of any panel), always in the same
 * four parts, a part left out when it has nothing:
 *
 * ```
 * ● <name> · <state>                          ⧉  ✕
 * <now: one sentence of how it is>
 *
 * What to do
 * → <first step, a verb first, buttons in 「」>
 * → <second step>
 *
 * <details: key: value, a line each>
 *
 * <errors: Line N: …, each of them>
 *                                  [ action ]
 * ```
 *
 * [action] is the button of the first step (one at most: Edit, Install, …).
 */
internal class EntryInfo(
    val name: CharSequence,
    val state: CharSequence,
    val level: StateLevel,
    val now: CharSequence = "",
    val steps: List<CharSequence> = emptyList(),
    val details: List<CharSequence> = emptyList(),
    val errors: List<CharSequence> = emptyList(),
    val action: PopupAction? = null,
    val progress: Int = EntryProblemPopup.NO_PROGRESS,
) {
    /** The title: `<name> · <state>` (the name alone without a state). */
    fun title(): CharSequence = if (state.isEmpty()) name else "$name · $state"

    /** The text under the title: its parts, a blank line between them. */
    fun text(context: Context): CharSequence {
        val parts = ArrayList<CharSequence>()
        if (now.isNotEmpty()) parts += now
        if (steps.isNotEmpty()) {
            parts += SpannableStringBuilder().apply {
                append(context.getString(R.string.info_steps))
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (step in steps) append("\n→ ").append(step)
            }
        }
        if (details.isNotEmpty()) parts += join(details, "\n")
        if (errors.isNotEmpty()) {
            parts += SpannableStringBuilder(join(errors, "\n")).apply {
                setSpan(ForegroundColorSpan(StateLevel.BAD_COLOR), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return join(parts, "\n\n")
    }

    private fun join(parts: List<CharSequence>, separator: String): CharSequence = SpannableStringBuilder().apply {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(separator)
            append(part)
        }
    }
}

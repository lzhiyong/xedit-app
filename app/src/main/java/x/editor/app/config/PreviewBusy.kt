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

import android.app.Activity
import android.view.View
import android.widget.TextView

import androidx.lifecycle.LifecycleCoroutineScope

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What Preview says while it works, on the panels with a preview (FormattingSheet,
 * ThemeSheet), from the tap on it to its end:
 *
 *  - [title], of the preview, says what is being done (the bar of the preview goes on
 *    meanwhile: the panel's own);
 *  - the window of the panel ([popup]) says so over Preview ([anchor]: the preview under the
 *    button stays in sight), a ring turning, and then that it is done, closing by itself a
 *    moment later. Closed by the user meanwhile, it is not opened again: the title says it
 *    as well. What else the panel has to say of Preview goes there too ([say]).
 *
 * @param heading the title of the window
 */
internal class PreviewBusy(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val popup: EntryProblemPopup,
    private val anchor: View,
    private val title: TextView,
    private val heading: CharSequence,
) {

    // the title as it was before it began
    private var idleTitle: CharSequence = ""

    private var busy = false

    // the window closing by itself, after it said it is done
    private var closing: Job? = null

    /** A tap on Preview: [busyTitle] as the title, and [message] in the window, a ring turning. */
    fun begin(busyTitle: CharSequence, message: CharSequence) {
        keepWindow()
        if (!busy) {
            busy = true
            idleTitle = title.text
        }
        title.text = busyTitle
        popup.show(
            anchor, heading, message, StateLevel.ACTIVE.color(activity), EntryProblemPopup.INDETERMINATE,
            centered = true, above = true,
        )
    }

    /** [text] under [windowTitle] in the window, over Preview: why it was not done. */
    fun say(windowTitle: CharSequence, text: CharSequence, color: Int) {
        keepWindow()
        popup.show(anchor, windowTitle, text, color, centered = true, above = true)
    }

    /**
     * Done: the title is [doneTitle] (null: as it was), and the window, if it is still shown,
     * says [message] in [color]; good news goes by itself, anything else stays to be read.
     */
    fun finish(doneTitle: CharSequence?, message: CharSequence, color: Int = StateLevel.GOOD_COLOR) {
        reset()
        doneTitle?.let { title.text = it }
        if (!popup.isShowingBy(anchor)) return
        popup.show(anchor, heading, message, color, centered = true, above = true)
        if (color != StateLevel.GOOD_COLOR) return
        closing = scope.launch {
            delay(CLOSE_AFTER_MILLIS)
            if (popup.isShowingBy(anchor)) popup.dismiss()
        }
    }

    /** Given up (it was cancelled, or something else is said in the window now): as it was. */
    fun stop() {
        if (!busy) return
        reset()
        if (popup.isShowingBy(anchor)) popup.dismiss()
    }

    /** The window will say something else by [anchor]: it does not close by itself. */
    fun keepWindow() {
        closing?.cancel()
        closing = null
    }

    // the title back
    private fun reset() {
        if (!busy) return
        busy = false
        title.text = idleTitle
    }

    private companion object {
        // how long the window says it is done
        const val CLOSE_AFTER_MILLIS = 1800L
    }
}

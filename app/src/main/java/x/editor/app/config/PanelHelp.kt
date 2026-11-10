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
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View

import androidx.core.content.edit

import kotlinx.coroutines.CoroutineScope

import x.editor.app.AppSettings
import x.editor.app.R

/**
 * ⓘ by the title of a panel of the developer tools: the same window as the info of an
 * entry (EntryProblemPopup), always three parts: what the panel is for ([what]), how it is
 * used ([how]: tap, ⋮, New), where its files are ([where]); and at its foot Export all
 * (ConfigExport: every panel's files in one zip, in /sdcard/Download).
 *
 * The first time a panel is opened ([id]: remembered once shown), it comes up by itself.
 */
internal class PanelHelp(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val popup: EntryProblemPopup,
    private val id: String,
    private val title: CharSequence,
    private val what: CharSequence,
    private val how: CharSequence,
    private val where: () -> String,
) {

    /**
     * [button] (ⓘ) shows it, by [anchor] (the title row), and again closes it. Shown at once
     * the first time the panel is opened, once the sheet has come up ([isShowing]: it still
     * is).
     */
    fun attach(button: View, anchor: View, isShowing: () -> Boolean) {
        button.setOnClickListener {
            if (popup.isShowingBy(anchor)) popup.dismiss() else show(anchor)
        }
        val preferences = AppSettings.preferences(activity)
        if (preferences.getBoolean(KEY_PREFIX + id, false)) return
        anchor.postDelayed({
            if (isShowing() && anchor.isAttachedToWindow && !popup.isShowing) {
                show(anchor)
                preferences.edit { putBoolean(KEY_PREFIX + id, true) }
            }
        }, FIRST_DELAY_MILLIS)
    }

    /** The help shown by [anchor]. */
    fun show(anchor: View) {
        val text = SpannableStringBuilder()
        fun part(heading: Int, body: CharSequence) {
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            text.append(activity.getString(heading))
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append('\n').append(body)
        }
        part(R.string.help_what, what)
        part(R.string.help_how, how)
        part(R.string.help_where, where())
        popup.show(
            anchor, title, text, StateLevel.ACTIVE.color(activity),
            action = PopupAction(activity.getString(R.string.config_export_all)) { ConfigExport.export(activity, scope) },
        )
    }

    private companion object {
        const val KEY_PREFIX = "devtools_help_seen_"

        // the sheet comes up first (the window would be by where the title was)
        const val FIRST_DELAY_MILLIS = 450L
    }
}

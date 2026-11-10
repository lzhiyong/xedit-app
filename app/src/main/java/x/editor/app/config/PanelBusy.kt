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
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.DrawableRes

import x.editor.app.R

/** What goes on, in a few words: "Saving clangd.json". */
internal fun OperationGate.Ticket.describe(context: Context): String = context.getString(
    when (op) {
        PanelOp.SAVE -> R.string.config_busy_save
        PanelOp.DELETE -> R.string.config_busy_delete
        PanelOp.UNINSTALL -> R.string.config_busy_uninstall
        PanelOp.REMOVE -> R.string.config_busy_remove
        PanelOp.STOP -> R.string.config_busy_stop
        PanelOp.RESTART -> R.string.config_busy_restart
        PanelOp.PREVIEW -> R.string.config_busy_preview
    },
    subject,
)

/** Says that what was asked for waits for [busy] (what goes on, in a few words). */
internal fun toastBusy(context: Context, busy: CharSequence) {
    Toast.makeText(context, context.getString(R.string.config_busy_wait, busy), Toast.LENGTH_SHORT).show()
}

/**
 * That an editor of a panel cannot be typed in now (view_editor_lock, over the editor): its
 * text under a tint, a badge in its corner saying why, and a padlock for the icon of its title
 * ([icon]; [idleIcon] when it is not locked). A drag on it still scrolls the text: what is
 * under it is only not to be changed. It comes and goes in a short fade, so that a write of a
 * few milliseconds is seen as a blink, not as a flash.
 */
internal class EditorLockView(root: View, private val icon: ImageView, @DrawableRes private val idleIcon: Int) {

    private val scrim = root.findViewById<View>(R.id.editor_lock_scrim)
    private val badge = root.findViewById<View>(R.id.editor_lock_badge)
    private val text = root.findViewById<TextView>(R.id.editor_lock_text)
    private var shown = false

    /** Locked, saying [message]; null: not locked. */
    fun set(message: CharSequence?) {
        if (message != null) text.text = message
        if ((message != null) == shown) return
        shown = message != null
        icon.setImageResource(if (shown) R.drawable.ic_lock else idleIcon)
        fade(scrim, shown, SCRIM_ALPHA)
        fade(badge, shown, 1f)
    }

    private fun fade(view: View, visible: Boolean, alpha: Float) {
        view.animate().cancel()
        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(alpha).setDuration(FADE_IN_MILLIS).start()
        } else {
            view.animate().alpha(0f).setDuration(FADE_OUT_MILLIS).withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private companion object {
        const val SCRIM_ALPHA = 0.12f
        const val FADE_IN_MILLIS = 90L
        const val FADE_OUT_MILLIS = 180L
    }
}

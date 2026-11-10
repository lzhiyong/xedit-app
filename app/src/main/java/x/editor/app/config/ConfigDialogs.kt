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
import android.content.DialogInterface

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import x.editor.app.R

/**
 * The dialogs of the panels of the developer tools, worded the same on every panel: the
 * first sentence says what will happen, the second what it touches; the button that loses
 * something is red.
 */
internal object ConfigDialogs {

    /**
     * Asks whether the changes not saved may be let go of: [message] says which file and
     * what then (switching to another, closing the panel). Keep editing / Discard (red).
     */
    fun confirmDiscard(activity: Activity, message: CharSequence, onDiscard: () -> Unit) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.config_discard_title)
            .setMessage(message)
            .setNegativeButton(R.string.config_discard_keep, null)
            .setPositiveButton(R.string.config_discard_confirm) { _, _ -> onDiscard() }
            .create()
        showRed(dialog)
    }

    /**
     * Asks before something is deleted or removed: [title] (`Delete clangd?`), [message]
     * (what goes, what it touches), Cancel / [confirm] (red).
     */
    fun confirmDelete(activity: Activity, title: CharSequence, message: CharSequence, confirm: CharSequence, onConfirm: () -> Unit) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirm) { _, _ -> onConfirm() }
            .create()
        showRed(dialog)
    }

    // shown, its positive button in the colour of what is lost
    private fun showRed(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(StateLevel.BAD_COLOR)
        }
        dialog.show()
    }
}

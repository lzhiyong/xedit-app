/*
 * Copyright © 2023 Github Lzhiyong
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
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.preference.ListPreferenceDialogFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MaterialListPreferenceDialog : ListPreferenceDialogFragmentCompat() {

    private var clickedButton = 0

    private var closedFromDismiss = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context: Context? = activity

        clickedButton = DialogInterface.BUTTON_NEGATIVE

        val builder = MaterialAlertDialogBuilder(requireActivity()).apply {
            setTitle(preference.dialogTitle)
            setIcon(preference.dialogIcon)
            setPositiveButton(preference.positiveButtonText, this@MaterialListPreferenceDialog)
            setNegativeButton(preference.negativeButtonText, this@MaterialListPreferenceDialog)
        }

        val contentView = context?.let { onCreateDialogView(it) }
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(preference.dialogMessage)
        }

        onPrepareDialogBuilder(builder)
        return builder.create()
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        clickedButton = which
    }

    override fun onDismiss(dialog: DialogInterface) {
        closedFromDismiss = true
        super.onDismiss(dialog)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (closedFromDismiss) {
            closedFromDismiss = false
            super.onDialogClosed(clickedButton == DialogInterface.BUTTON_POSITIVE)
        } else {
            super.onDialogClosed(positiveResult)
        }
    }
}


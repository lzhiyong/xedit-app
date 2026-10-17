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

package x.editor.app.console

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import x.editor.app.R
import java.io.File

/**
 * The command "compile and run" runs for files like [file] (by extension), as the user
 * wants it (a long press on the toolbar's run button): a field with the template, what it
 * comes to for [file] below it as it is typed. Save keeps it; Default goes back to the
 * app's (RunCommands.DEFAULTS).
 */
internal class RunSettingsDialog(private val activity: Activity, private val file: File) {

    fun show() {
        val extension = file.extension.lowercase()
        val density = activity.resources.displayMetrics.density
        val field = TextInputEditText(activity).apply {
            setText(RunCommands.template(activity, extension).orEmpty())
            typeface = Typeface.MONOSPACE
            textSize = 13f
            minLines = 2
        }
        val layout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.run_command_hint, if (extension.isEmpty()) file.name else ".$extension")
            helperText = activity.getString(R.string.run_command_help)
            addView(field)
        }
        // what the template comes to for this file
        val preview = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        fun update() {
            val template = field.text?.toString().orEmpty().trim()
            preview.text = if (template.isEmpty()) "" else activity.getString(R.string.run_command_preview, RunCommands.expand(template, file))
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()
        val padding = (20 * density).toInt()
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.run_settings_title)
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.run_command_default) { _, _ -> RunCommands.setTemplate(activity, extension, null) }
            .setPositiveButton(R.string.run_command_save) { _, _ ->
                RunCommands.setTemplate(activity, extension, field.text?.toString())
            }
            .show()
    }
}

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
import android.content.DialogInterface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import x.editor.app.R
import x.editor.app.console.RunCommands.Variable
import x.editor.app.toolchain.Runner
import java.io.File

/**
 * The command "compile and run" runs for [file], as the user wants it (a long press on the
 * toolbar's run button): that of its [project] if the project has one for it (a CMake,
 * Gradle or Android project), else that of files of its extension (RunCommands.keyFor).
 *
 * What it is for; the command; below it, as it is typed, what it comes to for [file], its
 * working directory (the project's root, ProjectRoot) and where it runs (the app's own
 * toolchain or Termux); then the variables it may use, each with what it stands for and
 * what it is for [file] now: a tap puts it in the command where the cursor is. Save keeps
 * it; Default puts the app's (RunCommands.DEFAULTS) in the field, to be saved.
 */
internal class RunSettingsDialog(
    private val activity: Activity,
    private val file: File,
    private val project: ProjectRoot.Project? = null,
) {

    private val view = LayoutInflater.from(activity).inflate(R.layout.dialog_run_settings, null)
    private val field = view.findViewById<TextInputEditText>(R.id.run_command_field)
    private val previewCommand = view.findViewById<TextView>(R.id.run_preview_command)
    private val previewWorkDir = view.findViewById<TextView>(R.id.run_preview_workdir)
    private val previewRunner = view.findViewById<TextView>(R.id.run_preview_runner)
    private val variables = view.findViewById<LinearLayout>(R.id.run_variables)

    private val key = RunCommands.keyFor(file, project)
    private val dim = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val accent = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
    private val mono by lazy { ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular) }

    // the rows of the variables: their values follow the command (where it runs changes ${tmp}, ...)
    private val valueViews = LinkedHashMap<Variable, TextView>()

    fun show() {
        val extension = file.extension.lowercase()
        view.findViewById<TextView>(R.id.run_scope).text = RunCommands.projectKindOf(key)
            ?.let { activity.getString(R.string.run_scope_project, it.label) }
            ?: activity.getString(R.string.run_scope_file, if (extension.isEmpty()) file.name else ".$extension")
        field.setText(RunCommands.template(activity, key).orEmpty())
        field.setSelection(field.text?.length ?: 0)
        previewWorkDir.text = RunCommands.workDirOf(file, project).path

        // Gradle's variable only where there is Gradle
        val shown = Variable.entries.filter { it != Variable.GRADLE || project?.kind.let { k -> k == ProjectRoot.Kind.GRADLE || k == ProjectRoot.Kind.ANDROID } }
        for (variable in shown) variables.addView(variableRow(variable))

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.run_settings_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            // set below: it fills the field in, the dialog stays
            .setNeutralButton(R.string.run_settings_default, null)
            .setPositiveButton(R.string.run_settings_save) { _, _ ->
                val text = field.text?.toString().orEmpty().trim()
                // the default as it is: no command of the user's (later defaults apply)
                RunCommands.setTemplate(activity, key, text.takeIf { it != RunCommands.DEFAULTS[key] })
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.apply {
                isEnabled = RunCommands.DEFAULTS[key] != null
                setOnClickListener {
                    field.setText(RunCommands.DEFAULTS[key].orEmpty())
                    field.setSelection(field.text?.length ?: 0)
                }
            }
        }
        dialog.show()
    }

    // what the command comes to, where, and what the variables are there
    private fun update() {
        val template = field.text?.toString().orEmpty().trim()
        val values = RunCommands.valuesFor(activity, template, file, project)
        previewCommand.text = if (template.isEmpty()) activity.getString(R.string.run_preview_none) else RunCommands.expand(template, values)
        previewCommand.setTextColor(if (template.isEmpty()) dim else previewWorkDir.currentTextColor)
        val runner = RunCommands.runnerOf(activity, template, project)
        previewRunner.text = activity.getString(if (runner == Runner.LOCAL) R.string.run_runner_local else R.string.run_runner_termux)
        for ((variable, valueView) in valueViews) {
            val value = values[variable].orEmpty()
            valueView.text = value.ifEmpty { activity.getString(R.string.run_value_empty) }
        }
    }

    // A variable: `${file}` | what it stands for, what it is now. A tap puts it where the cursor is.
    private fun variableRow(variable: Variable): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { insert(variable.token) }
        }
        val token = TextView(activity).apply {
            text = variable.token
            typeface = mono
            textSize = 13f
            setTextColor(accent)
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val description = TextView(activity).apply {
            setText(variable.description)
            textSize = 13f
        }
        val value = TextView(activity).apply {
            typeface = mono
            textSize = 11f
            setTextColor(dim)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        valueViews[variable] = value
        texts.addView(description)
        texts.addView(value)
        row.addView(token, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    // [text] where the cursor is (in place of what is selected)
    private fun insert(text: String) {
        val editable = field.text ?: return
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(0)
        editable.replace(minOf(start, end), maxOf(start, end), text)
        field.requestFocus()
    }
}

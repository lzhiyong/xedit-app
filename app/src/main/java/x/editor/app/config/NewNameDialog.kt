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
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo

import android.content.DialogInterface

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import x.editor.app.R

/**
 * Asks for the name of something new (New on a sheet of the developer tools: a server, a
 * file of snippets, a tab of shortcuts): [title], [hint] in the field, [initial] in it.
 * [problemOf] says what is wrong with a name (null: nothing), under the field, and the
 * dialog stays; a right one goes to [onName], trimmed.
 */
internal fun askNewName(
    activity: Activity,
    title: CharSequence,
    hint: CharSequence,
    initial: String = "",
    problemOf: (String) -> String?,
    noteOf: ((String) -> CharSequence?)? = null,
    onName: (String) -> Unit,
) {
    val content = LayoutInflater.from(activity).inflate(R.layout.dialog_new_name, null)
    val layout = content.findViewById<TextInputLayout>(R.id.new_name_layout)
    val field = content.findViewById<TextInputEditText>(R.id.new_name)
    layout.hint = hint
    field.setText(initial)
    field.setSelection(initial.length)
    // under the field: what the name will make (the template it starts from), as it is typed
    fun showNote() {
        val note = noteOf?.invoke(field.text?.toString()?.trim().orEmpty())
        layout.isHelperTextEnabled = !note.isNullOrEmpty()
        layout.helperText = note
    }
    showNote()
    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        // set again below: the dialog stays while the name is not right
        .setPositiveButton(R.string.config_new_create, null)
        .create()
    fun submit() {
        val name = field.text?.toString()?.trim().orEmpty()
        val problem = problemOf(name)
        if (problem != null) {
            layout.error = problem
            return
        }
        dialog.dismiss()
        onName(name)
    }
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
        field.requestFocus()
    }
    field.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) {
            submit()
            true
        } else {
            false
        }
    }
    field.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            // checked as it is typed: what is wrong under the field at once (not for an empty one)
            val name = s?.toString()?.trim().orEmpty()
            layout.error = if (name.isEmpty()) null else problemOf(name)
            showNote()
        }
    })
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}

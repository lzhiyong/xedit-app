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

package x.editor.app.controller

import android.animation.ObjectAnimator
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import x.editor.app.R
import x.github.module.editor.view.EditorView
import kotlin.math.roundToInt

/**
 * Go to a line (or `line:column`) of the file in [editor]: a Material dialog with the
 * line typed in, a preview of the line it lands on that slides along as the target
 * changes, a slider over the whole file, and chips for the first, the current and the
 * last line. Something that is not a line shakes the field; a jump lights the line up in
 * the editor for a moment (EditorView.flashLine).
 */
internal class GotoLineDialog(private val context: Context, private val editor: EditorView) {

    // Material 3's emphasized decelerate: what comes in
    private val emphasized = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private val view = LayoutInflater.from(context).inflate(R.layout.dialog_goto_line, null)
    private val inputLayout = view.findViewById<TextInputLayout>(R.id.goto_line_input_layout)
    private val input = view.findViewById<TextInputEditText>(R.id.goto_line_input)
    private val previewCard = view.findViewById<MaterialCardView>(R.id.goto_line_preview)
    private val previewNumber = view.findViewById<TextView>(R.id.goto_line_preview_number)
    private val previewText = view.findViewById<TextView>(R.id.goto_line_preview_text)
    private val slider = view.findViewById<Slider>(R.id.goto_line_slider)
    private val chips = view.findViewById<View>(R.id.goto_line_chips)

    private val lineCount = Math.max(1, editor.getLineCount())
    private val currentLine = editor.getCursorLine().coerceIn(1, lineCount)

    private lateinit var dialog: AlertDialog
    private var target: GotoLineTarget = GotoLineTarget.Empty
    // the line the preview shows, and whether the slider is being set from the text
    private var previewLine = 0
    private var settingSlider = false

    fun show() {
        inputLayout.helperText = context.getString(R.string.goto_line_range, lineCount)

        // the slider over the whole file (a file of one line has nothing to slide over)
        if (lineCount >= 2) {
            slider.valueFrom = 1f
            slider.valueTo = lineCount.toFloat()
            slider.value = currentLine.toFloat()
            slider.setLabelFormatter { it.roundToInt().toString() }
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !settingSlider) setInput(value.roundToInt().toString())
            }
        } else {
            slider.visibility = View.GONE
        }

        view.findViewById<Chip>(R.id.goto_line_first).setOnClickListener { setInput("1") }
        view.findViewById<Chip>(R.id.goto_line_current).apply {
            text = context.getString(R.string.goto_line_current, currentLine)
            setOnClickListener { setInput(currentLine.toString()) }
        }
        view.findViewById<Chip>(R.id.goto_line_last).setOnClickListener { setInput(lineCount.toString()) }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update(s ?: "")
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                go()
                true
            } else {
                false
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_goto_line)
            .setTitle(R.string.goto_line_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            // set again once shown: a tap on it must not close a dialog that cannot go
            .setPositiveButton(R.string.goto_line_go, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go() }
            // the current line, all selected: typing replaces it
            setInput(currentLine.toString())
            input.selectAll()
            input.requestFocus()
            enter()
        }
        dialog.show()
    }

    // The parts come in one after the other, rising a little into place.
    private fun enter() {
        val rise = dp(14f)
        listOf(inputLayout, previewCard, slider, chips).filter { it.visibility == View.VISIBLE }
            .forEachIndexed { index, part ->
                part.alpha = 0f
                part.translationY = rise
                part.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(40L + index * 45L)
                    .setDuration(320L)
                    .setInterpolator(emphasized)
                    .start()
            }
    }

    private fun setInput(text: String) {
        input.setText(text)
        input.setSelection(input.length())
    }

    // what is typed, read again: the error, the button, the slider, the preview
    private fun update(text: CharSequence) {
        target = GotoLineTarget.parse(text, lineCount)
        val goButton = if (::dialog.isInitialized) dialog.getButton(DialogInterface.BUTTON_POSITIVE) else null
        when (val t = target) {
            is GotoLineTarget.Valid -> {
                inputLayout.error = null
                goButton?.isEnabled = true
                if (lineCount >= 2 && slider.value.roundToInt() != t.line) {
                    settingSlider = true
                    slider.value = t.line.toFloat()
                    settingSlider = false
                }
                showPreview(t.line)
            }
            is GotoLineTarget.OutOfRange -> {
                inputLayout.error = context.getString(R.string.goto_line_out_of_range, lineCount)
                goButton?.isEnabled = false
                dimPreview()
            }
            GotoLineTarget.Invalid -> {
                inputLayout.error = context.getString(R.string.goto_line_invalid)
                goButton?.isEnabled = false
                dimPreview()
            }
            GotoLineTarget.Empty -> {
                inputLayout.error = null
                goButton?.isEnabled = false
                dimPreview()
            }
        }
    }

    // The line it lands on: its number and its text (tabs shown as spaces, a long line
    // cut), sliding in from below when the target went down the file, from above when up.
    private fun showPreview(line: Int) {
        previewCard.animate().alpha(1f).setDuration(150L).start()
        if (line == previewLine) return
        val direction = if (line > previewLine) 1f else -1f
        val first = previewLine == 0
        previewLine = line
        previewNumber.text = line.toString()
        val content = editor.getLineText(line).take(PREVIEW_CHARS).replace("\t", "    ").trim()
        previewText.text = content.ifEmpty { context.getString(R.string.goto_line_empty) }
        previewText.alpha = if (content.isEmpty()) 0.6f else 1f
        if (first) return
        for (part in listOf(previewNumber, previewText)) {
            val alpha = part.alpha
            part.animate().cancel()
            part.translationY = direction * dp(10f)
            part.alpha = 0f
            part.animate().translationY(0f).alpha(alpha).setDuration(200L).setInterpolator(emphasized).start()
        }
    }

    private fun dimPreview() {
        previewCard.animate().alpha(0.38f).setDuration(150L).start()
    }

    // Goes if what is typed is a line of the file; shakes the field otherwise.
    private fun go() {
        val t = target
        if (t !is GotoLineTarget.Valid) {
            shake()
            return
        }
        dialog.dismiss()
        editor.gotoLine(t.line, t.column, flash = true)
        editor.requestFocus()
    }

    private fun shake() {
        val d = dp(10f)
        ObjectAnimator.ofFloat(inputLayout, View.TRANSLATION_X, 0f, d, -d * 0.8f, d * 0.6f, -d * 0.4f, d * 0.2f, 0f)
            .setDuration(420L)
            .start()
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        inputLayout.performHapticFeedback(feedback)
        if (target is GotoLineTarget.Empty) {
            inputLayout.error = context.getString(R.string.goto_line_invalid)
        }
    }

    private fun dp(value: Float) = value * context.resources.displayMetrics.density

    private companion object {
        // the chars of a line the preview shows at most (a minified line is long)
        const val PREVIEW_CHARS = 200
    }
}

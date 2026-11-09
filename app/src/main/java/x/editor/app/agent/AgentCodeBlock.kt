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

package x.editor.app.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import x.editor.app.R
import x.editor.app.view.CodeBlockFrame
import x.editor.app.view.CodeEditorView
import x.github.module.editor.input.EditorClipboard
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.common.Range

/**
 * A code block of the AI assistant (AgentSheet): a code block of an answer, or what a tool
 * came to (read_file, run_command, ...). Its head says what it is, with Copy (all of it) and
 * Expand (all of it in CodeViewer, where it can be selected); under it the code, in an
 * editor of the app ([CodeEditorView]) as the editors of the sheets look (styleEditor),
 * highlighted as a file of its language once it is all there, as high as the code up to
 * [MAX_LINES] lines (CodeBlockFrame). A longer one fades out at its foot, what follows
 * showing faintly through, with Show more under it: unfolded where it is up to
 * [UNFOLD_MAX_LINES] lines (Show less folds it back), longer still shown whole in CodeViewer.
 *
 * Only to look at: nothing can be typed, the keyboard never comes up for it, no cursor and
 * no line of the cursor, not focused. A long press selects a word, its handles (or the
 * finger, still down) select more, the floating bar has Copy and Select all; a tap beside
 * the selection drops it, a tap on it brings the bar back. A drag across it scrolls the
 * code sideways, the conversation not scrolling with it to its end; up or down it scrolls
 * the conversation (CodeBlockFrame; the editor keeps its touches while a handle is dragged
 * or the finger selects: EditorView). Only one block of the sheet has a selection at a
 * time ([onSelectionStarted]).
 *
 * A block is bound again and again while its code streams in ([bind]): the text goes in at
 * once, plain; once the block is [Content.isComplete], it is highlighted. The text never
 * changes while it is parsed (the engine reads it from another thread): what comes meanwhile
 * goes in after. Main thread.
 */
internal class AgentCodeBlock(
    private val context: Context,
    parent: ViewGroup,
    private val scope: CoroutineScope,
    styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
    fallbackBackground: Int,
    private val onExpand: (Content) -> Unit,
    private val onSelectionStarted: (AgentCodeBlock) -> Unit,
    /** [change] made to [view] (the block, unfolded; folded: true) with its top where it is. */
    private val keepingTop: (view: View, folding: Boolean, change: () -> Unit) -> Unit,
) {

    /**
     * What a block shows: [label] in its head, [code], highlighted as a file named
     * [highlightName] (null: plain), once it [isComplete] (the closing fence has come, or
     * the answer has ended).
     */
    data class Content(
        val label: String,
        val code: String,
        val highlightName: String?,
        val isComplete: Boolean,
    )

    /** The block, with the layout params of [parent] (which it is to be added to). */
    val view: View = LayoutInflater.from(context).inflate(R.layout.view_agent_code, parent, false)
    private val language = view.findViewById<TextView>(R.id.code_language)
    private val copy = view.findViewById<MaterialButton>(R.id.code_copy)
    private val expand = view.findViewById<MaterialButton>(R.id.code_expand)
    private val frame = view.findViewById<CodeBlockFrame>(R.id.code_frame)
    private val editor = view.findViewById<CodeEditorView>(R.id.code_editor)
    private val more = view.findViewById<TextView>(R.id.code_more)

    // what was bound last, what the editor has (its text), and what it is highlighted as
    private var bound: Content? = null
    private var shownCode: String? = null
    private var highlightedAs: String? = null
    private var job: Job? = null
    // unfolded where it is (Show more)
    private var unfolded = false
    // released: never shown again (its parse may still be running, on the text it has)
    private var released = false

    init {
        styleEditor(editor)
        // only to look at, but selectable (the floating bar of styleEditor: copy, select all):
        // see the class comment
        editor.setTextSelectable(true)
        editor.onSelectionMenuRequested = { onSelectionStarted(this) }
        editor.isFocusable = false
        editor.isFocusableInTouchMode = false
        editor.setEditable(false)
        editor.showsSoftKeyboard = false
        editor.scrollsPastEnd = false
        editor.setKeepsTouches(false)
        editor.setLineNumbersVisible(false)
        editor.setBracketMatchingEnabled(false)
        editor.setCursorDrawable(ColorDrawable(Color.TRANSPARENT))
        editor.setCurrentLineColor(Color.TRANSPARENT)
        // another text size (a pinch on it): another height
        editor.onLayoutReady = { frame.requestLayout() }

        // the block on the editor's own ground (the theme's), the head too: one piece, with
        // rounded corners that cut the editor off too
        val ground = (editor.background as? ColorDrawable)?.color ?: fallbackBackground
        editor.background = null
        view.backgroundTintList = ColorStateList.valueOf(ground)
        view.clipToOutline = true
        val dark = ColorUtils.calculateLuminance(ColorUtils.compositeColors(ground, Color.WHITE)) < 0.5
        val muted = if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
        language.setTextColor(muted)
        more.setTextColor(muted)
        copy.iconTint = ColorStateList.valueOf(muted)
        expand.iconTint = ColorStateList.valueOf(muted)

        frame.maxLines = MAX_LINES
        // what follows the lines shown fades into the block's ground
        frame.fadeColor = ground
        // the knob of a handle on the last line hangs in it (and it is the space under the code)
        frame.roomBelow = (ROOM_BELOW_DP * context.resources.displayMetrics.density).toInt()
        // a tap is the editor's (it drops the selection): Expand shows it whole, Show more unfolds it
        copy.setOnClickListener { bound?.let { copyToClipboard(it.code) } }
        expand.setOnClickListener { bound?.let(onExpand) }
        more.setOnClickListener { onMore() }
    }

    // Show more: unfolded where it is (or, too long for that, shown whole in CodeViewer);
    // Show less: folded back
    private fun onMore() {
        val content = bound ?: return
        val lines = lineCount(content.code)
        if (!unfolded && lines > UNFOLD_MAX_LINES) {
            onExpand(content)
            return
        }
        val folding = unfolded
        keepingTop(view, folding) {
            unfolded = !unfolded
            showLines(lines)
            // from its first line again
            editor.scrollTo(0, 0)
        }
    }

    // How much of [lines] lines is shown: all up to MAX_LINES; a longer block folded, its
    // first MAX_LINES fading out, Show more; unfolded, all, Show less.
    private fun showLines(lines: Int) {
        frame.lines = lines
        val long = lines > MAX_LINES
        val open = long && unfolded
        frame.maxLines = if (open) UNFOLD_MAX_LINES else MAX_LINES
        frame.fades = long && !open
        if (!long) {
            more.visibility = View.GONE
            return
        }
        more.visibility = View.VISIBLE
        more.text = when {
            open -> context.getString(R.string.agent_code_show_less)
            lines > UNFOLD_MAX_LINES -> context.getString(R.string.agent_code_more, lines)
            else -> context.getString(R.string.agent_code_show_more, lines)
        }
    }

    /** Shows [content] (the same again changes nothing). */
    fun bind(content: Content) {
        if (released || content == bound) return
        // another code (the row bound to another message), not more of the same: folded
        val previous = bound
        if (previous == null || !content.code.startsWith(previous.code)) unfolded = false
        bound = content
        language.text = content.label
        showLines(lineCount(content.code))
        // (on the main thread at once, until it waits: for a layout, for the parse)
        if (job?.isActive != true) job = scope.launch(Dispatchers.Main.immediate) { catchUp() }
    }

    // the editor brought to what was bound last, one step at a time
    private suspend fun catchUp() {
        while (true) {
            val wanted = bound ?: return
            if (wanted.code != shownCode) {
                // the tree of the text before goes with it
                editor.syntaxEngine.release()
                highlightedAs = null
                // refused while the editor is being laid out (its text size, a pinch-zoom)
                while (!editor.setText(wanted.code)) delay(BUSY_RETRY_MILLIS)
                shownCode = wanted.code
                // Its first lines shown, not its last: setText leaves the cursor at the end of
                // the text and slides the view there (a smooth scroll, which a scrollTo alone
                // does not stop). The cursor put back at the start scrolls back there at once,
                // the slide stopped.
                editor.setSelection(Range(1, 1, 1, 1))
                editor.scrollTo(0, 0)
                editor.refreshHighlighting()
                frame.requestLayout()
                continue
            }
            val name = wanted.highlightName
            if (!wanted.isComplete || name == null || name == highlightedAs) return
            highlightedAs = name
            try {
                highlight(editor, name)
            } catch (e: CancellationException) {
                highlightedAs = null
                throw e
            }
            // bound again meanwhile: on with that
        }
    }

    /** Takes the floating bar of the selection away (the list scrolls); the selection stays. */
    fun dismissMenu() {
        editor.dismissSelectionMenu()
    }

    /** Drops the selection of this block, if it has one (another block got one). */
    fun clearSelection() {
        editor.clearSelection()
    }

    /** No longer shown (the sheet closed, the block taken away): the parse and its tree go. */
    fun release() {
        released = true
        editor.onSelectionMenuRequested = null
        editor.clearSelection()
        job?.cancel()
        job = null
        editor.syntaxEngine.release()
        highlightedAs = null
    }

    private fun copyToClipboard(text: String) {
        copyText(context, text)
    }

    companion object {
        /** The most lines a block shows folded; the rest: Show more. */
        const val MAX_LINES = 20

        /** The most lines a block unfolds to where it is; longer, Show more is CodeViewer. */
        const val UNFOLD_MAX_LINES = 300
        private const val BUSY_RETRY_MILLIS = 32L
        // room under the last line: the knob of a selection handle (DrawnTextHandle: 15dp,
        // a little more as it pops up)
        private const val ROOM_BELOW_DP = 18

        fun lineCount(text: String): Int {
            var count = 1
            for (c in text) if (c == '\n') count++
            // a last line break ends the last line, it does not start one
            if (text.endsWith('\n') && count > 1) count--
            return count
        }

        /** [text] on the clipboard, with a word of how it went (as the editors say it). */
        fun copyText(context: Context, text: String) {
            val message = when {
                text.length > EditorClipboard.MAX_TEXT_LENGTH ->
                    context.getString(R.string.clipboard_too_large, text.length, EditorClipboard.MAX_TEXT_LENGTH)
                else -> try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", text))
                    context.getString(R.string.clipboard_copied)
                } catch (e: Exception) {
                    context.getString(R.string.clipboard_failed, e.localizedMessage ?: e.toString())
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

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

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.editor.app.R
import x.editor.app.lsp.LocalFiles
import x.editor.app.view.CodeEditorView
import x.editor.app.view.HandleDragSheetDialog
import x.github.module.editor.input.EditorClipboard
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * A code block of the AI assistant ([showText]: Expand of AgentCodeBlock), or a file it
 * wrote, changed or read ([showFile]: View on its card), whole and only to look at: a sheet
 * almost as high as the window, over the assistant's, in an editor of the app
 * ([CodeEditorView]) as the editors of the sheets look (styleEditor), with line numbers,
 * highlighted as a file of its name or language.
 *
 *  - Nothing can be typed and the keyboard never comes up; there is no cursor and no line
 *    of the cursor. A long press selects (handles, the magnifier), and the floating bar
 *    copies the selection or selects all (Ctrl+C / Ctrl+A on a keyboard too); Copy all in
 *    the head copies all of it.
 *  - A large file is read in chunks on a worker thread into a piece tree and laid out in
 *    the background, as the editor loads one: shown as soon as it is laid out, highlighted
 *    after. Up to [MAX_FILE_BYTES]; larger, or not text: why, and nothing shown.
 *  - Word wrap in the head, on or off. Dragged (down, closed) by the head only: a drag on
 *    the text scrolls it.
 *
 * One per showing: closed, its parse and its tree go.
 */
internal class CodeViewer(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
) {

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_code_viewer, null)
    private val header = view.findViewById<View>(R.id.viewer_header)
    private val title = view.findViewById<TextView>(R.id.viewer_title)
    private val subtitle = view.findViewById<TextView>(R.id.viewer_subtitle)
    private val wrap = view.findViewById<MaterialButton>(R.id.viewer_wrap)
    private val copyAll = view.findViewById<MaterialButton>(R.id.viewer_copy)
    private val editor = view.findViewById<CodeEditorView>(R.id.viewer_editor)
    private val message = view.findViewById<TextView>(R.id.viewer_message)
    private val progress = view.findViewById<View>(R.id.viewer_progress)

    private val dialog = HandleDragSheetDialog(activity).apply { setDragHandle(header) }
    private var job: Job? = null

    // the text is in the editor and laid out: it can be copied
    private var loaded = false

    /** [code] (a code block, what a tool came to), titled [label], highlighted as [highlightName] (null: plain). */
    fun showText(label: String, code: String, highlightName: String?) {
        open(label)
        load(label, highlightName) { PieceTreeTextBufferBuilder().apply { acceptChunk(code) }.build() }
    }

    /** [file], as it is on disk now, highlighted as a file of its name. */
    fun showFile(file: File) {
        open(file.name)
        load(file.name, file.name) { readFile(file) }
    }

    private fun open(heading: String) {
        title.text = heading
        subtitle.text = activity.getString(R.string.viewer_loading)
        setUpEditor()
        view.findViewById<View>(R.id.viewer_close).setOnClickListener { dialog.dismiss() }
        wrap.setOnClickListener { toggleWrap() }
        copyAll.setOnClickListener { copyAll() }
        copyAll.isEnabled = false
        updateWrapButton(editor.isWordwrap())

        val height = (activity.resources.displayMetrics.heightPixels * SHEET_HEIGHT).toInt()
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog.setDraggedByHandleOnly(true)
        dialog.setOnDismissListener {
            job?.cancel()
            // what the parse made goes too (a parse still running lets go of it itself)
            editor.syntaxEngine.release()
        }
        dialog.show()
    }

    private fun setUpEditor() {
        styleEditor(editor)
        // only to look at: see the class comment. Focusable, for the keys of a keyboard
        // (copy, select all); the soft keyboard never comes up for it
        editor.setEditable(false)
        editor.showsSoftKeyboard = false
        editor.setTextSelectable(true)
        editor.setLineNumbersVisible(true)
        editor.setBracketMatchingEnabled(false)
        editor.setCursorDrawable(ColorDrawable(Color.TRANSPARENT))
        editor.setCurrentLineColor(Color.TRANSPARENT)
        // a drag on it scrolls its text, not the sheet
        editor.setKeepsTouches(true)
    }

    // the text from [read] (a worker thread) into the editor, laid out, then highlighted
    private fun load(label: String, highlightName: String?, read: () -> PieceTreeTextBuffer) {
        progress.visibility = View.VISIBLE
        job = scope.launch {
            try {
                val buffer = withContext(Dispatchers.IO) { read() }
                if (!dialog.isShowing) return@launch
                val laidOut = CompletableDeferred<Unit>()
                // refused while it is being pinch-zoomed: again in a moment
                while (!editor.setBuffer(buffer) { laidOut.complete(Unit) }) delay(BUSY_RETRY_MILLIS)
                editor.refreshHighlighting()
                laidOut.await()
                if (!dialog.isShowing) return@launch
                loaded = true
                copyAll.isEnabled = true
                progress.visibility = View.GONE
                subtitle.text = activity.getString(R.string.viewer_subtitle, describe(label, highlightName), editor.getLineCount())
                if (highlightName != null) highlight(editor, highlightName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotShown) {
                showMessage(e.message.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "cannot show $label", e)
                showMessage(activity.getString(R.string.viewer_failed, label, e.localizedMessage ?: e.toString()))
            }
        }
    }

    // what the subtitle says it is: the language of a code block, the type of a file
    private fun describe(label: String, highlightName: String?): String {
        val name = highlightName ?: return activity.getString(R.string.agent_code_plain)
        if (name.startsWith("snippet.")) return name.removePrefix("snippet.")
        val extension = name.substringAfterLast('.', "")
        return extension.ifEmpty { label }
    }

    private fun showMessage(text: String) {
        progress.visibility = View.GONE
        editor.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = text
        subtitle.text = ""
    }

    // On a worker thread: [file] in chunks of characters (a character of several bytes may lie
    // across two chunks of bytes: the reader carries it over), as the editor loads a file
    private fun readFile(file: File): PieceTreeTextBuffer {
        val length = file.length()
        if (length > MAX_FILE_BYTES) {
            throw NotShown(activity.getString(R.string.viewer_too_large, file.name, Formatter.formatFileSize(activity, length)))
        }
        val builder = PieceTreeTextBufferBuilder()
        BufferedInputStream(openFile(file)).use { input ->
            // a NUL in its start: not a text file
            input.mark(PROBE_BYTES)
            val probe = ByteArray(PROBE_BYTES)
            val probed = input.read(probe).coerceAtLeast(0)
            if ((0 until probed).any { probe[it] == 0.toByte() }) throw NotShown(activity.getString(R.string.viewer_binary, file.name))
            input.reset()
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val chunk = CharArray(READ_CHUNK_CHARS)
                while (true) {
                    val n = reader.read(chunk)
                    if (n == -1) break
                    builder.acceptChunk(String(chunk, 0, n))
                }
            }
        }
        return builder.build()
    }

    // [file] to read: by its path, or, where the app may not read it so (shared storage
    // without access to all files), through the folder the user granted, as the agent's
    // tools read it (LocalFiles); what opening it by its path said if neither works
    private fun openFile(file: File): InputStream = try {
        FileInputStream(file)
    } catch (e: IOException) {
        val uri = LocalFiles.documentUriOf(activity, file) ?: throw e
        activity.contentResolver.openInputStream(uri) ?: throw e
    }

    private fun toggleWrap() {
        if (editor.isLayoutBusy()) return
        val wrapped = !editor.isWordwrap()
        updateWrapButton(wrapped)
        editor.setWordwrap(wrapped)
    }

    private fun updateWrapButton(wrapped: Boolean) {
        wrap.isSelected = wrapped
        wrap.alpha = if (wrapped) 1f else UNSELECTED_ALPHA
    }

    // all of it on the clipboard (no more than the clipboard can take: said so, then)
    private fun copyAll() {
        if (!loaded) return
        val length = editor.getBufferLength()
        if (length > EditorClipboard.MAX_TEXT_LENGTH) {
            Toast.makeText(
                activity,
                activity.getString(R.string.clipboard_too_large, length, EditorClipboard.MAX_TEXT_LENGTH),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        AgentCodeBlock.copyText(activity, editor.getText())
    }

    // why nothing is shown (too large, not text): said as it is
    private class NotShown(message: String) : Exception(message)

    private companion object {
        const val TAG = "CodeViewer"
        // of the window
        const val SHEET_HEIGHT = 0.92f
        const val BUSY_RETRY_MILLIS = 50L
        const val UNSELECTED_ALPHA = 0.55f
        // the editor takes tens of MB; beyond this, the editor of the app is the place
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        const val PROBE_BYTES = 8192
        const val READ_CHUNK_CHARS = 64 * 1024
    }
}

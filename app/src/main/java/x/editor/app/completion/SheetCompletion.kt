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
package x.editor.app.completion

import android.content.Context
import android.view.ViewTreeObserver

import x.editor.app.R
import x.editor.app.view.CodeEditorView
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.view.CompletionWindow
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.common.Range

/**
 * Completion in an editor of a sheet of the developer tools (the files of packages.json,
 * the language servers, the formatting rules, the snippets, keymap.json, a theme): what
 * [schema] says may be written where the cursor is (JsonCompletion: a key of the object it
 * is in, or a value of the key it is after), then what the file itself has there, then the
 * words of the file (BufferWords), as the editor of a document offers them without a server.
 *
 *  - It comes up as a word is typed in a key or a value, and on a `"` typed where a key or a
 *    value starts (all there is for it, then); deleting changes the list shown, a line
 *    break, a paste or an edit elsewhere takes it away.
 *  - A key taken is put in whole: `"version": ""` with the cursor in its value (`{}`, `[]`
 *    for an object, a list), or just its name where the `"` and the `:` are there already;
 *    a value in quotes unless it is true, false, null or a number.
 *  - The list is the compact one (CompletionWindow.Size.COMPACT), within the editor's own
 *    width; it follows the cursor as the sheet scrolls, and goes with the keyboard.
 *
 * @param schema what may be written in the file in the editor now; null: the words only
 */
internal class SheetCompletion(
    private val context: Context,
    private val editor: CodeEditorView,
    private val schema: () -> JsonSchema?,
) {

    // a pick is being put in: its edit is not typing
    private var applying = false
    // where the cursor was left by the last pick: a keyboard committing again what it was
    // composing there (an edit of the same length) is not typing either
    private var pickedAt = -1

    // the sheet scrolls: the list follows the cursor (or goes, when it is out of sight)
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        if (editor.isShowingCompletions) editor.repositionPopups()
    }

    init {
        editor.completionSize = CompletionWindow.Size.COMPACT
        editor.completionsWithinView = true
        editor.onTextChangesForCompletion = ::onTextChanged
        editor.viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    /** The list taken away (the editor hidden, another file put in). */
    fun dismiss() {
        editor.dismissCompletions()
    }

    /** The sheet is gone. */
    fun release() {
        dismiss()
        editor.onTextChangesForCompletion = null
        if (editor.viewTreeObserver.isAlive) editor.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
    }

    // #region typing

    private fun onTextChanged(changes: List<EditorDocument.AppliedChange>) {
        if (applying) return
        val change = changes.singleOrNull()
        if (change == null || !editor.isEditable() || editor.isSelected() || !editor.hasFocus()) {
            dismiss()
            return
        }
        val buffer = editor.getTextBuffer()
        val cursor = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
        if (cursor == pickedAt && change.deletedTextLength == change.insertedTextLength) return
        pickedAt = -1
        if (buffer.length > MAX_TEXT) {
            dismiss()
            return
        }
        val inserted = if (change.insertedTextLength in 1..MAX_TYPED) change.insertedText() else ""
        when {
            change.insertedTextLength > 0 -> {
                // a line break, a paste, an edit away from the cursor: not typing
                val atInsert = cursor >= change.rangeOffset && cursor <= change.rangeOffset + change.insertedTextLength
                if (change.insertedTextLength > MAX_TYPED || inserted.contains('\n') || !atInsert) {
                    dismiss()
                    return
                }
                show(quoteTyped = inserted.contains('"'))
            }
            change.deletedTextLength > 0 -> if (editor.isShowingCompletions) show(quoteTyped = false)
            else -> Unit
        }
    }

    // #endregion

    // #region the list

    // what a row of the list puts in
    private sealed class Pick {
        class Key(val key: JsonSchema.Key) : Pick()
        class Value(val text: String, val raw: Boolean) : Pick()
        class Word(val word: String) : Pick()
    }

    // The list for the cursor as it is, or none: what is typed of the word there matched
    // against the schema's keys or values for the place, what the file has there, and its
    // words. [quoteTyped]: a `"` was just typed, which brings all there is for the place.
    private fun show(quoteTyped: Boolean) {
        val text = editor.getText()
        val buffer = editor.getTextBuffer()
        val cursor = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
        val json = JsonCompletion.contextAt(text, cursor)
        val query = json.prefix
        // just after a quote that opens a key or a value: all of it; else a word typed
        val opened = quoteTyped && json.inString && query.isEmpty() && cursor > 0 && text[cursor - 1] == '"'
        if (query.isEmpty() && !opened) {
            dismiss()
            return
        }
        val items = ArrayList<CompletionWindow.Item>()
        val seen = HashSet<String>()
        fun offer(label: String, score: Int, positions: IntArray?, item: (IntArray?) -> CompletionWindow.Item) {
            // not the word as it is typed already (the file's own text learned it too)
            if (label == query || label == query + json.suffix || !seen.add(label)) return
            ranked += Ranked(score, item(positions))
        }
        ranked.clear()

        val base = schema()
        // what the file itself has there (a key of another package, a group written before)
        val own = JsonSchema().learn(text)
        when (json.position) {
            JsonCompletion.Position.KEY -> {
                val keys = base?.keysAt(json.path).orEmpty() + own.keysAt(json.path)
                for ((index, key) in keys.withIndex()) {
                    if (key.name in json.siblings) continue
                    val score = scoreOf(key.name, query, index) ?: continue
                    offer(key.name, score.first, score.second) { positions ->
                        CompletionWindow.Item(
                            label = key.name,
                            detail = key.detail ?: shapeWord(key.shape),
                            kind = "p", kindColor = KEY_COLOR,
                            matchIndices = positions,
                            tag = Pick.Key(key),
                        )
                    }
                }
            }
            JsonCompletion.Position.VALUE -> {
                val values = base?.valuesAt(json.path, json.key).orEmpty() + own.valuesAt(json.path, json.key)
                for ((index, value) in values.withIndex()) {
                    // in a string only what goes in one; outside, the others too
                    if (json.inString && value.raw) continue
                    val score = scoreOf(value.text, query, index) ?: continue
                    offer(value.text, score.first + 4, score.second) { positions ->
                        CompletionWindow.Item(
                            label = value.text,
                            detail = value.detail,
                            kind = if (value.raw) "k" else "#",
                            kindColor = if (value.raw) LITERAL_COLOR else VALUE_COLOR,
                            matchIndices = positions,
                            tag = Pick.Value(value.text, value.raw),
                        )
                    }
                }
                // true, false, null where a value starts and is not quoted
                if (!json.inString) {
                    val shape = base?.keyAt(json.path, json.key)?.shape
                    val literals = when (shape) {
                        JsonSchema.Shape.BOOLEAN -> listOf("true", "false")
                        null, JsonSchema.Shape.ANY -> listOf("true", "false", "null")
                        else -> emptyList()
                    }
                    for ((index, literal) in literals.withIndex()) {
                        val score = scoreOf(literal, query, index) ?: continue
                        offer(literal, score.first, score.second) { positions ->
                            CompletionWindow.Item(
                                label = literal, kind = "k", kindColor = LITERAL_COLOR,
                                matchIndices = positions, tag = Pick.Value(literal, raw = true),
                            )
                        }
                    }
                }
            }
            JsonCompletion.Position.NONE -> Unit
        }

        // then the words of the file, below what the schema says (not in a comment)
        if (query.length >= MIN_WORD_QUERY && json.position != JsonCompletion.Position.NONE) {
            val start = cursor - query.length
            val counts = BufferWords.index(text, skipStart = start, skipEnd = cursor + json.suffix.length)
            val near = BufferWords.near(text, cursor, NEAR_WINDOW, start, cursor + json.suffix.length)
            val label = context.getString(R.string.completion_source_words)
            for (candidate in BufferWords.rank(counts, near, query, MAX_WORDS)) {
                offer(candidate.word, candidate.score - WORD_PENALTY, candidate.positions) { positions ->
                    CompletionWindow.Item(
                        label = candidate.word, detail = label, kind = "w", kindColor = WORD_COLOR,
                        matchIndices = positions, tag = Pick.Word(candidate.word),
                    )
                }
            }
        }

        ranked.sortByDescending { it.score }
        ranked.take(MAX_SHOWN).mapTo(items) { it.item }
        ranked.clear()
        if (items.isEmpty()) {
            dismiss()
            return
        }
        editor.showCompletions(items) { picked -> onPicked(picked) }
    }

    private class Ranked(val score: Int, val item: CompletionWindow.Item)
    private val ranked = ArrayList<Ranked>()

    // How [label] matches [query], and where; with no query, all match, in the schema's order
    // ([index]: its place there). Null: it does not match.
    private fun scoreOf(label: String, query: String, index: Int): Pair<Int, IntArray?>? {
        if (query.isEmpty()) return (1000 - index) to null
        val (score, positions) = BufferWords.match(label, query) ?: return null
        // a schema's own name a little before a word of the text that matches as well
        return (score + SCHEMA_BONUS) to positions
    }

    private fun shapeWord(shape: JsonSchema.Shape): String? = when (shape) {
        JsonSchema.Shape.STRING -> "\"\""
        JsonSchema.Shape.NUMBER -> "0"
        JsonSchema.Shape.BOOLEAN -> "true | false"
        JsonSchema.Shape.OBJECT -> "{ }"
        JsonSchema.Shape.ARRAY -> "[ ]"
        JsonSchema.Shape.ANY -> null
    }

    // #endregion

    // #region a pick put in

    private fun onPicked(item: CompletionWindow.Item) {
        val pick = item.tag as? Pick ?: return
        if (!editor.isEditable()) return
        val text = editor.getText()
        val buffer = editor.getTextBuffer()
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        val cursor = buffer.getOffsetAt(line, column)
        val json = JsonCompletion.contextAt(text, cursor)
        // what is replaced: the word at the cursor, the part before it and after it
        val startColumn = column - json.prefix.length
        var endColumn = column + json.suffix.length
        val edit: EditorView.CompletionEdit = when (pick) {
            is Pick.Key -> {
                val name = pick.key.name
                val (after, inside) = skeletonOf(pick.key.shape)
                when {
                    // "ver|sion": the name, and the quote and colon where they are not yet
                    json.inString && json.colonAfter -> edit(line, startColumn, endColumn, name, name.length)
                    json.inString -> {
                        // the closing quote, if there is one, is put in again with the rest
                        if (json.closingQuote) endColumn++
                        val put = "$name\": $after"
                        edit(line, startColumn, endColumn, put, put.length - inside)
                    }
                    else -> {
                        val put = "\"$name\": $after"
                        edit(line, startColumn, endColumn, put, put.length - inside)
                    }
                }
            }
            is Pick.Value -> when {
                // in its string: the value, and the cursor after the closing quote
                json.inString -> {
                    val closed = json.closingQuote
                    if (closed) endColumn++
                    val put = pick.text + "\""
                    edit(line, startColumn, endColumn, put, put.length)
                }
                pick.raw -> edit(line, startColumn, endColumn, pick.text, pick.text.length)
                else -> {
                    val put = "\"${pick.text}\""
                    edit(line, startColumn, endColumn, put, put.length)
                }
            }
            is Pick.Word -> edit(line, startColumn, endColumn, pick.word, pick.word.length)
        }
        applying = true
        try {
            editor.applyCompletion(edit)
        } finally {
            applying = false
        }
        pickedAt = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
    }

    // what follows a key taken, by the shape of its value, and how far from its end the
    // cursor goes (into the quotes, the braces, the brackets)
    private fun skeletonOf(shape: JsonSchema.Shape): Pair<String, Int> = when (shape) {
        JsonSchema.Shape.STRING -> "\"\"" to 1
        JsonSchema.Shape.OBJECT -> "{}" to 1
        JsonSchema.Shape.ARRAY -> "[]" to 1
        else -> "" to 0
    }

    private fun edit(line: Int, startColumn: Int, endColumn: Int, text: String, cursor: Int) =
        EditorView.CompletionEdit(Range(line, startColumn, line, endColumn), text, cursor.coerceIn(0, text.length))

    // #endregion

    private companion object {
        // a file longer than this is not looked through at every key
        const val MAX_TEXT = 512 * 1024
        // more than this put in at once is not typing (a paste)
        const val MAX_TYPED = 64
        // shown at most
        const val MAX_SHOWN = 40
        // the words of the text offered at most, and from how many chars typed
        const val MAX_WORDS = 12
        const val MIN_WORD_QUERY = 2
        // the words near the cursor count for more, within this many chars of it
        const val NEAR_WINDOW = 4000
        // what the schema says goes before the words of the text
        const val SCHEMA_BONUS = 24
        const val WORD_PENALTY = 8

        // the badges, as the editor of a document has them (LspController.badgeOf)
        const val KEY_COLOR = 0xFF4EC9B0.toInt()
        const val VALUE_COLOR = 0xFF98C379.toInt()
        const val LITERAL_COLOR = 0xFFE06C9F.toInt()
        const val WORD_COLOR = 0xFF8FA6C0.toInt()
    }
}

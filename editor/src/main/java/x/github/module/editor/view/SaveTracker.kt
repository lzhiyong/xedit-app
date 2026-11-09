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

package x.github.module.editor.view

import android.os.Looper

import x.github.module.piecetable.common.TextChange

import x.github.module.editor.model.EditHistory
import x.github.module.editor.model.EditSession
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.HistoryChange
import x.github.module.editor.model.UndoManager
import x.github.module.editor.model.UndoStep

/**
 * Which text is the saved one, and the edit sessions that belong to it: the
 * bookkeeping behind EditorView's getTextVersion, markSaved, isModified, saveSession
 * and restoreSession, without the view. Taken out of EditorView, which keeps those
 * methods and does what they do to the view (cursor, scrolling, folds).
 *
 * Main thread, except [textVersion] and [isModified].
 */
internal class SaveTracker(
    private val history: EditHistory,
    private val document: EditorDocument,
    private val runOnMainThread: (() -> Unit) -> Unit
) {

    companion object {
        // how many versions handed out by takeTextVersion are remembered for markSaved
        private const val MAX_VERSION_MARKS = 16
    }

    /**
     * Counts every edit that was applied, undo and redo included, and every text that
     * was loaded. Written on the main thread, read from any thread.
     */
    @Volatile
    var textVersion = 0L
        private set

    // the version of the text that was loaded last: versions below it are about
    // texts that are gone
    @Volatile
    private var loadedVersion = 0L

    // The versions handed out on the main thread, each with the place in the
    // history it stands for: markSaved(version) arrives when the file is written,
    // and the user may have typed on in the meantime.
    private val versionMarks = ArrayDeque<Pair<Long, UndoManager.Mark>>()

    /** An edit was applied (afterTextChanged). */
    fun onTextEdited() {
        textVersion++
    }

    /**
     * A text was loaded (setBuffer, setText, another piece tree): it is the saved
     * one, and nothing from before can be undone into it.
     */
    fun onTextLoaded() {
        history.clear()
        textVersion++
        loadedVersion = textVersion
        versionMarks.clear()
    }

    /** See EditorView.getTextVersion. Any thread; on the main thread it also notes where the history stands. */
    fun takeTextVersion(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val mark = history.mark()
            val version = textVersion
            if (versionMarks.lastOrNull()?.first != version) {
                versionMarks.addLast(version to mark)
                while (versionMarks.size > MAX_VERSION_MARKS) {
                    versionMarks.removeFirst()
                }
            }
            return version
        }
        return textVersion
    }

    /** See EditorView.markSaved. Any thread; takes effect on the main thread. */
    fun markSaved(version: Long) {
        runOnMainThread {
            when {
                // about a text that is no longer open
                version < loadedVersion -> {}
                // nothing edited since
                version == textVersion -> history.markSaved()
                else -> {
                    val mark = versionMarks.firstOrNull { it.first == version }?.second
                    if (mark != null) history.markSaved(mark) else history.forgetSavePoint()
                }
            }
        }
    }

    /** See EditorView.markUnsaved. Any thread; takes effect on the main thread. */
    fun markUnsaved() {
        runOnMainThread { history.forgetSavePoint() }
    }

    /** See EditorView.isModified. Any thread. */
    val isModified: Boolean get() = history.isModified

    // #region edit sessions

    // how much of the history a session keeps, see EditorView.setSessionLimits
    var sessionMaxSteps = EditorView.DEFAULT_SESSION_MAX_STEPS
        set(value) {
            field = Math.max(0, value)
        }
    var sessionMaxChars = EditorView.DEFAULT_SESSION_MAX_CHARS
        set(value) {
            field = Math.max(0, value)
        }

    /**
     * The session of the saved text (see EditorView.saveSession): the history as it
     * is from the saved text, as much of it as the limits allow, the unsaved edits
     * first. [cursorOffset] is where the cursor is in the text on screen; [folds]
     * (start and end lines one after the other) are kept only when that text is the
     * saved one.
     *
     * @return null if the save point is lost
     */
    fun buildSession(contentHash: String, cursorOffset: Int, folds: IntArray): EditSession? {
        val aligned = history.alignedToSavePoint() ?: return null

        // The saved text, worked out from the one on screen: take back the steps made
        // since, or make again the ones undone past it. Its length and line count come
        // from the shape of the contents, without reading them.
        var textLength = document.length.toLong()
        var lineCount = document.getLineCount().toLong()
        for (step in aligned.stepsAhead) {
            for (change in step.changes) {
                textLength -= change.newLength - change.oldLength
                lineCount -= change.newContent.eolCount - change.oldContent.eolCount
            }
        }
        for (step in aligned.stepsBehind) {
            for (change in step.changes) {
                textLength += change.newLength - change.oldLength
                lineCount += change.newContent.eolCount - change.oldContent.eolCount
            }
        }

        // the cursor: where the text on screen starts to differ from the saved one,
        // or where it is if the two are the same
        val savedCursor = when {
            // the first change made after saving, in the saved text
            aligned.stepsAhead.isNotEmpty() ->
                aligned.stepsAhead.last().changes.firstOrNull()?.oldPosition ?: 0
            // the end of the last change that is redone to get to the saved text
            aligned.stepsBehind.isNotEmpty() ->
                aligned.stepsBehind.last().changes.firstOrNull()?.newEnd ?: 0
            else -> cursorOffset
        }

        // as much as the limits allow, the unsaved edits first
        var stepsLeft = sessionMaxSteps
        var charsLeft = sessionMaxChars.toLong()
        fun keep(steps: List<UndoStep>): ArrayDeque<List<TextChange>> {
            val kept = ArrayDeque<List<TextChange>>()
            for (step in steps) {
                val length = step.length
                if (stepsLeft <= 0 || length > charsLeft) break
                kept.addLast(step.toTextChanges())
                stepsLeft--
                charsLeft -= length
            }
            return kept
        }
        val redoStack = keep(aligned.redoStack)
        val undoStack = keep(aligned.undoStack)

        val length = textLength.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        // the folds are lines of the text on screen: only when that is the saved one
        val atSavedText = aligned.stepsAhead.isEmpty() && aligned.stepsBehind.isEmpty()
        return EditSession(
            contentHash = contentHash,
            textLength = length,
            lineCount = lineCount.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
            cursorOffset = savedCursor.coerceIn(0, length),
            undoStack = undoStack,
            redoStack = redoStack,
            foldedLines = if (atSavedText) folds.toList() else emptyList()
        )
    }

    /**
     * Whether [session] can be put on the text as it is: the same text (hash, length,
     * lines), not edited since it was loaded. Null if it can; otherwise why not.
     */
    fun checkSession(session: EditSession, contentHash: String): EditSession.RestoreResult? = when {
        session.contentHash != contentHash ||
            session.textLength != document.length ||
            session.lineCount != document.getLineCount() -> EditSession.RestoreResult.CONTENT_MISMATCH
        isModified -> EditSession.RestoreResult.TEXT_MODIFIED
        else -> null
    }

    /** Puts the history of [session] in place of whatever was there; the text counts as saved. */
    fun restoreHistory(session: EditSession) {
        history.restore(
            session.undoStack.map { step -> UndoStep(step.map { HistoryChange.of(it) }) },
            session.redoStack.map { step -> UndoStep(step.map { HistoryChange.of(it) }) }
        )
    }

    // #endregion
}

/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.editor.layout

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

import x.github.module.editor.model.EditorTextSource

/**
 * The line-break model: maps rows (visual, on-screen lines) to real buffer lines.
 *
 * A layout only measures and caches; it does not draw and does not know the view.
 * It reads the lines from [source], measures them with [metrics], and leaves the
 * painting to the renderer.
 *
 * This class is only the questions one can ask about rows. How the answers are
 * stored is up to each layout, because the two have very different needs: without
 * wrapping a row *is* a buffer line and only its width is worth remembering, with
 * wrapping every row is a piece of a line with its own start and end.
 *
 * In every method `line` is a 1-based row number unless it is called `bufferLine`.
 * A row number past the end means the last row.
 */
sealed class TextLayout(
    protected val source: EditorTextSource,
    protected val metrics: TextMeasurer
) {

    /**
     * Goes up whenever the rows change: an edit, a relayout, a rescale, rows taken
     * over or freed. Whoever keeps something worked out from the rows (see
     * [FoldedLayout]) compares it to know when that is out of date. Any thread.
     */
    @Volatile
    var version: Int = 0
        private set

    // the rows changed, see version
    protected fun rowsChanged() {
        version++
    }

    /** Whether a buffer line can take several rows (word wrap). */
    open val isWrapping: Boolean get() = false

    /** The width of the widest row. */
    abstract fun width(): Int

    /** The height of all rows together. */
    abstract fun height(): Int

    /** The number of rows. */
    abstract fun count(): Int

    /** Lays out the whole text again. Slow for a large text. */
    @WorkerThread
    abstract fun relayout(
        inProgress: (Int) -> Unit = {},
        onCompleted: () -> Unit = {},
        isCancelled: () -> Boolean = { false }
    )

    /**
     * Follows one edit: the buffer line [startLine] was modified, [deletedLinesCnt]
     * lines after it are gone and [insertedLinesCnt] new ones took their place.
     */
    abstract fun update(
        startLine: Int,
        insertedLinesCnt: Int,
        deletedLinesCnt: Int
    )

    /**
     * Like [update], but the inserted lines are not measured: they get rough rows (an
     * estimated width; with word wrap, one row each) until the next relayout, which
     * whoever calls this has to start (LayoutCoordinator.relayoutAfterLargeEdit). For
     * an edit that inserts so many lines that measuring them all would stall the main
     * thread, like undoing the deletion of half a large file. By default the same as
     * [update].
     */
    open fun updateRoughly(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        update(startLine, insertedLinesCnt, deletedLinesCnt)

    /**
     * The text size was multiplied by [factor] (pinch zoom): adapt to it without
     * measuring anything, if this kind of layout can.
     *
     * Must be cheap. Nothing else may be writing to the layout at the same time: no
     * relayout running, no edit possible (see LayoutCoordinator).
     *
     * @return true if the layout now matches the new text size (to within a pixel of
     *         rounding per line); false if only a [relayout] can bring it up to date
     */
    open fun rescale(factor: Float): Boolean = false

    // #region one row

    /** The buffer line the row shows (a piece of). */
    abstract fun getBufferLine(line: Int): Int

    /** The 0-based offset in the buffer line where the row starts. */
    abstract fun getLineStart(line: Int): Int

    /** The 0-based offset in the buffer line where the row ends (exclusive). */
    abstract fun getLineEnd(line: Int): Int

    /** The width of the text of the row. */
    abstract fun getLineWidth(line: Int): Int

    /**
     * Everything about the row at once. Prefer the single getters above when one
     * value is enough: not every layout has such an object lying around.
     */
    open fun getLineResult(line: Int): LineBreakResult {
        return LineBreakResult(getBufferLine(line), getLineStart(line), getLineEnd(line), getLineWidth(line))
    }

    // #endregion

    // #region buffer line -> rows

    /** The 0-based index of the first row of a buffer line. */
    abstract fun getStartIndex(bufferLine: Int): Int

    /** The 0-based index of the last row of a buffer line. */
    abstract fun getEndIndex(bufferLine: Int): Int

    /** The 0-based index of the row that shows [column] of a buffer line. */
    abstract fun getIndexAt(bufferLine: Int, column: Int): Int

    // #endregion

    /** A copy of all rows, e.g. for saving them along with the text. */
    abstract fun getBreakResults(): List<LineBreakResult>

    /** Takes over rows saved earlier with [getBreakResults] for the same text. */
    abstract fun setBreakResults(lineBreaks: List<LineBreakResult>)

    /** Frees the cached rows. */
    abstract fun recycle()
}

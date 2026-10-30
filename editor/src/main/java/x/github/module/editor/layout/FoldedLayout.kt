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

package x.github.module.editor.layout

import androidx.annotation.WorkerThread

import x.github.module.editor.model.EditorTextSource

/**
 * Which buffer lines are hidden (folded away), for [FoldedLayout].
 */
interface HiddenLines {

    /**
     * Goes up whenever the hidden lines change, so that a layout knows when to look
     * at them again.
     */
    val version: Int

    /**
     * The hidden lines as pairs of first and last line (1-based, inclusive), in
     * order, not overlapping and not touching: `[3, 8, 12, 12]` hides lines 3 to 8
     * and line 12. Empty if nothing is hidden.
     */
    fun hiddenLineRanges(): IntArray
}

/**
 * Another layout ([inner]) with some buffer lines left out: the lines of folded code.
 * The rows of the hidden lines are simply not there; everything else is the inner
 * layout's, and so is all the measuring.
 *
 * Whoever asks about rows (the renderer, the selection, the gutter, scrolling) asks
 * this one and need not know about folding: a row number here is a row on screen.
 * A position on a hidden line belongs to the row just above the hidden ones, the
 * last row of the line the fold starts on.
 *
 * What is hidden comes from [hiddenLines]. Where the hidden rows are is worked out
 * from the inner layout when first asked and kept until either of the two changes
 * ([TextLayout.version], [HiddenLines.version]); a frame asks many times, an edit or
 * a fold comes seldom. It is read on the main thread, like the layout it wraps; the
 * inner layout keeps doing everything on its own threads.
 *
 * It does not lay anything out itself: [relayout], [update] and the rest go to the
 * inner layout, which is the one the editor keeps up to date anyway.
 */
class FoldedLayout(
    val inner: TextLayout,
    source: EditorTextSource,
    metrics: TextMeasurer,
    private val hiddenLines: HiddenLines
) : TextLayout(source, metrics) {

    // Where the hidden rows are, in rows of the inner layout (0-based), and what that
    // makes of the visible rows. Never changed, only replaced.
    private class Mapping(
        val innerVersion: Int,
        val hiddenVersion: Int,
        // per run of hidden rows: its first and last inner row
        val hiddenStart: IntArray,
        val hiddenEnd: IntArray,
        // per run: the visible row index (0-based) the run comes right before,
        // i.e. how many visible rows there are above it
        val visibleBefore: IntArray,
        // per run: how many rows are hidden in it and in all runs above it
        val hiddenThrough: IntArray,
        // all hidden rows together
        val hiddenCount: Int
    )

    @Volatile
    private var mapping: Mapping? = null

    private fun mapping(): Mapping {
        val current = mapping
        val innerVersion = inner.version
        val hiddenVersion = hiddenLines.version
        if (current != null && current.innerVersion == innerVersion && current.hiddenVersion == hiddenVersion) {
            return current
        }
        return computeMapping(innerVersion, hiddenVersion).also { mapping = it }
    }

    private fun computeMapping(innerVersion: Int, hiddenVersion: Int): Mapping {
        val ranges = hiddenLines.hiddenLineRanges()
        val innerCount = inner.count()
        // the last buffer line the inner layout knows about (it can be a moment
        // behind the text)
        val lastLine = if (innerCount > 0) inner.getBufferLine(innerCount) else 0

        val starts = IntArray(ranges.size / 2)
        val ends = IntArray(ranges.size / 2)
        val before = IntArray(ranges.size / 2)
        val through = IntArray(ranges.size / 2)
        var runs = 0
        var hidden = 0
        var index = 0
        while (index + 1 < ranges.size) {
            val firstLine = Math.max(1, ranges[index])
            val lastHidden = Math.min(lastLine, ranges[index + 1])
            index += 2
            if (firstLine > lastHidden) continue
            val start = inner.getStartIndex(firstLine)
            val end = inner.getEndIndex(lastHidden)
            // a run that would overlap the one before (the inner layout is out of
            // step with the text for a moment): leave it out rather than count twice
            if (end < start || (runs > 0 && start <= ends[runs - 1])) continue
            starts[runs] = start
            ends[runs] = end
            before[runs] = start - hidden
            hidden += end - start + 1
            through[runs] = hidden
            runs++
        }
        return Mapping(
            innerVersion, hiddenVersion,
            starts.copyOf(runs), ends.copyOf(runs), before.copyOf(runs), through.copyOf(runs), hidden
        )
    }

    // #region rows <-> inner rows

    /** The inner row (0-based) of the visible row (0-based). */
    fun toInnerIndex(visibleIndex: Int): Int {
        val map = mapping()
        // how many runs come before (or right at) this visible row
        var low = 0
        var high = map.visibleBefore.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (map.visibleBefore[middle] <= visibleIndex) low = middle + 1 else high = middle
        }
        return if (low == 0) visibleIndex else visibleIndex + map.hiddenThrough[low - 1]
    }

    /**
     * The visible row (0-based) of the inner row (0-based). A hidden row gives the
     * visible row right above it: the last row of the line the fold starts on.
     */
    fun toVisibleIndex(innerIndex: Int): Int {
        val map = mapping()
        // the last run that starts at or before the inner row
        var low = 0
        var high = map.hiddenStart.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (map.hiddenStart[middle] <= innerIndex) low = middle + 1 else high = middle
        }
        val run = low - 1
        if (run < 0) return innerIndex
        if (innerIndex <= map.hiddenEnd[run]) {
            // hidden: the row above the run
            return Math.max(0, map.visibleBefore[run] - 1)
        }
        return innerIndex - map.hiddenThrough[run]
    }

    // the inner row number (1-based) of a row number of this layout; past the end
    // means the last row
    private fun innerRow(line: Int): Int {
        val visible = Math.max(1, Math.min(line, count()))
        return toInnerIndex(visible - 1) + 1
    }

    // #endregion

    override val isWrapping: Boolean get() = inner.isWrapping

    // the hidden lines may well be the widest: the width stays that of all lines, so
    // that folding does not make the view scroll sideways
    override fun width(): Int = inner.width()

    override fun height(): Int = count() * metrics.lineHeight

    override fun count(): Int = Math.max(1, inner.count() - mapping().hiddenCount)

    // #region one row

    override fun getBufferLine(line: Int): Int = inner.getBufferLine(innerRow(line))

    override fun getLineStart(line: Int): Int = inner.getLineStart(innerRow(line))

    override fun getLineEnd(line: Int): Int = inner.getLineEnd(innerRow(line))

    override fun getLineWidth(line: Int): Int = inner.getLineWidth(innerRow(line))

    override fun getLineResult(line: Int): LineBreakResult = inner.getLineResult(innerRow(line))

    // #endregion

    // #region buffer line -> rows

    override fun getStartIndex(bufferLine: Int): Int = toVisibleIndex(inner.getStartIndex(bufferLine))

    override fun getEndIndex(bufferLine: Int): Int = toVisibleIndex(inner.getEndIndex(bufferLine))

    override fun getIndexAt(bufferLine: Int, column: Int): Int = toVisibleIndex(inner.getIndexAt(bufferLine, column))

    // #endregion

    // #region everything else is the inner layout's

    @WorkerThread
    override fun relayout(inProgress: (Int) -> Unit, onCompleted: () -> Unit, isCancelled: () -> Boolean) =
        inner.relayout(inProgress, onCompleted, isCancelled)

    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        inner.update(startLine, insertedLinesCnt, deletedLinesCnt)

    override fun updateRoughly(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        inner.updateRoughly(startLine, insertedLinesCnt, deletedLinesCnt)

    override fun rescale(factor: Float): Boolean = inner.rescale(factor)

    override fun getBreakResults(): List<LineBreakResult> = inner.getBreakResults()

    override fun setBreakResults(lineBreaks: List<LineBreakResult>) = inner.setBreakResults(lineBreaks)

    override fun recycle() = inner.recycle()

    // #endregion
}

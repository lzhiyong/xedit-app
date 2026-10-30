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

package x.github.module.editor.structure

import x.github.module.editor.layout.HiddenLines
import x.github.module.editor.model.EditorDocument

/**
 * The folded ranges of a text: which [FoldingRange]s are collapsed, and so which
 * lines are hidden (the lines after the start line of each, through its end line).
 *
 * What can be folded is not its business; the [FoldingRangeProvider] says that.
 * This only remembers what was folded, and keeps it right while the text changes
 * ([onTextChanged]): a fold above an edit moves with the lines, a fold an edit
 * reaches into is opened, since its hidden lines are no longer what was folded.
 *
 * Folds may nest. Folding a range inside one that is folded already is allowed;
 * its lines are hidden either way. Main thread.
 */
class FoldingModel : HiddenLines {

    // the folded ranges, by start line
    private val folds = ArrayList<FoldingRange>()

    // the hidden lines, worked out when first asked for after a change
    private var hiddenRanges: IntArray? = null

    @Volatile
    override var version: Int = 0
        private set

    private fun changed() {
        hiddenRanges = null
        version++
    }

    /** Whether nothing is folded. */
    val isEmpty: Boolean get() = folds.isEmpty()

    /** The folded ranges, by start line. */
    fun getFolds(): List<FoldingRange> = folds.toList()

    /** The folded range that starts on [line], if any. */
    fun foldAt(line: Int): FoldingRange? {
        val index = indexOf(line)
        return if (index >= 0) folds[index] else null
    }

    // the index of the fold starting on [line], or -(insertion point) - 1
    private fun indexOf(line: Int): Int {
        var low = 0
        var high = folds.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = folds[middle].startLine
            when {
                start < line -> low = middle + 1
                start > line -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }

    /**
     * Folds [range]: its lines after the start line are hidden. A fold that starts on
     * the same line is replaced. Ranges of fewer than two lines are ignored.
     *
     * @return whether anything changed
     */
    fun fold(range: FoldingRange): Boolean {
        if (range.endLine <= range.startLine || range.startLine < 1) return false
        val index = indexOf(range.startLine)
        if (index >= 0) {
            if (folds[index] == range) return false
            folds[index] = range
        } else {
            folds.add(-index - 1, range)
        }
        changed()
        return true
    }

    /**
     * Opens the fold that starts on [line].
     *
     * @return whether there was one
     */
    fun unfold(line: Int): Boolean {
        val index = indexOf(line)
        if (index < 0) return false
        folds.removeAt(index)
        changed()
        return true
    }

    /**
     * Opens every fold that hides [line], so that it can be seen.
     *
     * @return whether there was any
     */
    fun reveal(line: Int): Boolean {
        val removed = folds.removeAll { line > it.startLine && line <= it.endLine }
        if (removed) changed()
        return removed
    }

    /** Opens all folds. */
    fun clear() {
        if (folds.isEmpty()) return
        folds.clear()
        changed()
    }

    /** Whether [line] is hidden in a fold. */
    fun isHidden(line: Int): Boolean {
        val ranges = hiddenLineRanges()
        var index = 0
        while (index + 1 < ranges.size) {
            if (line < ranges[index]) return false
            if (line <= ranges[index + 1]) return true
            index += 2
        }
        return false
    }

    /** [line], or the first line below it that is not hidden. */
    fun nextVisibleLine(line: Int): Int {
        val ranges = hiddenLineRanges()
        var index = 0
        while (index + 1 < ranges.size) {
            if (line < ranges[index]) return line
            if (line <= ranges[index + 1]) return ranges[index + 1] + 1
            index += 2
        }
        return line
    }

    /** [line], or the first line above it that is not hidden. */
    fun previousVisibleLine(line: Int): Int {
        val ranges = hiddenLineRanges()
        var index = 0
        while (index + 1 < ranges.size) {
            if (line < ranges[index]) return line
            if (line <= ranges[index + 1]) return ranges[index] - 1
            index += 2
        }
        return line
    }

    override fun hiddenLineRanges(): IntArray {
        hiddenRanges?.let { return it }
        // the folds are sorted by start line: merge what overlaps or touches
        val merged = ArrayList<Int>(folds.size * 2)
        for (fold in folds) {
            val first = fold.startLine + 1
            val last = fold.endLine
            if (merged.isNotEmpty() && first <= merged[merged.size - 1] + 1) {
                if (last > merged[merged.size - 1]) merged[merged.size - 1] = last
            } else {
                merged.add(first)
                merged.add(last)
            }
        }
        return merged.toIntArray().also { hiddenRanges = it }
    }

    /**
     * Follows one change of the text (see EditorDocument.Callback.onTextChanged):
     *
     *  - a fold below the change moves up or down with its lines, also when the
     *    change ends right at the start of its first line (a line typed above it);
     *  - a fold above the change stays as it is;
     *  - a change that stays on the start line of a fold, without adding or
     *    removing lines, leaves the fold as it is (renaming a function);
     *  - any other change that reaches the lines of a fold opens it.
     *
     * @return whether the folds changed
     */
    fun onTextChanged(change: EditorDocument.AppliedChange): Boolean {
        if (folds.isEmpty()) return false
        val range = change.range
        val delta = change.insertedLinesCnt - change.deletedLinesCnt
        var modified = false
        val iterator = folds.listIterator()
        while (iterator.hasNext()) {
            val fold = iterator.next()
            when {
                // below the change, or right after it: moves with its lines
                range.endLine < fold.startLine ||
                    (range.endLine == fold.startLine && range.endColumn == 1) -> if (delta != 0) {
                    iterator.set(fold.copy(startLine = fold.startLine + delta, endLine = fold.endLine + delta))
                    modified = true
                }
                // above the change
                range.startLine > fold.endLine -> {}
                // on the start line only, and the lines stay
                range.startLine == fold.startLine && range.endLine == fold.startLine && delta == 0 -> {}
                // in the fold: open it
                else -> {
                    iterator.remove()
                    modified = true
                }
            }
        }
        // moving folds keeps their order: the ones below an edit all move alike
        if (modified) changed()
        return modified
    }
}

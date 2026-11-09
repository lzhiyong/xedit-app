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

package x.github.module.editor.model

import x.github.module.piecetable.common.Range

/**
 * The matches of a search, and which of them is the current one.
 *
 * It owns its ranges: what is handed in is copied, so nobody outside can change the
 * matches behind its back, and it can move them when the text changes without
 * changing somebody else's objects.
 *
 * And it does move them. A match is a position in the text, and positions shift when
 * text is typed or removed in front of them. After an edit ([onTextChanged]):
 *
 *  - matches before the edit are left as they are;
 *  - matches after it are moved by as much as the text moved;
 *  - matches the edit reached into are dropped: what they pointed at is gone.
 *
 * So the highlights stay on the text they belong to instead of on the place where
 * that text used to be.
 */
class SearchResults {

    // sorted by position, not overlapping
    private val ranges = ArrayList<Range>()

    /** All matches, in document order. A read-only view: it changes when the matches do. */
    val matches: List<Range> get() = ranges

    /**
     * The match the user is at ("3 of 17"), or null. Usually one of [matches], but
     * any range can be made current.
     */
    var current: Range? = null
        private set

    val isEmpty: Boolean get() = ranges.isEmpty()

    /**
     * Which of [matches] the current match is (0-based), for showing "3 of 17".
     * -1 if there is no current match, or if it is not one of the matches.
     */
    val currentIndex: Int
        get() {
            val range = current ?: return -1
            val index = indexOfFirstFrom(range.startLine, range.startColumn)
            if (index >= ranges.size) return -1
            val match = ranges[index]
            val same = match.startLine == range.startLine && match.startColumn == range.startColumn &&
                match.endLine == range.endLine && match.endColumn == range.endColumn
            return if (same) index else -1
        }

    /**
     * Replaces the matches with copies of the given ones, which must be in document
     * order. Null or an empty list clears them. The current match is forgotten.
     */
    fun setMatches(matches: List<Range>?) {
        ranges.clear()
        matches?.forEach { ranges.add(copyOf(it)) }
        current = null
    }

    /** Makes a copy of [range] the current match; null means there is none. */
    fun setCurrent(range: Range?) {
        current = range?.let { copyOf(it) }
    }

    fun clear() {
        ranges.clear()
        current = null
    }

    /**
     * Takes one match out of the list, e.g. one the user chose to skip.
     *
     * @return the match that was removed, or null if there is none at [index]
     */
    fun removeAt(index: Int): Range? {
        return if (index in ranges.indices) ranges.removeAt(index) else null
    }

    private fun copyOf(range: Range) =
        Range(range.startLine, range.startColumn, range.endLine, range.endColumn)

    // #region stepping through the matches

    /**
     * The first match that starts at or after the given position, wrapping around to
     * the first match of all. Null if there are no matches.
     */
    fun firstFrom(lineNumber: Int, column: Int): Range? {
        if (ranges.isEmpty()) return null
        val index = indexOfFirstFrom(lineNumber, column)
        return ranges[if (index < ranges.size) index else 0]
    }

    /**
     * The last match that starts before the given position, wrapping around to the
     * last match of all. Null if there are no matches.
     */
    fun lastBefore(lineNumber: Int, column: Int): Range? {
        if (ranges.isEmpty()) return null
        val index = indexOfFirstFrom(lineNumber, column) - 1
        return ranges[if (index >= 0) index else ranges.size - 1]
    }

    // binary search: the index of the first match starting at or after the position
    private fun indexOfFirstFrom(lineNumber: Int, column: Int): Int {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val range = ranges[mid]
            if (isBefore(range.startLine, range.startColumn, lineNumber, column)) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    // #endregion

    // #region following the text

    /**
     * Call after one change to the text. [replaced] is the range that was replaced,
     * in the coordinates from before the change; ([finalLineNumber], [finalColumn])
     * is where the end of that range is now, i.e. the position right after the new
     * text.
     *
     * For an edit made of several changes at once, coordinates of one change do not
     * hold for the next; call [clear] instead.
     */
    fun onTextChanged(replaced: Range, finalLineNumber: Int, finalColumn: Int) {
        val lineDelta = finalLineNumber - replaced.endLine
        val columnDelta = finalColumn - replaced.endColumn

        current = current?.let { if (follow(it, replaced, lineDelta, columnDelta)) it else null }

        if (ranges.isEmpty()) return

        // Matches are sorted, so the ones the edit reaches into are next to each
        // other. Find that run: it starts at the first match that ends after the
        // start of the edit ...
        var from = 0
        var high = ranges.size
        while (from < high) {
            val mid = (from + high) ushr 1
            val range = ranges[mid]
            if (isAfter(range.endLine, range.endColumn, replaced.startLine, replaced.startColumn)) {
                high = mid
            } else {
                from = mid + 1
            }
        }
        // ... and ends before the first match that starts at or after its end.
        var to = from
        while (to < ranges.size && isBefore(
                ranges[to].startLine, ranges[to].startColumn, replaced.endLine, replaced.endColumn
            )
        ) {
            to++
        }
        if (to > from) {
            ranges.subList(from, to).clear()
        }

        // Everything from here on is behind the edit and moves with the text. If no
        // lines were added or removed, only the rest of the line the edit ended on
        // moves, and the loop can stop there.
        for (index in from until ranges.size) {
            val range = ranges[index]
            if (lineDelta == 0 && range.startLine > replaced.endLine) break
            shift(range, replaced.endLine, lineDelta, columnDelta)
        }
    }

    // Moves [range] along with an edit, if it survives it. Returns false if the edit
    // reached into it.
    private fun follow(range: Range, replaced: Range, lineDelta: Int, columnDelta: Int): Boolean {
        return when {
            // ends before the edit starts: nothing to do
            !isAfter(range.endLine, range.endColumn, replaced.startLine, replaced.startColumn) -> true
            // starts at or after the end of the edit: moves
            !isBefore(range.startLine, range.startColumn, replaced.endLine, replaced.endColumn) -> {
                shift(range, replaced.endLine, lineDelta, columnDelta)
                true
            }
            else -> false
        }
    }

    // A position on the line the edit ended on moves sideways by as much as the end
    // of the edit did; every position behind the edit moves by the lines it added.
    private fun shift(range: Range, editEndLine: Int, lineDelta: Int, columnDelta: Int) {
        if (range.startLine == editEndLine) range.startColumn += columnDelta
        if (range.endLine == editEndLine) range.endColumn += columnDelta
        range.startLine += lineDelta
        range.endLine += lineDelta
    }

    private fun isBefore(lineA: Int, columnA: Int, lineB: Int, columnB: Int) =
        lineA < lineB || lineA == lineB && columnA < columnB

    private fun isAfter(lineA: Int, columnA: Int, lineB: Int, columnB: Int) =
        isBefore(lineB, columnB, lineA, columnA)

    // #endregion
}

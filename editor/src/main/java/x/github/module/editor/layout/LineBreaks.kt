/*
 * Copyright © 2023 Github Lzhiyong
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

import kotlinx.serialization.Serializable

/**
 * Line breaking: the result of breaking one line, the algorithm that finds the break
 * points, and the lookups that map a buffer line to its rows in a list of results.
 */

// line break result
@Serializable
data class LineBreakResult(
    var line: Int = 1, // buffer line number
    val start: Int = 0, // start offset in line text
    val end: Int = 0, // end offset in line text
    var width: Int = 0 // the width of line text (var: rescaled in place after a zoom)
)

// top-level function
public fun FloatArray.sumRange(
    start: Int, end: Int, offset: Int = 0
): Float {
    var sum: Float = 0f
    // here not check the boundary of array
    for (i in start until end) {
        sum += get(i - offset)
    }
    return sum
}

// A char that belongs to a word: a line is not broken in the middle of a run of
// these if it can be broken before the run instead.
public fun Char.isWordChar(): Boolean {
    return this.code >= 0x22 && this.code <= 0x29 ||
        this.code >= 0x30 && this.code <= 0x3A ||
        this.code >= 0x40 && this.code <= 0x7A
}

// break single line text
// [tabWidth]: tabs reach the next tab stop from the start of their row (see
// TabStops); their widths are worked out here row by row, and written into [widths]
// as they are on their row. 0 leaves the widths of tabs as they are.
public fun computeLineBreaks(
    text: String,
    widths: FloatArray,
    maxWidth: Float,
    tabWidth: Float = 0f,
): List<Int> {
    var width: Float = 0f // line text width
    var offset: Int = 0 // current offset in line text
    var start: Int = 0 // start offset of line text    
    val breaks = mutableListOf<Int>()
    // ...
    while (offset < text.length) {
        if (tabWidth > 0f && text[offset] == '\t') {
            // from where this row is so far
            widths[offset] = TabStops.next(width, tabWidth) - width
        }
        if (width + widths[offset] > maxWidth) {
            // add the current break point
            // force break here
            breaks.add(offset)
            // backtrack forward to find non-alphabetic index
            while (text[offset].isWordChar() && offset != start) {
                offset--
            }

            // find the first index of non-alphabetic
            if (offset != start && offset != breaks.last()) {
                // find the word start offset
                // modify the current break point
                breaks.run { set(lastIndex, offset + 1) }
            }

            // reset width and offset, continue next line
            width = 0.0f
            offset = breaks.last()
            start = offset
        }
        width += widths[offset++]
    }
    
    // here offset => text length
    return breaks.apply { add(offset) }
}

/**
 * find the line position
 * note here maybe not start or end position(random)
 * @line buffer line number in text buffer
 * @return index of buffer line in cache
 */
public fun List<LineBreakResult>.findRow(line: Int): Int {
    var index = 0
    var low = 0
    var high = this.size - 1 // this context is List
    
    while (low <= high) {
        // (low + high) / 2
        val mid = (low + high) shr 1
        // when running on background thread
        // here needs to check the boundary of list
        if (mid < 0 || mid >= this.size) {
            index = Math.max(0, Math.min(this.size - 1, mid))
            break
        }
        // the middle item
        val midLine = this[mid].line
        if (line > midLine) {
            low = mid + 1
        } else if (line < midLine) {
            high = mid - 1
        } else {
            // find the current line index success
            index = mid
            break
        }
    }
    
    // find the current index
    return index
}

/**
 * find the line start position
 * @line buffer line number in text buffer
 * @return first index of buffer line in cache
 */
public fun List<LineBreakResult>.firstRowOf(line: Int): Int {
    var startIndex = findRow(line)
    while (startIndex > 0 && this[startIndex - 1].line == line) {
        // continue to previous
        startIndex--
    }
    // find the start index
    return startIndex
}

/**
 * find the line end position
 * @line buffer line number in text buffer
 * @return last index of buffer line in cache
 */
public fun List<LineBreakResult>.lastRowOf(line: Int): Int {
    var endIndex = findRow(line)
    while (endIndex < this.size - 1 && this[endIndex + 1].line == line) {
        // continue to next
        endIndex++
    }
    // find the end index
    return endIndex
}

/**
 * find the specified position
 * @line buffer line number in text buffer
 * @column really column of current line
 * @return index of specified position in cache
 */
public fun List<LineBreakResult>.rowIndexAt(line: Int, column: Int): Int {
    var index = findRow(line)
    while (index > 0 && this[index].start > 0) {
        // continue to previous
        index--
    }
    
    // column = offset + 1
    while (
        column >= this[index].end + 1 && 
        index + 1 < this.size && 
        line == this[index + 1].line
    ) {
        // continue to next
        index++
    }
    
    // find the index
    return index
}

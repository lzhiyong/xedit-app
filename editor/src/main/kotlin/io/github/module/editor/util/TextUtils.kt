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

package io.github.module.editor.util

import io.github.module.piecetable.common.Range

// line break result
data class LineBreakResult(
    var line: Int = 1, // really line number
    val start: Int = 0, // start offset in line text
    val end: Int = 0, // end offset in line text
    val width: Int = 0 // line text width
)

// top-level function
public fun FloatArray.sumOf(
    start: Int, end: Int, offset: Int = 0
): Float {
    var sum: Float = 0f
    // here not check the boundary of array
    for (i in start until end) {
        sum += get(i - offset)
    }
    return sum
}

public fun Char.isAsciiLetter(): Boolean {
    return this.code >= 0x22 && this.code <= 0x29 ||
        this.code >= 0x30 && this.code <= 0x3A ||
        this.code >= 0x40 && this.code <= 0x7A
}

// break single line text
public fun breakText(
    text: String,
    widths: FloatArray,
    maxWidth: Float,
): List<Int> {
    var width: Float = 0f // line text width
    var offset: Int = 0 // current offset in line text
    var start: Int = 0 // start offset of line text    
    val breaks = mutableListOf<Int>()
    // ...
    while (offset < text.length) {
        if (width + widths[offset] > maxWidth) {
            // add the current break point
            // force break here
            breaks.add(offset)
            // backtrack forward to find non-alphabetic index
            while (text[offset].isAsciiLetter() && offset != start) {
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
 * @line really line number in text buffer
 * @return index of really line in cache
 */
public fun List<LineBreakResult>.findBy(line: Int): Int {
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
 * @line really line number in text buffer
 * @return first index of really line in cache
 */
public fun List<LineBreakResult>.startBy(line: Int): Int {
    var startIndex = findBy(line)
    while (startIndex > 0 && this[startIndex - 1].line == line) {
        // continue to previous
        startIndex--
    }
    // find the start index
    return startIndex
}

/**
 * find the line end position
 * @line really line number in text buffer
 * @return last index of really line in cache
 */
public fun List<LineBreakResult>.endBy(line: Int): Int {
    var endIndex = findBy(line)
    while (endIndex < this.size - 1 && this[endIndex + 1].line == line) {
        // continue to next
        endIndex++
    }
    // find the end index
    return endIndex
}

/**
 * find the specified position
 * @line really line number in text buffer
 * @column really column of current line
 * @return index of specified position in cache
 */
public fun List<LineBreakResult>.indexBy(line: Int, column: Int): Int {
    var index = findBy(line)
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

// 
public fun List<Range>.findBy(range: Range): List<Range> {
    return findBy(
        range.startLine,
        range.startColumn,
        range.endLine,
        range.endColumn,
    )
}

public fun List<Range>.findBy(
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int,
): List<Range> {
    var low = 0
    var high = this.size - 1 // this context is List
    // ArrayDeque used to add elements sequentially
    val result = ArrayDeque<Range>()

    while (low <= high) {
        // (low + high) / 2
        val mid = (low + high) shr 1      
        val midRange = this[mid]
        
        if (!(
            startLine > midRange.endLine ||
            endLine < midRange.startLine
            )
        ) {
            // continue to search previous
            var left = mid - 1
            while (left >= 0) {
                val range = this[left--]
                if (range.endLine < startLine) {
                    break
                }                
                // ignore when conditions are not satisfied
                // note here can not break the loop
                if (
                    range.startColumn > endColumn ||
                    range.endColumn < startColumn
                ) {
                    continue
                }
                // add sequentially
                result.addFirst(range)
            }
            
            // check the column
            if (!(
                midRange.startColumn > endColumn ||
                midRange.endColumn < startColumn
                )
            ) {
                // add the middle range
                result.add(midRange)
            }
            
            // continue to search next
            var right = mid + 1
            while (right <= this.size - 1) {
                val range = this[right++]
                if (range.startLine > endLine) {
                    break
                }
                // ignore when conditions are not satisfied
                // note here can not break the loop
                if (
                    range.startColumn > endColumn ||
                    range.endColumn < startColumn
                ) {
                    continue
                }              
                // add sequentially
                result.addLast(range)
            }

            // note here must be break loop or return list
            break 
        } else if (startLine < midRange.startLine) {
            high = mid - 1
        } else {
            if (endLine >= midRange.endLine) {
                low = mid + 1
            }
        }
    }

    return result.toList()
}


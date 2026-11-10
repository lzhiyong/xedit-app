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

package x.github.module.editor.util

import x.github.module.piecetable.common.Range

/**
 * The ranges of a sorted list that overlap a given range, found by binary search.
 * Used to pick the search matches that are on the line being drawn.
 */
public fun List<Range>.findOverlapping(range: Range): List<Range> {
    return findOverlapping(
        range.startLine,
        range.startColumn,
        range.endLine,
        range.endColumn,
    )
}

public fun List<Range>.findOverlapping(
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

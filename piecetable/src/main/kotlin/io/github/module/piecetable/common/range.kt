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
 
package io.github.module.piecetable.common

/** A range in the editor. (startLine,startColumn) is <= (endLine,endColumn) */
open class Range(
    /** Line Int on which the range starts (starts at 1). */
    var startLine: Int = 1,
    /** Column on which the range starts in line `startLine` (starts at 1). */
    var startColumn: Int = 1,
    /** Line Int on which the range ends. */
    var endLine: Int = 1,
    /** Column on which the range ends in line `endLine`. */
    var endColumn: Int = 1
) : Comparable<Range> {

    companion object {
        /** Test if `range` is empty. */
        fun isEmpty(range: Range) =
            (range.startLine == range.endLine && range.startColumn == range.endColumn)
            
        /** Test if `position` is in `range`. If the position is at the edges, will return true. */
        fun containsPosition(range: Range, position: Position): Boolean {
            if (position.lineNumber < range.startLine || position.lineNumber > range.endLine) {
                return false
            }
            if (position.lineNumber == range.startLine && position.column < range.startColumn) {
                return false
            }
            if (position.lineNumber == range.endLine && position.column > range.endColumn) {
                return false
            }
            return true
        }

        /** Test if `otherRange` is in `range`. If the ranges are equal, will return true. */
        fun containsRange(range: Range, otherRange: Range): Boolean {
            if (otherRange.startLine < range.startLine || otherRange.endLine < range.startLine) {
                return false
            }
            if (otherRange.startLine > range.endLine || otherRange.endLine > range.endLine) {
                return false
            }
            if (otherRange.startLine == range.startLine &&
                otherRange.startColumn < range.startColumn
            ) {
                return false
            }
            if (otherRange.endLine == range.endLine && otherRange.endColumn > range.endColumn) {
                return false
            }
            return true
        }

        /**
         * Test if `otherRange` is strinctly in `range` (must start after, and end before). If the
         * ranges are equal, will return false.
         */
        fun strictContainsRange(range: Range, otherRange: Range): Boolean {
            if (otherRange.startLine < range.startLine || otherRange.endLine < range.startLine) {
                return false
            }
            if (otherRange.startLine > range.endLine || otherRange.endLine > range.endLine) {
                return false
            }
            if (otherRange.startLine == range.startLine &&
                otherRange.startColumn <= range.startColumn
            ) {
                return false
            }
            if (otherRange.endLine == range.endLine && otherRange.endColumn >= range.endColumn) {
                return false
            }
            return true
        }

        /**
         * A reunion of the two ranges. The smallest position will be used as the start point, and
         * the largest one as the end point.
         */
        fun plusRange(a: Range, b: Range): Range {
            var startLine: Int
            var startColumn: Int
            var endLine: Int
            var endColumn: Int

            if (b.startLine < a.startLine) {
                startLine = b.startLine
                startColumn = b.startColumn
            } else if (b.startLine == a.startLine) {
                startLine = b.startLine
                startColumn = Math.min(b.startColumn, a.startColumn)
            } else {
                startLine = a.startLine
                startColumn = a.startColumn
            }

            if (b.endLine > a.endLine) {
                endLine = b.endLine
                endColumn = b.endColumn
            } else if (b.endLine == a.endLine) {
                endLine = b.endLine
                endColumn = Math.max(b.endColumn, a.endColumn)
            } else {
                endLine = a.endLine
                endColumn = a.endColumn
            }

            return Range(startLine, startColumn, endLine, endColumn)
        }

        /** A intersection of the two ranges. */
        fun intersectRanges(a: Range, b: Range): Range? {
            var resultstartLine = a.startLine
            var resultStartColumn = a.startColumn
            var resultendLine = a.endLine
            var resultEndColumn = a.endColumn
            var otherstartLine = b.startLine
            var otherStartColumn = b.startColumn
            var otherendLine = b.endLine
            var otherEndColumn = b.endColumn

            if (resultstartLine < otherstartLine) {
                resultstartLine = otherstartLine
                resultStartColumn = otherStartColumn
            } else if (resultstartLine == otherstartLine) {
                resultStartColumn = Math.max(resultStartColumn, otherStartColumn)
            }

            if (resultendLine > otherendLine) {
                resultendLine = otherendLine
                resultEndColumn = otherEndColumn
            } else if (resultendLine == otherendLine) {
                resultEndColumn = Math.min(resultEndColumn, otherEndColumn)
            }

            // Check if selection is now empty
            if (resultstartLine > resultendLine) {
                return null
            }
            if (resultstartLine == resultendLine && resultStartColumn > resultEndColumn) {
                return null
            }
            return Range(resultstartLine, resultStartColumn, resultendLine, resultEndColumn)
        }

        /** Return the end position (which will be after or equal to the start position) */
        fun getEndPosition(range: Range) = Position(range.endLine, range.endColumn)

        /** Return the start position (which will be before or equal to the end position) */
        fun getStartPosition(range: Range) = Position(range.startLine, range.startColumn)

        /** Create a new empty range using this range's start position. */
        fun collapseToStart(range: Range) =
            Range(range.startLine, range.startColumn, range.startLine, range.startColumn)

        // ---

        fun fromPositions(start: Position, end: Position = start) =
            Range(start.lineNumber, start.column, end.lineNumber, end.column)

        /** Create a `Range` from an `Range`. */
        fun clone(range: Range) =
            Range(range.startLine, range.startColumn, range.endLine, range.endColumn)

        /** Test if `obj` is an `Range`. */
        fun isRange(obj: Any?) = obj is Range

        /** Test if the two ranges are touching in any way. */
        fun areIntersectingOrTouching(a: Range, b: Range): Boolean {
            // Check if `a` is before `b`
            if (a.endLine < b.startLine || (a.endLine == b.startLine && a.endColumn < b.startColumn)
            ) {
                return false
            }

            // Check if `b` is before `a`
            if (b.endLine < a.startLine || (b.endLine == a.startLine && b.endColumn < a.startColumn)
            ) {
                return false
            }

            // These ranges must intersect
            return true
        }

        /** Test if the two ranges are intersecting. If the ranges are touching it returns true. */
        fun areIntersecting(a: Range, b: Range): Boolean {
            // Check if `a` is before `b`
            if (a.endLine < b.startLine ||
                    (a.endLine == b.startLine && a.endColumn <= b.startColumn)
            ) {
                return false
            }

            // Check if `b` is before `a`
            if (b.endLine < a.startLine ||
                    (b.endLine == a.startLine && b.endColumn <= a.startColumn)
            ) {
                return false
            }

            // These ranges must intersect
            return true
        }

        /**
         * A function that compares ranges, useful for sorting ranges It will first compare ranges
         * on the startPosition and then on the endPosition
         */
        fun compareRangesUsingStarts(a: Range?, b: Range?): Int {
            if (a != null && b != null) {
                val astartLine = a.startLine
                val bstartLine = b.startLine

                if (astartLine == bstartLine) {
                    val aStartColumn = a.startColumn
                    val bStartColumn = b.startColumn

                    if (aStartColumn == bStartColumn) {
                        val aendLine = a.endLine
                        val bendLine = b.endLine

                        if (aendLine == bendLine) {
                            val aEndColumn = a.endColumn
                            val bEndColumn = b.endColumn
                            return aEndColumn - bEndColumn
                        }
                        return aendLine - bendLine
                    }
                    return aStartColumn - bStartColumn
                }
                return astartLine - bstartLine
            }
            val aExists = if (a != null) 1 else 0
            val bExists = if (b != null) 1 else 0
            return aExists - bExists
        }

        /**
         * A function that compares ranges, useful for sorting ranges It will first compare ranges
         * on the endPosition and then on the startPosition
         */
        fun compareRangesUsingEnds(a: Range, b: Range): Int {
            if (a.endLine == b.endLine) {
                if (a.endColumn == b.endColumn) {
                    if (a.startLine == b.startLine) {
                        return a.startColumn - b.startColumn
                    }
                    return a.startLine - b.startLine
                }
                return a.endColumn - b.endColumn
            }
            return a.endLine - b.endLine
        }

        /** Test if the range spans multiple lines. */
        fun spansMultipleLines(range: Range) = range.endLine > range.startLine
    } // end companion
    
    /** Test if this range is empty. */
    fun isEmpty() = Range.isEmpty(this)
    
    // Clone this Range
    fun clone() = Range.clone(this)
    
    /** Test if position is in this range. If the position is at the edges, will return true. */
    fun containsPosition(position: Position) = Range.containsPosition(this, position)

    /** Test if range is in this range. If the range is equal to this range, will return true. */
    fun containsRange(range: Range) = Range.containsRange(this, range)

    /**
     * Test if `range` is strictly in this range. `range` must start after and end before this range
     * for the result to be true.
     */
    fun strictContainsRange(range: Range) = Range.strictContainsRange(this, range)

    /**
     * A reunion of the two ranges. The smallest position will be used as the start point, and the
     * largest one as the end point.
     */
    fun plusRange(range: Range) = Range.plusRange(this, range)
    /** A intersection of the two ranges. */
    fun intersectRanges(range: Range) = Range.intersectRanges(this, range)

    /** Return the end position (which will be after or equal to the start position) */
    fun getEndPosition() = Range.getEndPosition(this)

    /** Return the start position (which will be before or equal to the end position) */
    fun getStartPosition() = Range.getStartPosition(this)

    /**
     * Create a new range using this range's start position, and using endLine and endColumn as the
     * end position.
     */
    fun setEndPosition(endLine: Int, endColumn: Int) =
        Range(this.startLine, this.startColumn, endLine, endColumn)

    /**
     * Create a new range using this range's end position, and using startLine and startColumn as
     * the start position.
     */
    fun setStartPosition(startLine: Int, startColumn: Int) =
        Range(startLine, startColumn, this.endLine, this.endColumn)

    /** Create a new empty range using this range's start position. */
    fun collapseToStart() = Range.collapseToStart(this)

    /**
     * A function that compares ranges, useful for sorting ranges It will first compare ranges on
     * the startPosition and then on the endPosition
     */
    override fun compareTo(other: Range): Int {
        if (this.startLine == other.startLine) {
            if (this.startColumn == other.startColumn) {
                if (this.endLine == other.endLine) {
                    return this.endColumn - other.endColumn
                }
                return this.endLine - other.endLine
            }
            return this.startColumn - other.startColumn
        }
        return this.startLine - other.startLine
    }
    
    override fun equals(other: Any?): Boolean {
        return when (other) {
            !is Range -> false
            else -> {
                this === other ||
                this.startLine == other.startLine &&
                this.startColumn == other.startColumn &&
                this.endLine == other.endLine &&
                this.endColumn == other.endColumn
            }
        }
    }
    
    override fun hashCode(): Int {
        var result = 17
        result = result * 31 + this.startLine
        result = result * 31 + this.startColumn
        result = result * 31 + this.endLine
        result = result * 31 + this.endColumn
        return result
    }
    
    /** Transform to a user presentable string representation. */
    override fun toString() =
        "Range(${this.startLine}, ${this.startColumn} ,${this.endLine}, ${this.endColumn})"
}

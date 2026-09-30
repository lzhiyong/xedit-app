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
 
package x.github.module.piecetable.common

import kotlinx.serialization.Serializable

/** A position in the editor. */
@Serializable
open class Position(
    /** line number (starts at 1) */
    var lineNumber: Int = 1,
    /** column (the first character in a line is between column 1 and column 2) */
    var column: Int = 1
) : Comparable<Position> {

    companion object {

        /** Create a `Position` from an `Position`. */
        fun lift(pos: Position) = Position(pos.lineNumber, pos.column)

        /** Test if `obj` is an `Position`. */
        fun isPosition(obj: Any?) = obj is Position

        /**
         * Test if position `a` is before position `b`. If the two positions are equal, the result
         * will be true.
         */
        fun isBeforeOrEqual(a: Position, b: Position): Boolean {
            if (a.lineNumber < b.lineNumber) {
                return true
            }
            if (b.lineNumber < a.lineNumber) {
                return false
            }
            return a.column <= b.column
        }

        /**
         * Test if position `a` is before position `b`. If the two positions are equal, the result
         * will be false.
         */
        fun isBefore(a: Position, b: Position): Boolean {
            if (a.lineNumber < b.lineNumber) {
                return true
            }
            if (b.lineNumber < a.lineNumber) {
                return false
            }
            return a.column < b.column
        }
    }

    /**
     * Create a new position from this position.
     *
     * @param newLineNumber new line number
     * @param newColumn new column
     */
    fun with(newLineNumber: Int = this.lineNumber, newColumn: Int = this.column): Position {
        if (newLineNumber == this.lineNumber && newColumn == this.column) {
            return this
        } else {
            return Position(newLineNumber, newColumn)
        }
    }

    /**
     * Derive a new position from this position.
     *
     * @param deltaLineNumber line number delta
     * @param deltaColumn column delta
     */
    fun delta(deltaLineNumber: Int = 0, deltaColumn: Int = 0) =
        with(this.lineNumber + deltaLineNumber, this.column + deltaColumn)

    /**
     * Test if this position is before other position. If the two positions are equal, the result
     * will be false.
     */
    fun isBefore(other: Position) = isBefore(this, other)

    /**
     * Test if this position is before other position. If the two positions are equal, the result
     * will be true.
     */
    fun isBeforeOrEqual(other: Position) = isBeforeOrEqual(this, other)

    /** A function that compares positions, useful for sorting */
    override fun compareTo(other: Position): Int {
        val result = this.lineNumber - other.lineNumber
        return if (result == 0) this.column - other.column else result
    }

    /** Clone this position. */
    fun clone() = Position(this.lineNumber, this.column)
    
    override fun equals(other: Any?): Boolean {
        return when (other) {
            !is Position -> false
            else -> {
                this === other ||
                this.lineNumber == other.lineNumber &&
                this.column == other.column
            }
        }
    }
    
    override fun hashCode(): Int {
        var result = 17
        result = result * 31 + this.lineNumber
        result = result * 31 + this.column
        return result
    }
    
    // ---
    /** Convert to a human-readable representation. */
    override fun toString() = "Position(${this.lineNumber}, ${this.column})"
}

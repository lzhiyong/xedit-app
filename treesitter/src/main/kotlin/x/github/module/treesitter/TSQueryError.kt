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

package x.github.module.treesitter

/** Any error that occurred while instantiating a [Query]. */
sealed class TSQueryError : IllegalArgumentException() {
    abstract override val message: String

    /** A query syntax error. */
    class Syntax(row: Long, column: Long) : TSQueryError() {
        override val message: String =
            if (row < 0 || column < 0) "Unexpected EOF"
            else "Invalid syntax at row $row, column $column"
    }

    /** A capture name error. */
    class Capture(row: UInt, column: UInt, capture: String) : TSQueryError() {
        override val message: String = "Invalid capture name at row $row, column $column: $capture"
    }

    /** A field name error. */
    class Field(row: UInt, column: UInt, field: String) : TSQueryError() {
        override val message: String = "Invalid field name at row $row, column $column: $field"
    }

    /** A node type error. */
    class NodeType(row: UInt, column: UInt, type: String) : TSQueryError() {
        override val message: String = "Invalid node type at row $row, column $column: $type"
    }

    /** A pattern structure error. */
    class Structure(row: UInt, column: UInt) : TSQueryError() {
        override val message: String = "Impossible pattern at row $row, column $column"
    }

    /** A query predicate error. */
    class Predicate(
        row: UInt,
        details: String,
        override val cause: Throwable? = null
    ) : TSQueryError() {
        override val message = "Invalid predicate in pattern at row $row: $details"
    }
}


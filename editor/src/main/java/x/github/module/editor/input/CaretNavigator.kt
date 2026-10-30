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

package x.github.module.editor.input

import x.github.module.piecetable.common.Position

import x.github.module.editor.util.TextBoundaries

/** The ways a key can move the caret. */
enum class CaretMove {
    LEFT, RIGHT, UP, DOWN,
    WORD_LEFT, WORD_RIGHT,
    LINE_START, LINE_END,
    DOCUMENT_START, DOCUMENT_END,
    PAGE_UP, PAGE_DOWN;

    /** Whether the move goes towards the start of the text. */
    val isTowardsStart: Boolean
        get() = this == LEFT || this == UP || this == WORD_LEFT || this == LINE_START ||
            this == DOCUMENT_START || this == PAGE_UP

    /** Whether the move goes by rows on screen (and so needs the layout and pixels). */
    val isVertical: Boolean
        get() = this == UP || this == DOWN || this == PAGE_UP || this == PAGE_DOWN
}

/**
 * Where a key moves the caret along the text: by a character (a grapheme, which
 * can be several chars), by a word, to the start or end of the line or the text.
 * Plain computation on the text; the moves up and down by rows on screen need the
 * layout, and are SelectionController's.
 *
 * Only a little of a line around the caret is read: a line can be very long, and a
 * character never is.
 */
class CaretNavigator(private val text: Text) {

    /** The text, as the caret needs it. Line numbers and columns are 1-based. */
    interface Text {
        fun getLineCount(): Int
        fun getLineLength(lineNumber: Int): Int
        fun getLine(lineNumber: Int): String
        /** The text of [lineNumber] from [startColumn] to [endColumn], end exclusive. */
        fun getLineSegment(lineNumber: Int, startColumn: Int, endColumn: Int): String
    }

    private companion object {
        // how much of a line is looked at around the caret for a character
        const val CHARACTER_WINDOW = 64
    }

    private fun lineEndColumn(line: Int) = text.getLineLength(line) + 1

    /**
     * Where [move] takes the caret from [line]:[column]; null for the moves by rows
     * ([CaretMove.isVertical]), which this does not know about.
     */
    fun target(move: CaretMove, line: Int, column: Int): Position? {
        val lineCount = text.getLineCount()
        return when (move) {
            CaretMove.LEFT -> when {
                column > 1 -> Position(line, columnBefore(line, column))
                line > 1 -> Position(line - 1, lineEndColumn(line - 1))
                else -> Position(line, column)
            }
            CaretMove.RIGHT -> when {
                column < lineEndColumn(line) -> Position(line, columnAfter(line, column))
                line < lineCount -> Position(line + 1, 1)
                else -> Position(line, column)
            }
            CaretMove.WORD_LEFT -> when {
                column > 1 -> Position(line, TextBoundaries.previousWordStart(text.getLine(line), column - 1) + 1)
                line > 1 -> Position(line - 1, lineEndColumn(line - 1))
                else -> Position(line, column)
            }
            CaretMove.WORD_RIGHT -> when {
                column < lineEndColumn(line) -> Position(line, TextBoundaries.nextWordEnd(text.getLine(line), column - 1) + 1)
                line < lineCount -> Position(line + 1, 1)
                else -> Position(line, column)
            }
            CaretMove.LINE_START -> {
                // first to where the text starts, and from there to the very start
                val content = text.getLine(line)
                val indent = content.indexOfFirst { !it.isWhitespace() }
                val textStartColumn = if (indent < 0) 1 else indent + 1
                Position(line, if (column == textStartColumn) 1 else textStartColumn)
            }
            CaretMove.LINE_END -> Position(line, lineEndColumn(line))
            CaretMove.DOCUMENT_START -> Position(1, 1)
            CaretMove.DOCUMENT_END -> Position(lineCount, lineEndColumn(lineCount))
            CaretMove.UP, CaretMove.DOWN, CaretMove.PAGE_UP, CaretMove.PAGE_DOWN -> null
        }
    }

    /** The column one character before [column]. */
    fun columnBefore(line: Int, column: Int): Int {
        val windowStart = Math.max(1, column - CHARACTER_WINDOW)
        val before = text.getLineSegment(line, windowStart, column)
        return windowStart + TextBoundaries.graphemeStart(before, before.length)
    }

    /** The column one character after [column]. */
    fun columnAfter(line: Int, column: Int): Int {
        val windowEnd = Math.min(lineEndColumn(line), column + CHARACTER_WINDOW)
        val after = text.getLineSegment(line, column, windowEnd)
        return column + TextBoundaries.graphemeEnd(after, 0)
    }
}

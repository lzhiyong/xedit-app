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

package x.github.module.editor.testing

import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMeasurer
import x.github.module.editor.model.EditHistory
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.EditorTextSource
import x.github.module.piecetable.SliceEdit

import kotlin.test.assertEquals

/**
 * A font in which every char is as wide as the text size, except 'W', which is twice
 * that. Widths are therefore easy to predict (and exactly proportional to the text
 * size, like the real paint with linear text).
 */
class FakeMeasurer(override var textSize: Float = 10f) : TextMeasurer {

    /** How many times text was measured, to tell whether a relayout happened. */
    var measureCalls = 0

    override val lineHeight: Int get() = (textSize * 2).toInt()

    private fun widthOf(char: Char): Float = if (char == 'W') textSize * 2 else textSize

    override fun measureText(text: String): Int {
        measureCalls++
        var width = 0f
        for (char in text) {
            width += widthOf(char)
        }
        return width.toInt()
    }

    override fun getTextWidths(text: String, start: Int, end: Int): FloatArray {
        measureCalls++
        return FloatArray(end - start) { widthOf(text[start + it]) }
    }
}

/** A document that is just a list of lines. */
class FakeTextSource(vararg lines: String) : EditorTextSource {

    val lines = lines.toMutableList().also {
        // like a real buffer, never without a line
        if (it.isEmpty()) it.add("")
    }

    override fun getLineCount(): Int = lines.size

    override fun getLine(lineNumber: Int): String = lines[lineNumber - 1]

    override fun getLineLength(lineNumber: Int): Int = lines[lineNumber - 1].length

    /**
     * One edit, in the terms TextLayout.update is told about it: line [startLine]
     * becomes [newStartLine], the [deletedLinesCnt] lines after it go away, and
     * [insertedLines] take their place.
     */
    fun edit(startLine: Int, newStartLine: String, deletedLinesCnt: Int, insertedLines: List<String>) {
        lines[startLine - 1] = newStartLine
        repeat(deletedLinesCnt) { lines.removeAt(startLine) }
        lines.addAll(startLine, insertedLines)
    }
}

/** Stands in for View.postDelayed / removeCallbacks: nothing runs until told to. */
class FakeScheduler : EditHistory.Host {

    private val scheduled = mutableListOf<Runnable>()

    val pendingCount: Int get() = scheduled.size

    override fun postDelayed(action: Runnable, delayMillis: Long): Boolean {
        scheduled.add(action)
        return true
    }

    override fun removeCallbacks(action: Runnable): Boolean {
        scheduled.removeAll { it === action }
        return true
    }

    /** Lets the delay of everything scheduled so far elapse. */
    fun runAll() {
        val due = scheduled.toList()
        scheduled.clear()
        due.forEach { it.run() }
    }
}

/** Writes down what the edit pipeline reports. */
class RecordingCallback : EditorDocument.Callback {

    val events = mutableListOf<String>()
    val appliedChanges = mutableListOf<EditorDocument.AppliedChange>()
    var caretLineNumber = 0
    var caretColumn = 0

    override fun beforeTextChanged(edits: List<SliceEdit>) {
        events.add("before")
    }

    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        events.add("changed")
        appliedChanges.add(change)
    }

    override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, caretLineNumber: Int, caretColumn: Int) {
        events.add("after")
        this.caretLineNumber = caretLineNumber
        this.caretColumn = caretColumn
    }

    fun reset() {
        events.clear()
        appliedChanges.clear()
    }
}

/** Two layouts agree on everything that can be asked about rows. */
fun assertSameLayout(message: String, expected: TextLayout, actual: TextLayout) {
    assertEquals(expected.count(), actual.count(), "$message: row count")
    assertEquals(expected.width(), actual.width(), "$message: width")
    assertEquals(expected.height(), actual.height(), "$message: height")
    for (row in 1..expected.count()) {
        assertEquals(expected.getLineResult(row), actual.getLineResult(row), "$message: row $row")
    }
}

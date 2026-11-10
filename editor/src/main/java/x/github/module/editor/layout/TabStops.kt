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

package x.github.module.editor.layout

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Tab stops: a tab reaches from where it is to the next multiple of the tab width
 * (TextMetrics.tabWidth: so many spaces wide), counted from the start of the row.
 *
 * Everything that measures or draws text in the editor starts at the start of a
 * row (the cursor, the selection, hit testing, the rows themselves), so the stops
 * are the same wherever they are worked out. With word wrap the next row starts
 * counting anew, like a line of its own.
 */
object TabStops {

    /** Where a tab that starts at [x] (from the start of the row) ends. */
    fun next(x: Float, tabWidth: Float): Float {
        if (tabWidth <= 0f) return x
        return (Math.floor((x / tabWidth).toDouble()).toFloat() + 1f) * tabWidth
    }

    /** Whether text[start, end) has a tab. */
    fun hasTab(text: CharSequence, start: Int, end: Int): Boolean {
        for (index in start until end) {
            if (text[index] == '\t') return true
        }
        return false
    }

    /**
     * Gives each tab of text[start, end) the width that takes it to the next stop.
     * [widths] are the advances of the chars from [start] on (widths[0] is the one of
     * text[start]), and [start] is the start of the row.
     */
    fun applyTo(text: CharSequence, start: Int, end: Int, widths: FloatArray, tabWidth: Float) {
        if (tabWidth <= 0f) return
        var x = 0f
        for (index in start until end) {
            if (text[index] == '\t') {
                widths[index - start] = next(x, tabWidth) - x
            }
            x += widths[index - start]
        }
    }

    /**
     * Draws text[start, end) at [x], [y]: the runs between tabs as they are, each tab
     * as room up to the next stop from [x] (the start of the row).
     */
    fun draw(canvas: Canvas, text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint, tabWidth: Float) {
        var runStart = start
        var runX = x
        for (index in start until end) {
            if (text[index] != '\t') continue
            if (index > runStart) {
                canvas.drawText(text, runStart, index, runX, y, paint)
                runX += paint.measureText(text, runStart, index)
            }
            runX = x + next(runX - x, tabWidth)
            runStart = index + 1
        }
        if (runStart < end) {
            canvas.drawText(text, runStart, end, runX, y, paint)
        }
    }
}

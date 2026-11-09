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

package x.github.module.editor.render

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws line numbers on a grid: every digit in the middle of a cell of the same
 * width (TextMetrics.digitWidth, the widest digit of the font), right-aligned.
 *
 * Drawn as text, a number is as wide as its digits are, and in a proportional font
 * they are not all alike: "21" is narrower than "20", and the numbers of a column
 * do not line up. On the grid every number of n digits is n cells wide, and the
 * digits of the same place stand in a column.
 *
 * Main thread; nothing is allocated per number.
 */
object LineNumberText {

    // the digits of the number being drawn, the last one first
    private val digits = CharArray(10)

    /**
     * Draws [number] with its last digit in the cell that ends at [right], on the
     * [baseline], in [paint] (whose alignment is left as it was found).
     */
    fun draw(canvas: Canvas, number: Int, right: Float, baseline: Float, paint: Paint, cellWidth: Float) {
        var rest = Math.max(0, number)
        var count = 0
        do {
            digits[count++] = '0' + rest % 10
            rest /= 10
        } while (rest > 0 && count < digits.size)

        val align = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        for (index in 0 until count) {
            canvas.drawText(digits, index, 1, right - (index + 0.5f) * cellWidth, baseline, paint)
        }
        paint.textAlign = align
    }
}

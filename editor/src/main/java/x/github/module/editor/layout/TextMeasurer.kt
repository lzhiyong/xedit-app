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

/**
 * The little a layout needs to know about the font: how tall a line is and how wide
 * a piece of text.
 *
 * The editor's implementation is [TextMetrics], which asks the text paint. Keeping
 * the layouts on this interface instead of on the paint is what lets them be tested
 * on a plain JVM, with a measurer that simply counts characters.
 */
interface TextMeasurer {
    /** The current text size, in pixels. */
    val textSize: Float

    /** The height of one visual line. */
    val lineHeight: Int

    fun measureText(text: String): Int

    /** The advance width of each char of text[start, end). */
    fun getTextWidths(text: String, start: Int = 0, end: Int = text.length): FloatArray

    /**
     * How far apart the tab stops are, in pixels (see TabStops); 0 for none, a tab
     * then being as wide as its glyph. [measureText] and [getTextWidths] already give
     * tabs their width up to the next stop, from the start of what they measure.
     */
    val tabWidth: Float get() = 0f
}

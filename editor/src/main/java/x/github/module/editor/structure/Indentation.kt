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

package x.github.module.editor.structure

/**
 * Indentation measured in columns, a tab reaching to the next multiple of the tab size.
 * What the default providers ([IndentationGuideProvider], [IndentationFoldingProvider])
 * work out blocks from, for languages where indentation follows the structure.
 */
object Indentation {

    /** The width of the leading whitespace of [line] in columns; -1 if the line is blank. */
    fun indentOf(line: CharSequence, tabSize: Int): Int {
        var width = 0
        for (char in line) {
            width = when (char) {
                ' ' -> width + 1
                '\t' -> width + tabSize - width % tabSize
                else -> return width
            }
        }
        // only whitespace: a blank line has no indentation of its own
        return -1
    }

    /**
     * The 1-based column of [line] where the indentation reaches [width] columns. Past
     * the end of the line, each column further counts as one.
     */
    fun columnAt(line: CharSequence, width: Int, tabSize: Int): Int {
        var reached = 0
        var index = 0
        while (index < line.length && reached < width) {
            reached = when (line[index]) {
                '\t' -> reached + tabSize - reached % tabSize
                else -> reached + 1
            }
            index++
        }
        return index + 1 + Math.max(0, width - reached)
    }
}

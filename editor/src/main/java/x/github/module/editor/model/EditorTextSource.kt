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

package x.github.module.editor.model

/**
 * Read-only access to the text being edited, line by line: how many lines there are,
 * what each one contains and how long it is. This is all the layouts and the renderer
 * need to know about the document, and it is what [EditorDocument] implements.
 */
interface EditorTextSource {
    fun getLineCount(): Int
    fun getLine(lineNumber: Int): String
    /** The length of a line without its line break. Cheaper than getLine(n).length. */
    fun getLineLength(lineNumber: Int): Int
}

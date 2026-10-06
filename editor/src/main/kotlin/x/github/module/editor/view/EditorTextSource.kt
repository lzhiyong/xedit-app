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

package x.github.module.editor.view

/**
 * Read-only access to the text being edited, in the three terms almost every collaborator
 * needs: how many lines, the content of a line, and how tall a line is on screen.
 *
 * This is to text what [EditorGeometry] is to pixels: the shared base that each
 * collaborator's own `Host` interface extends with the few extra queries it needs.
 */
interface EditorTextSource {
    fun getLineCount(): Int
    fun getLine(lineNumber: Int): String
    fun getLineHeight(): Int
}

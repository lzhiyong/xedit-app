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

package x.editor.app.highlight

/**
 * How the text of one kind of syntax node is drawn, as a theme file describes it.
 *
 * @property foreground the text color, null to leave it as it is
 * @property background the color behind the text, null for none
 */
data class HighlightStyle(
    var foreground: Int? = null,
    var background: Int? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var strikethrough: Boolean = false,
    var underline: Boolean = false
)


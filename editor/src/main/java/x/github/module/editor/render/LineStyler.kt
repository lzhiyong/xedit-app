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

import android.text.Spanned

/**
 * Says how the text of a line is styled: colors, bold, italic, underline. This is how
 * syntax highlighting is plugged into the editor, without subclassing it:
 *
 *     editor.setLineStyler { text, _, lineStartOffset, start, end ->
 *         highlighter.spansFor(text, lineStartOffset, start, end)
 *     }
 *
 * The editor asks once per row it is about to paint, and paints what comes back with
 * [StyledTextPainter]. The spans it understands are ForegroundColorSpan,
 * BackgroundColorSpan, StyleSpan (bold, italic) and UnderlineSpan.
 *
 * It is called on the main thread, while drawing: it has to be quick. A styler that
 * needs time to work things out (parsing) does that elsewhere and answers from what
 * it has; when it knows more, it calls EditorView.invalidateStyles().
 */
fun interface LineStyler {

    /**
     * @param text the whole text of the buffer line
     * @param bufferLine its line number
     * @param lineStartOffset the offset of its first char in the document
     * @param start the first char of [text] that is about to be painted
     * @param end the char after the last one that is about to be painted
     * @return the spans for (at least) text[start, end), positioned by offsets into
     *         [text]; or null to have the text painted plainly
     */
    fun styleLine(text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Spanned?

    /**
     * A background for the whole of [bufferLine], from where the text starts to the
     * right edge of the view, on each of its rows: a code block in Markdown. Under the
     * line of the cursor, the selection and the search matches, which are drawn over
     * it. Null (the default): none.
     *
     * Asked for every visible row on every frame (backgrounds are not cached, they
     * follow the cursor): it has to be quick, a lookup in what the styler worked out
     * beforehand. When the answer changes without the text changing, the styler calls
     * EditorView.invalidate().
     */
    fun lineBackground(bufferLine: Int): Int? = null

    /**
     * Whether [styleLine] would answer for text[start, end) of [bufferLine] with styles
     * that are final. False: the styler is still working them out (in the background) and
     * answers with what it has meanwhile (plain, or the styles of before an edit); it calls
     * EditorView.invalidateStyles for the line when they are there. The editor then keeps
     * what a row shows, rather than recording it again, until the styles are final.
     * True by default: a styler that answers at once.
     */
    fun isStyleReady(bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Boolean = true
}

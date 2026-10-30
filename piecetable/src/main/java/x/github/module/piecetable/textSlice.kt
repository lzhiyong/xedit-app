/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.piecetable

import x.github.module.piecetable.common.*

/**
 * Document content kept aside to be put back later, typically the text an edit removed, for
 * undo / redo. See [PieceTreeTextBuffer.exportSlice] and [PieceTreeTextBuffer.applySliceEdits].
 *
 * Two representations, chosen when the slice is exported:
 *  - short content (below [TEXT_THRESHOLD] chars) is kept as a String, like the text of a
 *    ReverseEditOperation;
 *  - longer content is kept as references into the buffer's storage: (buffer, start, length)
 *    runs, no copy of the text. Deleting 100 MB and undoing it costs a few objects, not two
 *    more copies of 100 MB.
 *
 * A slice holding references only works with the buffer it was exported from; it keeps the
 * storage it points at alive (also after compact() / setEOL() replaced it in the buffer) and
 * reading it ([toText], inserting it) is a read of that buffer: same threading rules, so use it
 * where the buffer's undo stack lives, under the buffer's lock. It is not serializable; turn it
 * into text with [toText] / [TextSlice.of] to keep it beyond the buffer or to move content to
 * another buffer.
 *
 * Line breaks: content inserted into a buffer is normalized to the buffer's EOL, exactly like
 * applyEdits does, unless it can go in as it is: exported from a buffer with the same EOL, and
 * either exported from an EOL-normalized buffer or inserted into a buffer that is not
 * normalized either. So undo restores the exact text, mixed line breaks included, as long as
 * setEOL() was not called in between.
 */
class TextSlice internal constructor(
    // the tree the slice was exported from; null for TextSlice.of()
    internal val owner: PieceTreeBase?,
    // the content in text form; null when it is kept as segments
    internal val text: String?,
    // the content as runs of storage, in order; empty in text form
    internal val segments: Array<SliceSegment>,
    // EOL and EOL-normalized state of the buffer at export; null / false for TextSlice.of()
    internal val eol: String?,
    internal val eolNormalized: Boolean,
    // content flags of the buffer at export (they only ever turn true); false for TextSlice.of()
    internal val mightContainRTL: Boolean,
    internal val mightContainUnusualLineTerminators: Boolean,
    internal val mightContainNonBasicASCII: Boolean,
    /** Length in UTF-16 code units. */
    val length: Int,
    /** Number of line breaks; "\r\n" counts as one. */
    val eolCount: Int,
    /** Length of the first line, without its line break. */
    val firstLineLength: Int,
    /** Length of the last line; equals [firstLineLength] without line breaks. */
    val lastLineLength: Int
) {

    /**
     * Whether the content is held as a String (true) or as references into the storage of the
     * buffer it was exported from (false). A slice in text form can be read on any thread and
     * kept beyond its buffer; one holding references cannot, see the class comment.
     */
    val isText: Boolean
        get() = this.text != null

    /** How many runs of storage the slice refers to; 0 in text form. A measure of its own size. */
    val segmentCount: Int
        get() = this.segments.size

    /** The content as a String. Copies it when the slice holds references. */
    fun toText(): String {
        this.text?.let { return it }
        val result = StringBuilder(this.length)
        for (segment in this.segments) {
            result.append(segment.buffer.buffer, segment.start, segment.start + segment.length)
        }
        return result.toString()
    }

    /** Whether the content can be inserted into [tree] unchanged; see the class comment. */
    internal fun canInsertAsIs(tree: PieceTreeBase): Boolean {
        if (this.eol == null) {
            return false // TextSlice.of(): normalized like any text handed to applyEdits
        }
        if (this.text == null && this.owner !== tree) {
            return false // references into another buffer's storage
        }
        return this.eol == tree.getEOL() && (this.eolNormalized || !tree.isEOLNormalized())
    }

    override fun toString(): String {
        val form = if (this.text != null) "text" else "${this.segments.size} segments"
        return "TextSlice(length=${this.length}, eolCount=${this.eolCount}, $form)"
    }

    companion object {
        /** Content shorter than this is exported as text, longer content as references. */
        const val TEXT_THRESHOLD: Int = 16 * 1024

        private val NO_SEGMENTS = arrayOf<SliceSegment>()

        /** A slice holding [text]. It can go into any buffer and is normalized to its EOL there. */
        fun of(text: String): TextSlice {
            val (eolCount, firstLineLength, lastLineLength, _) = Strings.countEOL(text)
            return TextSlice(
                null, text, NO_SEGMENTS, null, false, false, false, false,
                text.length, eolCount, firstLineLength, lastLineLength
            )
        }
    }
}

/** [length] chars of [buffer] from the absolute offset [start]; offsets stay valid because nothing ever rewrites stored text. */
internal class SliceSegment(val buffer: TextBuffer, val start: Int, val length: Int)

/** One edit of [PieceTreeTextBuffer.applySliceEdits]: replace [range] with [content]; null (or an empty slice) deletes. */
data class SliceEdit(val range: Range, val content: TextSlice?)

/**
 * What one edit of a slice batch did, in coordinates of the document before the batch (like
 * ContentChange): [range] at [rangeOffset], [rangeLength] chars long, was replaced by [content]
 * (null: nothing inserted). [TextSlice.length], [TextSlice.eolCount] and the line lengths tell
 * where the new content ends without copying it.
 *
 * [removed] is what was in [range] before, exported like [PieceTreeTextBuffer.exportSlice]
 * (null: nothing was removed). It is the same slice as the content of the matching reverse
 * edit; having it here spares a caller that keeps its own undo history (offsets instead of
 * ranges, steps merged while typing) from matching changes and reverse edits up.
 */
data class SliceChange(
    val range: Range,
    val rangeOffset: Int,
    val rangeLength: Int,
    val content: TextSlice?,
    val removed: TextSlice? = null
)

/**
 * @property changes what the batch did, ordered from the bottom of the document to the top
 *   (the order they were applied in, as ApplyEditsResult.changes)
 * @property reverseEdits the batch that undoes this one; applying it returns the redo batch
 */
class SliceEditResult(
    val changes: List<SliceChange>,
    val reverseEdits: List<SliceEdit>
)

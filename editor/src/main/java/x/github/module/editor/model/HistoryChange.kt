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

import x.github.module.piecetable.TextSlice
import x.github.module.piecetable.common.Strings
import x.github.module.piecetable.common.TextChange

/**
 * One change of an undo step, forwards: where the text before the step had [oldContent]
 * at [oldPosition], the text after it has [newContent] at [newPosition]. Undoing puts
 * oldContent back in place of newContent, redoing does the opposite.
 *
 * The same as the piece tree's TextChange, except that both contents are [TextSlice]s.
 * A slice holds short content as a String and long content as references into the
 * storage of the buffer it came from, so "select all, delete" on a 50 MB file keeps a
 * handful of references on the undo stack, not another 50 MB copy of the file. See
 * [TextSlice] for what a slice holding references can and cannot do: in short, it only
 * works with its own buffer, and is read on the thread that edits.
 *
 * Positions are offsets (0-based UTF-16 code units). oldPosition is in the text before
 * the step, newPosition in the text after it.
 */
class HistoryChange(
    val oldPosition: Int,
    val oldContent: TextSlice,
    val newPosition: Int,
    val newContent: TextSlice
) {
    val oldLength: Int get() = oldContent.length
    val oldEnd: Int get() = oldPosition + oldContent.length
    val newLength: Int get() = newContent.length
    val newEnd: Int get() = newPosition + newContent.length

    /**
     * Whether the change can be merged with the edits around it into one undo step (a
     * typing burst): both contents held as Strings, and short. A large change (a paste,
     * a deletion of a whole block) is always a step of its own.
     */
    val isMergeable: Boolean
        get() = oldContent.isText && newContent.isText && oldLength + newLength <= MERGE_LIMIT

    /**
     * What the change holds on to, in chars: the text of contents held as Strings, a
     * small amount per run of storage for contents held as references, and something for
     * the change itself (a replace-all of 100 000 short matches is 100 000 objects, which
     * the chars alone would not show).
     */
    val cost: Long
        get() = PER_CHANGE_COST + costOf(oldContent) + costOf(newContent)

    /** The change that takes this one back. */
    fun inverted(): HistoryChange = HistoryChange(newPosition, newContent, oldPosition, oldContent)

    /**
     * The change as a TextChange, both contents as Strings. Content held as references is
     * copied: mind the size, and the thread (the buffer's).
     */
    fun toTextChange(): TextChange =
        TextChange(oldPosition, oldContent.toText(), newPosition, newContent.toText())

    override fun toString(): String {
        fun show(slice: TextSlice) =
            if (slice.isText) Strings.escapeLineFeed(slice.toText()) else slice.toString()
        return when {
            oldLength == 0 -> "HistoryChange(insert@$oldPosition ${show(newContent)})"
            newLength == 0 -> "HistoryChange(delete@$oldPosition ${show(oldContent)})"
            else -> "HistoryChange(replace@$oldPosition ${show(oldContent)} -> ${show(newContent)})"
        }
    }

    companion object {
        /** Changes up to this many chars (old and new together) merge into typing bursts. */
        const val MERGE_LIMIT = TextSlice.TEXT_THRESHOLD

        /** What a change counts for by itself, in chars. */
        const val PER_CHANGE_COST = 32L

        /** What a run of storage referred to counts for, in chars. */
        const val PER_SEGMENT_COST = 16L

        /** No content. */
        @JvmField
        val EMPTY: TextSlice = TextSlice.of("")

        fun costOf(slice: TextSlice): Long =
            if (slice.isText) slice.length.toLong() else slice.segmentCount * PER_SEGMENT_COST

        /** The change of a TextChange, both contents held as Strings. */
        fun of(change: TextChange): HistoryChange = HistoryChange(
            change.oldPosition, sliceOf(change.oldText), change.newPosition, sliceOf(change.newText)
        )

        private fun sliceOf(text: String): TextSlice = if (text.isEmpty()) EMPTY else TextSlice.of(text)
    }
}

/**
 * One undo step: the changes that are undone and redone together, in document order
 * (ascending, not overlapping), like the TextChange lists of the piece tree.
 *
 * A step is an object with an identity, and keeps it while it goes back and forth
 * between the undo and the redo stack. The save point refers to a step that way (see
 * [UndoManager]). Its [changes] are replaced each time it is replayed: replaying exports
 * what it removes from the buffer again, so content that went in as a String (a large
 * paste) comes back as references, and the String can go.
 */
class UndoStep(changes: List<HistoryChange>) {

    var changes: List<HistoryChange> = changes
        internal set(value) {
            field = value
            cost = costOf(value)
            referencedLength = referencedLengthOf(value)
        }

    /** What the step holds on to, in chars; see [HistoryChange.cost]. */
    var cost: Long = costOf(changes)
        private set

    /**
     * The chars of buffer storage the step keeps alive through contents held as
     * references (see [UndoManager.maxReferencedChars]); 0 for contents held as Strings.
     */
    var referencedLength: Long = referencedLengthOf(changes)
        private set

    /** The chars of text the step carries, old and new together, whatever form they are in. */
    val length: Long
        get() {
            var length = 0L
            for (change in changes) {
                length += change.oldLength + change.newLength
            }
            return length
        }

    /** The step as TextChanges, see [HistoryChange.toTextChange]. */
    fun toTextChanges(): List<TextChange> = changes.map { it.toTextChange() }

    override fun toString(): String = "UndoStep$changes"

    private companion object {
        fun referencedLengthOf(changes: List<HistoryChange>): Long {
            var length = 0L
            for (change in changes) {
                if (!change.oldContent.isText) length += change.oldLength
                if (!change.newContent.isText) length += change.newLength
            }
            return length
        }

        fun costOf(changes: List<HistoryChange>): Long {
            var cost = 0L
            for (change in changes) {
                cost += change.cost
            }
            return cost
        }
    }
}

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

package x.editor.app.highlight

/**
 * The highlight captures found so far, in blocks of the text (see TreeSitterEngine):
 * what rows are styled from, on the main thread, without running a query there.
 *
 * Blocks are found on a thread of their own and handed in with [put]. Until a block is
 * there, the rows in it are drawn plainly (they had nothing on screen) or as they were.
 *
 * Blocks follow the edits ([onEdit]): those after an edit move along, the one an edit
 * is in grows or shrinks with it and becomes stale. A stale block is still drawn from
 * (a letter typed into a name keeps the color of the name at once) until the parse after
 * the edit is done and the block is found again with the new tree ([markStale] with what
 * the parse changed). So an edit never waits for a query, and never shows plain text.
 *
 * Blocks never overlap; they are kept in the order of the text. Main thread.
 */
internal class CaptureStore(
    // the chars of a block that is asked for (it may be cut to the room between blocks)
    private val blockChars: Int,
    // how many blocks are kept; those used longest ago go first
    private val maxBlocks: Int
) {

    /**
     * The captures of the chars [start, end), each cut to them: chars [starts], [ends],
     * the rank (priority, then order) and the style. Found with the text of [version].
     */
    class Block(
        var start: Int,
        var end: Int,
        val count: Int,
        val starts: IntArray,
        val ends: IntArray,
        val ranks: LongArray,
        val styles: Array<HighlightStyle>,
        // which request found it: of two blocks of the same chars the later one counts
        val sequence: Long
    ) {
        /** Of a text or a tree that is not the one of now: drawn from, but found again. */
        var stale = false
        /**
         * Stale from an edit that is not parsed yet: found again after the parse (finding
         * it now, with the tree only told about the edit, would find what it has).
         */
        var awaitsParse = false
        internal var lastUsed = 0L
    }

    // the blocks, in the order of the text, not overlapping
    private val blocks = ArrayList<Block>()
    // the chars asked for and not in yet (start, end, sequence), in the coordinates of now
    private val pending = ArrayList<LongArray>()
    private var useCounter = 0L

    val size: Int get() = blocks.size

    /** The blocks that reach into the chars [start, end), in the order of the text. */
    fun blocksIn(start: Int, end: Int): List<Block> {
        if (blocks.isEmpty() || end <= start) return emptyList()
        var i = firstEndingAfter(start)
        val result = ArrayList<Block>(2)
        val used = ++useCounter
        while (i < blocks.size && blocks[i].start < end) {
            val block = blocks[i]
            block.lastUsed = used
            result += block
            i++
        }
        return result
    }

    /** Whether all of the chars [start, end) are in blocks that are not stale. */
    fun isCovered(start: Int, end: Int): Boolean {
        var at = start
        var i = firstEndingAfter(start)
        while (at < end) {
            val block = blocks.getOrNull(i) ?: return false
            if (block.start > at || block.stale) return false
            at = block.end
            i++
        }
        return true
    }

    /**
     * The chars of [start, end) that are to be asked for: those in no block or in a
     * stale one, and not asked for already; as blocks of [blockChars] (aligned to them,
     * cut to the room between the blocks there are). Each is noted as asked for with
     * the sequence [nextSequence] gives; [put] or [dropped] says it is done.
     */
    fun toRequest(start: Int, end: Int, nextSequence: () -> Long): List<LongArray> {
        val result = ArrayList<LongArray>()
        var at = start
        while (at < end) {
            // the next char that is not in a block that is not stale, nor asked for
            val covering = blockAt(at)
            if (covering != null && (!covering.stale || covering.awaitsParse)) { at = covering.end; continue }
            val asked = pending.firstOrNull { it[0] <= at && at < it[1] }
            if (asked != null) { at = asked[1].toInt(); continue }
            // the room around it: up to the blocks (not stale) and requests around
            var from = (at / blockChars) * blockChars
            var to = from + blockChars
            for (block in blocks) {
                if (block.stale && !block.awaitsParse) continue
                if (block.end <= at) from = maxOf(from, block.end)
                if (block.start > at) to = minOf(to, block.start)
            }
            for (other in pending) {
                if (other[1] <= at) from = maxOf(from, other[1].toInt())
                if (other[0] > at) to = minOf(to, other[0].toInt())
            }
            val request = longArrayOf(from.toLong(), to.toLong(), nextSequence())
            pending += request
            result += request
            at = to
        }
        return result
    }

    /** What was asked for with [sequence] will not come (dropped, or of no use any more). */
    fun dropped(sequence: Long) {
        pending.removeAll { it[2] == sequence }
    }

    /**
     * A block was found (already moved along with the edits made since it was asked
     * for). It takes the place of what it covers, but for blocks found later that are not
     * stale: those stay, and it is cut around them.
     */
    fun put(block: Block) {
        dropped(block.sequence)
        if (block.end <= block.start) return
        // the parts of it that are not covered by blocks found later
        val pieces = ArrayList<IntArray>()
        var at = block.start
        for (other in blocks) {
            if (other.end <= at || other.start >= block.end) continue
            if (other.sequence > block.sequence && !other.stale) {
                if (other.start > at) pieces += intArrayOf(at, other.start)
                at = maxOf(at, other.end)
            }
        }
        if (at < block.end) pieces += intArrayOf(at, block.end)
        for (piece in pieces) insertCut(block, piece[0], piece[1])
        evict()
    }

    // [block], cut to [from, to), in place of whatever it overlaps there
    private fun insertCut(block: Block, from: Int, to: Int) {
        if (to <= from) return
        // what it overlaps is cut back or goes
        val iterator = blocks.listIterator()
        val tails = ArrayList<Block>()
        while (iterator.hasNext()) {
            val other = iterator.next()
            if (other.end <= from || other.start >= to) continue
            when {
                other.start < from && other.end > to -> {
                    // around it: the part after it becomes a block of its own
                    tails += other.copyCut(to, other.end)
                    other.end = from
                }
                other.start < from -> other.end = from
                other.end > to -> other.start = to
                else -> iterator.remove()
            }
        }
        val piece = if (from == block.start && to == block.end) block else block.copyCut(from, to)
        piece.lastUsed = ++useCounter
        insertSorted(piece)
        for (tail in tails) insertSorted(tail)
    }

    private fun Block.copyCut(from: Int, to: Int): Block =
        Block(from, to, count, starts, ends, ranks, styles, sequence).also {
            it.stale = stale
            it.awaitsParse = awaitsParse
            it.lastUsed = lastUsed
        }

    private fun insertSorted(block: Block) {
        var i = blocks.size
        while (i > 0 && blocks[i - 1].start > block.start) i--
        blocks.add(i, block)
    }

    // too many: the ones used longest ago go
    private fun evict() {
        while (blocks.size > maxBlocks) {
            var oldest = 0
            for (i in blocks.indices) if (blocks[i].lastUsed < blocks[oldest].lastUsed) oldest = i
            blocks.removeAt(oldest)
        }
    }

    /**
     * The text [start, oldEnd) was replaced by [start, newEnd): the blocks after it move
     * along, those it touches follow it as tree-sitter moves nodes and become stale.
     */
    fun onEdit(start: Int, oldEnd: Int, newEnd: Int) {
        val delta = newEnd - oldEnd
        val iterator = blocks.listIterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (move(block, start, oldEnd, newEnd)) {
                block.stale = true
                block.awaitsParse = true
            }
            if (block.end <= block.start) iterator.remove()
        }
        // two blocks the edit touched may now overlap: the later one gives way
        for (i in 1 until blocks.size) {
            if (blocks[i].start < blocks[i - 1].end) blocks[i].start = blocks[i - 1].end
        }
        blocks.removeAll { it.end <= it.start }
        // what is asked for moves along too: it comes in the coordinates it was asked
        // in, and is moved along by the engine
        for (request in pending) {
            request[0] = movedStart(request[0].toInt(), start, oldEnd, delta).toLong()
            request[1] = movedEnd(request[1].toInt(), start, oldEnd, newEnd, delta).toLong()
        }
    }

    /** The blocks that reach into the chars [start, end] (inclusive) are to be found again. */
    fun markStale(start: Int, end: Int) {
        for (block in blocks) {
            if (block.end > start && block.start <= end) {
                block.stale = true
                block.awaitsParse = false
            }
        }
    }

    /** Every block is to be found again (another theme, another tree). */
    fun markAllStale() {
        for (block in blocks) {
            block.stale = true
            block.awaitsParse = false
        }
    }

    /** No parse follows (it failed): the blocks waiting for one are found as they are. */
    fun stopAwaitingParse() {
        for (block in blocks) block.awaitsParse = false
    }

    fun clear() {
        blocks.clear()
        pending.clear()
    }

    // the first block that ends after [offset]
    private fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = blocks.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (blocks[mid].end <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    private fun blockAt(offset: Int): Block? =
        blocks.getOrNull(firstEndingAfter(offset))?.takeIf { it.start <= offset }

    companion object {
        /**
         * Moves [block] along with the edit [start, oldEnd) -> [start, newEnd): one after it
         * moves, one it is in (or that ends where text is typed in) follows it as tree-sitter
         * moves nodes. Returns whether the edit touched the block (it is then stale).
         */
        fun move(block: Block, start: Int, oldEnd: Int, newEnd: Int): Boolean {
            val delta = newEnd - oldEnd
            return when {
                // before it
                block.end < start -> false
                // after it (also one that starts where text is typed in: it moves on)
                block.start >= oldEnd -> {
                    if (delta != 0) {
                        block.start += delta
                        block.end += delta
                        for (j in 0 until block.count) {
                            block.starts[j] += delta
                            block.ends[j] += delta
                        }
                    }
                    false
                }
                // it is in it, or it ends where text is typed in (and takes it)
                else -> {
                    for (j in 0 until block.count) {
                        block.starts[j] = movedStart(block.starts[j], start, oldEnd, delta)
                        block.ends[j] = movedEnd(block.ends[j], start, oldEnd, newEnd, delta)
                        if (block.ends[j] < block.starts[j]) block.ends[j] = block.starts[j]
                    }
                    block.start = movedStart(block.start, start, oldEnd, delta)
                    block.end = movedEnd(block.end, start, oldEnd, newEnd, delta)
                    true
                }
            }
        }

        // where a position at which something starts is after the edit
        fun movedStart(position: Int, start: Int, oldEnd: Int, delta: Int): Int = when {
            position < start -> position
            position >= oldEnd -> position + delta
            else -> start
        }

        // Where a position at which something ends is after the edit. What ends where
        // text is typed in takes the text (tree-sitter grows such a node too): a letter
        // typed at the end of a name has the color of the name at once.
        fun movedEnd(position: Int, start: Int, oldEnd: Int, newEnd: Int, delta: Int): Int = when {
            position < start -> position
            position >= oldEnd -> position + delta
            else -> newEnd
        }
    }
}

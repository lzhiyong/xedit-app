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
 * The tab stops of a snippet that was put in (a completion with `${1:arg}`, `$2`, `$0`),
 * as nvim's snippet engines keep them: Tab goes to the next one, its placeholder
 * selected, Shift+Tab back; reaching the last one (`$0`, or the end of the snippet) ends
 * it. See EditorView.applyCompletion.
 *
 * The stops are offsets in the document ([start], [end] exclusive), kept in step with
 * every edit ([onEdit]): what is typed into the stop the cursor is at makes it grow,
 * what is put in before a stop moves it. [isInside] tells whether an offset is still in
 * the snippet; once the cursor has left it, the session is over (see EditorView).
 */
internal class SnippetSession(stops: List<Pair<Int, Int>>) {

    private val starts = IntArray(stops.size) { stops[it].first }
    private val ends = IntArray(stops.size) { stops[it].second.coerceAtLeast(stops[it].first) }

    // the whole snippet, from its first char to its last
    private var wholeStart = starts.minOrNull() ?: 0
    private var wholeEnd = ends.maxOrNull() ?: 0

    /** The stop the cursor is at: 0 is where it was put first. */
    var current = 0
        private set

    val size: Int get() = starts.size

    /** Whether [current] is the last stop: Tab there ends the snippet. */
    val isAtLast: Boolean get() = current >= starts.size - 1

    fun start(index: Int): Int = starts[index]
    fun end(index: Int): Int = ends[index]

    /** Whether [offset] is in the stop the cursor is at (its ends count). */
    fun isInCurrent(offset: Int): Boolean = offset in starts[current]..ends[current]

    /** Whether [offset] is in the snippet (its ends count). */
    fun isInside(offset: Int): Boolean = offset in wholeStart..wholeEnd

    /** The next stop (made [current]); null after the last. */
    fun next(): Int? {
        if (current + 1 >= starts.size) return null
        current++
        return current
    }

    /** The stop before (made [current]); null before the first. */
    fun previous(): Int? {
        if (current <= 0) return null
        current--
        return current
    }

    /**
     * [deleted] chars at [offset] were replaced with [inserted] ones. The stop the cursor
     * is at takes an edit in it or at its ends; another stop moves with what is put in
     * before it; one that the edit cut into is made to fit around it.
     */
    fun onEdit(offset: Int, deleted: Int, inserted: Int) {
        val editEnd = offset + deleted
        val delta = inserted - deleted
        for (i in starts.indices) {
            val s = starts[i]
            val e = ends[i]
            when {
                // typed into the stop being filled in (or over its placeholder)
                i == current && offset >= s && editEnd <= e -> ends[i] = e + delta
                // before it: it moves along
                editEnd <= s -> {
                    starts[i] = s + delta
                    ends[i] = e + delta
                }
                // after it: nothing
                offset >= e -> Unit
                // across one of its ends
                else -> {
                    val newStart = if (offset < s) offset else s
                    val newEnd = if (editEnd >= e) offset + inserted else e + delta
                    starts[i] = newStart
                    ends[i] = newEnd.coerceAtLeast(newStart)
                }
            }
        }
        when {
            offset >= wholeStart && editEnd <= wholeEnd -> wholeEnd += delta
            editEnd <= wholeStart -> {
                wholeStart += delta
                wholeEnd += delta
            }
            offset >= wholeEnd -> Unit
            else -> {
                wholeStart = minOf(wholeStart, offset)
                wholeEnd = maxOf(wholeStart, if (editEnd >= wholeEnd) offset + inserted else wholeEnd + delta)
            }
        }
    }
}

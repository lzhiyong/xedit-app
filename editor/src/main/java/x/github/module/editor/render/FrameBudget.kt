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

/**
 * How much time one frame may spend on work that can wait for the next one: styling
 * rows that are recorded and kept (see TextRenderer), asking the structure providers
 * (indent guides, folding ranges).
 *
 * Dragging the scrollbar thumb through a large file brings a whole screen of new rows
 * on every frame. Styled with tree-sitter, they take far longer than a frame. With a
 * budget, a frame styles rows until the budget is spent and paints the rest plainly;
 * those rows are recorded again on the next frames, styled, until all are. Scrolling
 * stays smooth, and the colors follow a few frames later.
 *
 * What waits never takes away what is on screen: only a row that shows nothing yet is
 * painted plainly; one that shows its text keeps showing it (with its older styles)
 * until it is styled again, and an edited row is styled at once (see RowCache). The
 * structure (indent guides, fold markers) that waits is drawn as it was meanwhile. So
 * typing never makes the text, the guides or the markers flicker.
 *
 * At least the first piece of work of a frame is always done ([isSpent] is false until
 * something was measured), so every frame makes progress. Main thread.
 */
class FrameBudget(budgetMillis: Float) {

    private var budgetNanos: Long = (budgetMillis * 1_000_000).toLong()

    /** The time a frame may spend, in milliseconds. */
    var budgetMillis: Float
        get() = budgetNanos / 1_000_000f
        set(value) {
            budgetNanos = (value * 1_000_000).toLong()
        }

    private var spentNanos = 0L

    /** Whether some work was left for a later frame: that frame has to be asked for. */
    var hasDeferred: Boolean = false
        private set

    /** A frame starts: nothing spent, nothing left. */
    fun reset() {
        spentNanos = 0L
        hasDeferred = false
    }

    /** Whether the frame has no time left for work that can wait. */
    val isSpent: Boolean get() = spentNanos >= budgetNanos

    /** Work was left for a later frame. */
    fun defer() {
        hasDeferred = true
    }

    /** Runs [work], and counts the time it took against the budget. */
    inline fun <T> measure(work: () -> T): T {
        val start = System.nanoTime()
        try {
            return work()
        } finally {
            spend(System.nanoTime() - start)
        }
    }

    fun spend(nanos: Long) {
        spentNanos += nanos
    }
}

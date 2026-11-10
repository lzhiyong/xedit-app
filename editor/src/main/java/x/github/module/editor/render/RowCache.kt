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
 * The rows that are recorded and kept (see TextRenderer), by row number, and what the
 * recording of each one still shows. [N] is whatever a recording is kept in (a
 * RenderNode); this class only does the bookkeeping, so that it can be tested on its own.
 *
 * ## Why it matters what a recording still shows
 *
 * Styling a row can wait for a later frame when the frame has no time left (see
 * FrameBudget): the row is then recorded plainly, and styled a few frames later. That is
 * right for a row that has just come on screen (a fast scroll): it had nothing to show.
 * It is wrong for a row that already shows its text styled: recorded plainly, it would
 * lose its colors for a frame and get them back on the next one, a flicker on every key
 * press. So what is done with a row depends on what it shows ([State], [actionFor]):
 *
 *  - nothing yet (just came on screen): record it, styled if there is time ([Action.RECORD]);
 *  - its text, styled, but the text has changed (an edit): record it now, styled
 *    ([Action.RECORD_STYLED]); its old recording shows text that is not there any more;
 *  - its text, but only plain, or with styles that may be out of date (the parse after
 *    an edit is done): keep showing that, and style it when a frame has time
 *    ([Action.RESTYLE]); nothing changes on screen until the new styles are there.
 *
 * ## Edits move rows, they do not change them
 *
 * A line typed in or joined moves every row below it. Those rows show what they did,
 * one row further down or up: [onEdited] moves their recordings along instead of having
 * them recorded again. Only the rows of the edited lines are recorded again.
 *
 * Main thread.
 */
internal class RowCache<N>(
    // how many recordings to keep: at least the rows on one screen
    private val capacity: () -> Int,
    private val create: () -> N
) {

    /** What the recording of a row shows. */
    enum class State {
        /** Nothing of this row: it was just given to it. */
        NEW,
        /** The text of the row, not styled: its styling was left for a later frame. */
        PLAIN,
        /** The text of the row, styled as it is. */
        CURRENT,
        /** The text of the row, styled with styles that may have changed since. */
        STALE_STYLES,
        /** Styled text that is not that of the row any more: the row was edited. */
        STALE_TEXT
    }

    /** What has to be done with a row before it is drawn. */
    enum class Action {
        /** Nothing: its recording is up to date. */
        NONE,
        /** Record it; its styling may wait for a later frame (it shows nothing yet). */
        RECORD,
        /** Record it now, styled: what it shows is out of date. */
        RECORD_STYLED,
        /** Record it styled when the frame has time; until then it shows what it does. */
        RESTYLE
    }

    class Entry<N> internal constructor(val node: N) {
        /** The row it is the recording of (1-based); 0 while it is of none. */
        var row: Int = 0
            internal set
        var state: State = State.NEW
            internal set
    }

    // the entries given to a row, by their row
    private val byRow = HashMap<Int, Entry<N>>()
    // the entries of no row, to be given to the next ones that need one
    private val spare = ArrayList<Entry<N>>()

    // The rows of the edits since the last frame (inclusive; empty while first > last).
    // A row among them that has no recording yet is new text in the place of styled
    // text (a line typed in, one pulled up from below): it is styled when it is
    // recorded, like the edited rows.
    private var urgentFirst = Int.MAX_VALUE
    private var urgentLast = Int.MIN_VALUE

    /** Whether there is any recording, i.e. anything to invalidate. */
    val isEmpty: Boolean get() = byRow.isEmpty() && spare.isEmpty()

    /** Every recording kept, of a row or not. */
    fun nodes(): List<N> = byRow.values.map { it.node } + spare.map { it.node }

    /** The entry of [row], if there is one. */
    fun peek(row: Int): Entry<N>? = byRow[row]

    /**
     * The entry of [row]: the one it has, or another one given to it, which shows
     * nothing of it ([State.NEW]). That is taken from the spare ones, or from a row that is
     * not in [visible], or made when there are fewer than the capacity.
     */
    fun entryFor(row: Int, visible: IntRange): Entry<N> {
        byRow[row]?.let { return it }
        val entry = if (spare.isNotEmpty()) {
            spare.removeAt(spare.lastIndex)
        } else if (byRow.size < capacity()) {
            Entry(create())
        } else {
            takeOffScreen(visible) ?: Entry(create())
        }
        entry.row = row
        entry.state = State.NEW
        byRow[row] = entry
        return entry
    }

    // The entry of a row that is not on screen, the one farthest from it; null if
    // every row that has one is on screen.
    private fun takeOffScreen(visible: IntRange): Entry<N>? {
        var farthest: Entry<N>? = null
        var distance = 0
        for (entry in byRow.values) {
            val row = entry.row
            val away = when {
                row < visible.first -> visible.first - row
                row > visible.last -> row - visible.last
                else -> continue
            }
            if (away > distance) {
                farthest = entry
                distance = away
            }
        }
        farthest?.let { byRow.remove(it.row) }
        return farthest
    }

    /**
     * What is to be done with [entry] before it is drawn. [hasRecording]: whether its
     * node still holds what was recorded (a node can lose it).
     */
    fun actionFor(entry: Entry<N>, hasRecording: Boolean): Action {
        if (!hasRecording) return if (isUrgent(entry.row)) Action.RECORD_STYLED else Action.RECORD
        return when (entry.state) {
            State.NEW -> if (isUrgent(entry.row)) Action.RECORD_STYLED else Action.RECORD
            State.STALE_TEXT -> Action.RECORD_STYLED
            // plain where an edit just moved it (from below the bottom edge, in the place
            // of styled text): styled now; elsewhere it may go on waiting
            State.PLAIN -> if (isUrgent(entry.row)) Action.RECORD_STYLED else Action.RESTYLE
            State.STALE_STYLES -> Action.RESTYLE
            State.CURRENT -> Action.NONE
        }
    }

    /** [entry] was recorded: [styled], or plainly (its styling was left for later). */
    fun recorded(entry: Entry<N>, styled: Boolean) {
        entry.state = if (styled) State.CURRENT else State.PLAIN
    }

    private fun isUrgent(row: Int) = row in urgentFirst..urgentLast

    private fun addUrgent(first: Int, last: Int) {
        urgentFirst = Math.min(urgentFirst, first)
        urgentLast = Math.max(urgentLast, last)
    }

    /** A frame was drawn: the rows of the edits before it are no different from others any more. */
    fun endFrame() {
        urgentFirst = Int.MAX_VALUE
        urgentLast = Int.MIN_VALUE
    }

    // the text of the row of [entry] changed
    private fun textChanged(entry: Entry<N>) {
        entry.state = when (entry.state) {
            // styled: it must not lose its colors, record it now, styled
            State.CURRENT, State.STALE_STYLES, State.STALE_TEXT -> State.STALE_TEXT
            // nothing, or plain: plain again does not show a change, it may wait
            State.NEW, State.PLAIN -> State.NEW
        }
    }

    // the styles of the row of [entry] may have changed, its text did not
    private fun stylesChanged(entry: Entry<N>) {
        if (entry.state == State.CURRENT) entry.state = State.STALE_STYLES
    }

    /**
     * The text of the rows [firstRow] to [lastRow] (inclusive) changed: they are recorded
     * again, styled now where they showed styled text, as are rows given to them later
     * before the next frame.
     */
    fun invalidateText(firstRow: Int, lastRow: Int) {
        if (lastRow < firstRow) return
        for (entry in byRow.values) {
            if (entry.row in firstRow..lastRow) textChanged(entry)
        }
        addUrgent(firstRow, lastRow)
    }

    /** Every row is to be recorded again, as by [invalidateText] (another text size, color, ...). */
    fun invalidateAllText() {
        for (entry in byRow.values) textChanged(entry)
        addUrgent(Int.MIN_VALUE, Int.MAX_VALUE)
    }

    /**
     * The styles of the rows [firstRow] to [lastRow] may have changed, their text did not:
     * they keep showing what they do until they are styled again, when a frame has time.
     */
    fun invalidateStyles(firstRow: Int, lastRow: Int) {
        if (lastRow < firstRow) return
        for (entry in byRow.values) {
            if (entry.row in firstRow..lastRow) stylesChanged(entry)
        }
    }

    /** As [invalidateStyles], for every row. */
    fun invalidateAllStyles() {
        for (entry in byRow.values) stylesChanged(entry)
    }

    /**
     * One edit: the rows [firstRow] to [lastRowBefore] were replaced by the rows
     * [firstRow] to [lastRowAfter] (inclusive; `lastRow < firstRow` for none). The rows of
     * the edited lines are recorded again, styled now; those below them move along with
     * what they show, by `lastRowAfter - lastRowBefore` rows.
     */
    fun onEdited(firstRow: Int, lastRowBefore: Int, lastRowAfter: Int) {
        val delta = lastRowAfter - lastRowBefore
        val below = ArrayList<Entry<N>>()
        val released = ArrayList<Entry<N>>()
        for (entry in byRow.values) {
            val row = entry.row
            when {
                row < firstRow -> {}
                // a row of the edited lines, which is still one: other text
                row <= lastRowBefore && row <= lastRowAfter -> textChanged(entry)
                // a row of the edited lines that is not one any more (lines were
                // joined): its place goes to a row from below
                row <= lastRowBefore -> released += entry
                else -> below += entry
            }
        }
        for (entry in released) {
            byRow.remove(entry.row)
            entry.row = 0
            entry.state = State.NEW
            spare += entry
        }
        if (delta != 0) {
            for (entry in below) byRow.remove(entry.row)
            for (entry in below) {
                entry.row += delta
                byRow[entry.row] = entry
            }
            // the rows of earlier edits not drawn yet move along too
            if (urgentFirst <= urgentLast) {
                if (urgentFirst > lastRowBefore) urgentFirst = shifted(urgentFirst, delta)
                if (urgentLast > lastRowBefore) urgentLast = shifted(urgentLast, delta)
            }
        }
        // The edited rows are styled when they are recorded. With lines added or taken
        // away, so are the rows below that have no styled recording: they show what the
        // edit brought on screen (joined lines pull up a line from below the bottom
        // edge, recorded plainly or not at all), in the place of styled text. The rows
        // with a styled recording are not concerned: they show their own text, moved.
        addUrgent(firstRow, if (delta == 0) lastRowAfter else Int.MAX_VALUE)
    }

    // a row moved by [delta], the open ends of a range staying open
    private fun shifted(row: Int, delta: Int): Int =
        if (row == Int.MAX_VALUE || row == Int.MIN_VALUE) row else row + delta

    /** Forgets every recording, e.g. when the whole document was replaced; returns their nodes. */
    fun clear(): List<N> {
        val nodes = nodes()
        byRow.clear()
        spare.clear()
        endFrame()
        return nodes
    }
}

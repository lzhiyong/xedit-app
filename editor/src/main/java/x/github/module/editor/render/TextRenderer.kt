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

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi

import x.github.module.editor.EditorGeometry
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.layout.TabStops
import x.github.module.editor.layout.UnwrappedLayout
import x.github.module.editor.model.EditorTextSource

/**
 * Draws the visible rows of text. A row is one on-screen line: a whole buffer line
 * without word wrap, a piece of one with it.
 *
 * There is one loop over the visible rows, for both kinds of layout, and two ways of
 * getting a row onto the canvas:
 *
 *  - **cached**: the row is recorded once into a [RenderNode] of its own and only
 *    replayed afterwards, so scrolling does not run the (possibly expensive, think
 *    syntax highlighting) painting of a row again. Needs Android 10 and a hardware
 *    accelerated canvas.
 *  - **direct**: the row is painted straight onto the canvas, every time. Works
 *    everywhere, and is what happens on older devices and whenever the view is drawn
 *    into a bitmap (a screenshot, printing, a software layer).
 *
 * *What* a row looks like is not decided here. Highlights, the line number and the
 * text itself are painted through [Painter], which is where EditorView (and its
 * subclasses, e.g. a syntax highlighter) plug in their own drawing.
 */
class TextRenderer(
    private val host: TextRenderer.Host,
    private val source: EditorTextSource,
    private val metrics: TextMetrics,
    private val painter: TextRenderer.Painter,
    // only used to find the visible columns of a long line that is not wrapped
    private val textPaint: TextPaint
) {

    /** On top of geometry: the current layout, and how many lines to cache. */
    interface Host : EditorGeometry {
        val textLayout: TextLayout
        /** How many per-row RenderNodes to keep. Roughly the rows on one screen. */
        val nodeCacheSize: Int
    }

    /**
     * Paints the pieces of one line. In every method `bufferLine` / `line` is the real line
     * number in the buffer and `visualLine` the visual (on-screen) line; they only differ
     * in word wrap mode.
     */
    interface Painter {
        /** Backgrounds: the block a line is in, current line, selection, search matches. */
        fun drawRegionHighlight(canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int)

        /**
         * The number of [line] on the first of its rows, on the baseline [y]. Drawn on
         * every frame (not recorded with the row): the cursor's line has its own color.
         */
        fun drawLineNumber(canvas: Canvas, line: Int, x: Float, y: Float)

        /**
         * The text of a row. [mayDefer]: the row is recorded and kept (cached), and has
         * nothing on screen yet, so its styling may be left for a later frame when the
         * frame has no time left (see FrameBudget); the row is then painted plainly for
         * now. A row that shows styled text is never asked with [mayDefer]: painted
         * plainly it would flicker.
         *
         * @return false if the styling was left out: the row is styled on a later frame
         */
        fun drawText(
            canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, x: Float, y: Float,
            mayDefer: Boolean
        ): Boolean

        /**
         * Whether text[startOffset, endOffset) of [line], a row that can wait (it shows
         * its text already, plainly or with styles that may be out of date), is to be
         * styled now: the frame has time left, and the styles are there (see
         * LineStyler.isStyleReady). False: it keeps showing what it does; the painter has
         * another frame asked for if time was what was missing.
         */
        fun mayStyleNow(line: Int, startOffset: Int, endOffset: Int): Boolean
    }

    companion object {
        // rows longer than this many chars are painted directly, see draw()
        private const val MAX_CACHED_ROW_LENGTH = 4096
    }

    // The recorded rows (hardware accelerated, Android 10 and later), and what each
    // one still shows: see RowCache. Created on first use, which is on such a device.
    private var rowCache: RowCache<RenderNode>? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun rows(): RowCache<RenderNode> = rowCache ?: RowCache(
        capacity = { host.nodeCacheSize },
        create = {
            RenderNode(null).also { it.setPosition(0, 0, Int.MAX_VALUE, metrics.lineHeight) }
        }
    ).also { rowCache = it }

    /** Whether any line has been recorded yet, i.e. whether there is anything to invalidate. */
    val hasRenderNodes: Boolean get() = rowCache?.isEmpty == false

    /** Draws the visible rows. The canvas must already be translated by the padding. */
    fun draw(canvas: Canvas) {
        // read the layout once: it can be swapped from a worker thread
        val layout = host.textLayout
        // Without wrapping or folding a row is a line; otherwise the layout says
        // which part of which line a row shows.
        val rowsAreLines = layout is UnwrappedLayout
        val wrapped = layout.isWrapping
        val cached = canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val lineCount = source.getLineCount()
        // Without wrapping a row is a line, and the text alone says how many there
        // are. Not asking the layout matters while a large file is still being laid
        // out in the background: its lines can be drawn right away.
        val rowCount = if (rowsAreLines) lineCount else layout.count()
        // where the text starts, right of the line numbers
        val textLeft = metrics.gutterWidth(lineCount)

        val firstRow = firstVisibleRow()
        // the rows on screen, which keep their recordings
        var lastRow = firstRow
        while (isVisible(lastRow + 1)) lastRow++
        val visible = firstRow..lastRow

        var row = firstRow
        while (row <= rowCount && isVisible(row)) {
            // which part of which buffer line this row shows
            val bufferLine: Int
            val rowStart: Int
            val rowEnd: Int
            val isFirstRowOfLine: Boolean
            val text: String

            if (!rowsAreLines) {
                val result = layout.getLineResult(row)
                bufferLine = result.line
                // the layout can be a step behind the text for a moment
                if (bufferLine > lineCount) break
                text = source.getLine(bufferLine)
                rowStart = Math.min(result.start, text.length)
                rowEnd = Math.min(result.end, text.length)
                isFirstRowOfLine = row == layout.getStartIndex(bufferLine) + 1
            } else {
                // not wrapped: the row is the whole line
                bufferLine = row
                if (bufferLine > lineCount) break
                text = source.getLine(bufferLine)
                rowStart = 0
                rowEnd = text.length
                isFirstRowOfLine = true
            }

            // A row of ordinary length is recorded whole, and scrolling sideways costs
            // nothing. A very long one (a minified file is one line of megabytes) is
            // not worth recording: painting just the columns on screen is far less
            // work than recording all of it once.
            if (cached && rowEnd - rowStart <= MAX_CACHED_ROW_LENGTH) {
                drawRowCached(canvas, row, visible, bufferLine, text, rowStart, rowEnd, isFirstRowOfLine, textLeft)
            } else {
                drawRowDirect(canvas, row, bufferLine, text, rowStart, rowEnd, isFirstRowOfLine, textLeft, wrapped)
            }

            // continue to next row
            row++
        }
        // the rows of the edits before this frame have been drawn
        if (cached) rows().endFrame()
    }

    // #region render nodes

    /** Frees every cached display list, e.g. when the whole document was replaced. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun recycleRenderNodes() {
        // free the node memory
        rowCache?.clear()?.forEach { it.discardDisplayList() }
    }

    /**
     * Every cached row is to be recorded again: how text is drawn changed (its size,
     * its color, the room for the line numbers, ...). A row that showed styled text is
     * recorded styled at once: it must not show its text plainly for a frame.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun invalidateRenderNodes() {
        val cache = rowCache ?: return
        cache.invalidateAllText()
        // the height of a row may have changed with it
        for (node in cache.nodes()) node.setPosition(0, 0, Int.MAX_VALUE, metrics.lineHeight)
    }

    /**
     * The text of the cached rows [firstRow] to [lastRow] (1-based, inclusive) changed:
     * they are recorded again, styled at once where they showed styled text. Only the rows
     * on screen are cached; the others cost nothing.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun invalidateRows(firstRow: Int, lastRow: Int) {
        rowCache?.invalidateText(firstRow, lastRow)
    }

    /**
     * The styles of the cached rows [firstRow] to [lastRow] (1-based, inclusive) may have
     * changed, their text did not (a parse after an edit is done). They keep showing what
     * they do until they are styled again, on this frame or, if it has no time left, on
     * one of the next: nothing flickers.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun invalidateRowStyles(firstRow: Int, lastRow: Int) {
        rowCache?.invalidateStyles(firstRow, lastRow)
    }

    /** As [invalidateRowStyles], for every cached row (another theme, the first parse). */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun invalidateAllRowStyles() {
        rowCache?.invalidateAllStyles()
    }

    /**
     * An edit replaced the rows [firstRow] to [lastRowBefore] with the rows [firstRow] to
     * [lastRowAfter] (1-based, inclusive): those are recorded again, styled at once; the
     * rows below them keep their recordings, which move along with them.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun onRowsEdited(firstRow: Int, lastRowBefore: Int, lastRowAfter: Int) {
        rowCache?.onEdited(firstRow, lastRowBefore, lastRowAfter)
    }

    // Records the row into its node if needed, then draws the node where the row is.
    // What is recorded is in the node's own coordinates: the row starts at y = 0.
    // [record] gets whether the styling may be left for a later frame, and returns
    // whether the row came out styled.
    @RequiresApi(Build.VERSION_CODES.Q)
    private inline fun drawCached(
        canvas: Canvas, row: Int, visible: IntRange, mayStyleNow: () -> Boolean, record: (Canvas, Boolean) -> Boolean
    ) {
        val cache = rows()
        val entry = cache.entryFor(row, visible)
        val node = entry.node

        // whether to record it, and whether its styling may wait (null: not now)
        val mayDefer: Boolean? = when (cache.actionFor(entry, node.hasDisplayList())) {
            RowCache.Action.NONE -> null
            // it shows nothing yet: plain for now is no change on screen
            RowCache.Action.RECORD -> true
            // it shows text that is not there any more: now, styled
            RowCache.Action.RECORD_STYLED -> false
            // it shows its text (plain, or with older styles): styled when there is time,
            // until then what it shows stays
            RowCache.Action.RESTYLE -> if (mayStyleNow()) false else null
        }

        if (mayDefer != null) {
            // start recording
            val recordingCanvas = node.beginRecording()
            var styled = false
            try {
                styled = record(recordingCanvas, mayDefer)
            } finally {
                // end recording
                node.endRecording()
                cache.recorded(entry, styled)
            }
        }

        // translate to target position
        node.setTranslationY((row - 1f) * metrics.lineHeight)
        // draw render node
        canvas.drawRenderNode(node)
    }

    // #endregion

    // #region rows

    // the first row that is (partly) visible
    private fun firstVisibleRow() = host.viewScrollY / metrics.lineHeight + 1

    // whether the row is still above the bottom of the viewport (plus one spare)
    private fun isVisible(row: Int): Boolean {
        val lineHeight = metrics.lineHeight
        return row * lineHeight <= host.viewScrollY + host.viewHeight + lineHeight
    }

    // The text of one row. This is the one place that says what a row's text is;
    // both ways of drawing end up here, they differ only in the canvas and the
    // coordinates they pass.
    //
    // The line number is not part of it: it is drawn on every frame, like the
    // backgrounds, because the number of the cursor's line looks different (see
    // Painter.drawLineNumber) and the cursor moves without the row changing.
    private fun paintRow(
        canvas: Canvas, bufferLine: Int, text: String, start: Int, end: Int, x: Float, baseline: Float,
        mayDefer: Boolean
    ): Boolean = painter.drawText(canvas, text, bufferLine, start, end, x, baseline, mayDefer)

    // Cached: the backgrounds are drawn every frame (they follow the cursor and the
    // selection), the row itself is recorded once and replayed.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawRowCached(
        canvas: Canvas, row: Int, visible: IntRange, bufferLine: Int, text: String, start: Int, end: Int,
        isFirstRowOfLine: Boolean, textLeft: Int
    ) {
        painter.drawRegionHighlight(canvas, bufferLine, row, start, end)
        if (isFirstRowOfLine) {
            painter.drawLineNumber(canvas, bufferLine, 0f, metrics.baselineOf(row).toFloat())
        }

        drawCached(canvas, row, visible, { painter.mayStyleNow(bufferLine, start, end) }) { recordingCanvas, mayDefer ->
            // in the node every row is the first one: its baseline is that of row 1
            paintRow(
                recordingCanvas, bufferLine, text, start, end,
                textLeft.toFloat(), metrics.baselineOf(1).toFloat(), mayDefer
            )
        }
    }

    // receives the width of the text left of the viewport, see drawRowDirect
    private val hiddenWidth = FloatArray(1)

    // Direct: everything is painted onto the canvas, at the row's own position.
    private fun drawRowDirect(
        canvas: Canvas, row: Int, bufferLine: Int, text: String, start: Int, end: Int,
        isFirstRowOfLine: Boolean, textLeft: Int, wrapped: Boolean
    ) {
        var from = start
        var to = end
        var x = textLeft.toFloat()

        // A row with tabs is drawn whole: its tab stops count from the start of the
        // row, which the clipping below would skip.
        if (!wrapped && !TabStops.hasTab(text, start, end)) {
            // A line that is not wrapped can be far wider than the viewport. Painting
            // all of it every frame would be wasted work, so only the columns that
            // are on screen are painted: skip what is left of the viewport, and stop
            // after what fits into it.
            hiddenWidth[0] = 0f
            from = textPaint.breakText(text, true, (host.viewScrollX - textLeft).toFloat(), hiddenWidth)
            to = from + textPaint.breakText(
                text, from, text.length, true, (host.viewScrollX + host.viewWidth).toFloat(), null
            )
            x += hiddenWidth[0]
        }

        painter.drawRegionHighlight(canvas, bufferLine, row, from, to)
        val baseline = metrics.baselineOf(row).toFloat()
        if (isFirstRowOfLine) {
            painter.drawLineNumber(canvas, bufferLine, 0f, baseline)
        }
        // painted again on every frame: styled now, or it would never be
        paintRow(canvas, bufferLine, text, from, to, x, baseline, mayDefer = false)
    }

    // #endregion
}

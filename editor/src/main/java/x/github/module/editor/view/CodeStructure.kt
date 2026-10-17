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

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View

import x.github.module.piecetable.common.Range

import x.github.module.editor.layout.FoldedLayout
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.layout.UnwrappedLayout
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.render.DefaultFoldStyle
import x.github.module.editor.render.FoldStyle
import x.github.module.editor.render.FrameBudget
import x.github.module.editor.structure.BracketMatcher
import x.github.module.editor.structure.BracketPair
import x.github.module.editor.structure.FoldingModel
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.structure.FoldingRangeProvider
import x.github.module.editor.structure.IndentGuide
import x.github.module.editor.structure.IndentGuideProvider

/**
 * The structure of the text in the editor: matching brackets, indent guides and
 * code folding. Asks the providers (see the structure package), keeps their answers
 * until the text, the cursor or the lines on screen change, folds and unfolds, and
 * draws all of it.
 *
 * Taken out of EditorView, which keeps the public methods (setBracketMatcher, fold,
 * ...) and the hooks a subclass overrides (drawFoldMarker, ...) and hands them to
 * this. Everything it needs from the view comes through [Host]. Main thread.
 */
internal class CodeStructure(private val host: Host) {

    /** What the structure needs from the editor. */
    interface Host {
        val view: View
        val document: EditorDocument
        /** The layout of the coordinator, folded lines or not. */
        val coordinatorLayout: TextLayout
        val metrics: TextMetrics
        /** Changes with every edit, see EditorView.getTextVersion. */
        val textVersion: Long

        val cursorLine: Int
        val cursorColumn: Int
        /** The lines of the ends of the selection; null without a selection. */
        val selectionLines: IntRange?
        /** Puts the cursor at [line]:[column], without a selection, and shows it. */
        fun moveCursor(line: Int, column: Int)
        fun scrollCursorIntoView()

        val gutterEnabled: Boolean
        /** The width of the gutter itself, up to the gap before the text. */
        val gutterPanelWidth: Int
        /** Where the text starts. */
        val textLeft: Int
        val lineHeight: Int
        fun baselineOf(row: Int): Int
        val scrollX: Int
        val scrollY: Int
        val viewportHeight: Int
        val maxScrollY: Int
        fun scrollTo(x: Int, y: Int)
        /** Where the fold marker of a row goes when the line numbers are in the rows. */
        val rowFoldMarkerLeft: Int
        val lineNumberColor: Int
        val foldMarkerColor: Int?

        fun refreshTextGeometry()
        fun syncWrapWidth()
        fun invalidateRenderNodes()
        fun updateDrawableBounds()
        fun runOnMainThread(action: () -> Unit)
        fun addOverlay(drawable: Drawable)
        /**
         * What the frame being drawn has left for work that can wait: asking the
         * providers for what is drawn (indent guides, fold markers) is part of it.
         */
        val frameBudget: FrameBudget

        // the hooks of EditorView, which a subclass may override
        fun drawBracketText(canvas: Canvas, bracket: Range, visualLine: Int, x: Float, baseline: Float)
        fun drawBracketUnderline(canvas: Canvas, bracket: Range, visualLine: Int, left: Float, right: Float, baseline: Float)
        fun drawIndentGuide(canvas: Canvas, guide: IndentGuide, x: Float, top: Float, bottom: Float, active: Boolean)
        fun drawFoldMarker(canvas: Canvas, range: FoldingRange, folded: Boolean, left: Float, top: Float, right: Float, bottom: Float)
        fun drawFoldPlaceholder(canvas: Canvas, range: FoldingRange, left: Float, top: Float, right: Float, bottom: Float)
        fun onFoldMarkerClick(range: FoldingRange): Boolean
        fun onFoldChanged(folded: List<FoldingRange>)
    }

    private companion object {
        // providers are asked about blocks of this many lines
        const val WINDOW = 64
    }

    var bracketMatcher: BracketMatcher? = null
        set(value) {
            field = value
            invalidate()
        }
    var indentGuideProvider: IndentGuideProvider? = null
        set(value) {
            field = value
            invalidate()
        }
    var foldingRangeProvider: FoldingRangeProvider? = null
        set(value) {
            field = value
            if (value == null) unfoldAll()
            updateFoldMarkerZone()
            invalidate()
        }

    /** How folding looks, see [FoldStyle]. */
    var foldStyle: FoldStyle = DefaultFoldStyle()
        set(value) {
            field = value
            host.view.invalidate()
        }

    /**
     * Whether a tap on the placeholder of folded lines unfolds them (see
     * EditorView.setFoldPlaceholderClickable). When not, a tap there is a tap on the
     * text: the cursor goes to the end of the line the fold starts on.
     */
    var placeholderClickable = true

    // how the brackets at the cursor are marked, see EditorView.setBracketMatchStyle
    var bracketHighlight = true
    var bracketBold = true
    var bracketUnderline = true

    // Goes up whenever what the providers would answer may have changed without the
    // text having changed: a provider set, invalidate().
    private var generation = 0

    /** Asks the providers again, on the main thread. Any thread. */
    fun invalidate() {
        host.runOnMainThread {
            generation++
            host.view.invalidate()
        }
    }

    // #region the layout, folded lines left out

    private val folds = FoldingModel()
    private var foldedLayout: FoldedLayout? = null

    /** The layout in use: the coordinator's, without rows for folded lines while there are any. */
    fun layout(): TextLayout {
        val layout = host.coordinatorLayout
        if (folds.isEmpty) return layout
        val folded = foldedLayout
        if (folded != null && folded.inner === layout) return folded
        return FoldedLayout(layout, host.document, host.metrics, folds).also { foldedLayout = it }
    }

    // #endregion

    // #region folding

    // the column for fold markers next to the line numbers, while folding is on
    private fun updateFoldMarkerZone() {
        val zone = foldingRangeProvider != null
        if (zone == host.metrics.foldMarkerZone) return
        host.metrics.foldMarkerZone = zone
        host.syncWrapWidth()
        host.invalidateRenderNodes()
        host.updateDrawableBounds()
        host.view.invalidate()
    }

    fun fold(range: FoldingRange): Boolean {
        val lineCount = host.document.getLineCount()
        if (range.startLine < 1 || range.endLine > lineCount) return false
        if (!folds.fold(range)) return false
        // what was in there is no longer where the cursor can be
        val inside = { line: Int -> line > range.startLine && line <= range.endLine }
        val selection = host.selectionLines
        if (inside(host.cursorLine) || (selection != null && (inside(selection.first) || inside(selection.last)))) {
            host.moveCursor(range.startLine, host.document.getLineLength(range.startLine) + 1)
        }
        onFoldsChanged()
        return true
    }

    fun unfold(line: Int): Boolean {
        if (!folds.unfold(line)) return false
        onFoldsChanged()
        return true
    }

    fun toggleFold(line: Int): Boolean {
        if (folds.foldAt(line) != null) return unfold(line)
        val range = foldingRangeAt(line) ?: return false
        return fold(range)
    }

    fun foldAll() {
        val provider = foldingRangeProvider ?: return
        var changed = false
        for (range in provider.getFoldingRanges(host.document.buffer, 1, host.document.getLineCount())) {
            changed = folds.fold(range) or changed
        }
        if (!changed) return
        // the cursor goes to the closest line that can still be seen
        val visible = folds.previousVisibleLine(host.cursorLine)
        if (visible != host.cursorLine) {
            host.moveCursor(visible, host.document.getLineLength(visible) + 1)
        }
        onFoldsChanged()
    }

    fun unfoldAll() {
        if (folds.isEmpty) return
        folds.clear()
        onFoldsChanged()
    }

    /**
     * Folds [ranges] that are still within the text, e.g. those of a session or of
     * the instance state; what no longer fits is left out.
     */
    fun restoreFolds(ranges: List<FoldingRange>) {
        val lineCount = host.document.getLineCount()
        var changed = false
        for (range in ranges) {
            if (range.startLine >= 1 && range.endLine <= lineCount) {
                changed = folds.fold(range) or changed
            }
        }
        if (changed) onFoldsChanged()
    }

    fun isFolded(line: Int): Boolean = folds.foldAt(line) != null

    fun isLineHidden(line: Int): Boolean = folds.isHidden(line)

    fun foldedRanges(): List<FoldingRange> = folds.getFolds()

    /**
     * Follows a change of the text: folds move with their lines, or open. What the
     * providers said is kept, moved along with its lines: it is asked for again (the
     * text version changed), but drawn meanwhile if a frame has no time to ask.
     */
    fun onTextChanged(change: EditorDocument.AppliedChange) {
        if (folds.onTextChanged(change)) onFoldsChanged()
        if (change.insertedLinesCnt != change.deletedLinesCnt) {
            val moved = StructureShift(change)
            if (indentGuides.isNotEmpty()) indentGuides = indentGuides.mapNotNull(moved::guide)
            if (foldingRanges.isNotEmpty()) {
                foldingRanges = foldingRanges.values.mapNotNull(moved::range).associateBy { it.startLine }
            }
        }
    }

    /** Another text was loaded: its folds are not the old ones. */
    fun onTextLoaded() {
        feedback.cancel()
        if (!folds.isEmpty) {
            folds.clear()
            onFoldsChanged()
        }
    }

    // The rows changed: the recorded ones show other lines now, the cursor and the
    // handles stand elsewhere, and the text may have become shorter than the
    // scrolled-to place.
    private fun onFoldsChanged() {
        if (folds.isEmpty) foldedLayout = null
        host.refreshTextGeometry()
        if (host.scrollY > host.maxScrollY) {
            host.scrollTo(host.scrollX, host.maxScrollY)
        }
        host.onFoldChanged(folds.getFolds())
    }

    /**
     * Before a frame: the cursor or an end of the selection got into folded lines
     * (search, go to line, an arrow key, an undo): unfolds them, and brings the
     * cursor into view where it really is.
     */
    fun revealCursor() {
        if (folds.isEmpty) return
        var changed = folds.reveal(host.cursorLine)
        host.selectionLines?.let {
            changed = folds.reveal(it.first) or changed
            changed = folds.reveal(it.last) or changed
        }
        if (changed) {
            onFoldsChanged()
            host.scrollCursorIntoView()
        }
    }

    // how a tapped marker answers: a Material 3 ripple, and the chevron turning
    private val feedback = FoldMarkerFeedback(
        view = host.view,
        addOverlay = { host.addOverlay(it) },
        fallbackColor = { host.lineNumberColor }
    )

    /** How far the marker of the range starting on [line] is turned towards folded, see FoldMarkerFeedback. */
    fun markerFoldedFraction(line: Int, folded: Boolean): Float = feedback.foldedFraction(line, folded)

    /** The color markers and placeholders are drawn in. */
    val markerColor: Int get() = host.foldMarkerColor ?: host.lineNumberColor

    // the row at content y
    private fun rowAtY(y: Int, layout: TextLayout): Int =
        (Math.max(0, y) / Math.max(1, host.lineHeight) + 1).coerceIn(1, Math.max(1, layout.count()))

    // where the placeholder of folded lines starts on a row: after its text
    private fun placeholderLeft(layout: TextLayout, row: Int): Int =
        host.textLeft + layout.getLineWidth(row) + host.metrics.spacingWidth

    /** A tap at content [x], [y]: on a fold marker or a placeholder it folds or unfolds. */
    fun onTap(x: Int, y: Int): Boolean {
        val markerWidth = host.metrics.foldMarkerWidth
        if (markerWidth <= 0) return false
        val layout = layout()
        val row = rowAtY(y, layout)
        val line = layout.getBufferLine(row)

        // the placeholder, at the end of the last row of a folded line
        if (placeholderClickable && folds.foldAt(line) != null && row == layout.getEndIndex(line) + 1) {
            val left = placeholderLeft(layout, row)
            if (x >= left && x < left + foldStyle.placeholderWidth(host.lineHeight)) {
                val unfolded = unfold(line)
                // the marker of the line turns back to open
                if (unfolded) feedback.turn(line, folded = false)
                return unfolded
            }
        }

        // the marker, next to the number on the first row of a line; a little room
        // around it, it is small
        if (layout.getLineStart(row) != 0) return false
        val markerLeft = if (host.gutterEnabled) {
            host.scrollX + host.gutterPanelWidth - markerWidth
        } else {
            host.rowFoldMarkerLeft
        }
        val slop = host.metrics.spacingWidth
        if (x < markerLeft - slop || x >= markerLeft + markerWidth + slop) return false
        val range = foldingRangeAt(line) ?: return false

        // like a Material 3 icon button: a ripple around the marker, and the chevron
        // turns when the tap folded or unfolded the range
        val lineHeight = host.lineHeight
        val centerX = markerLeft + markerWidth / 2f
        val centerY = layout.getStartIndex(line) * lineHeight + lineHeight / 2f
        feedback.press(centerX, centerY, Math.max(markerWidth, lineHeight) * 3 / 5)
        val wasFolded = isFolded(line)
        val handled = host.onFoldMarkerClick(range)
        val isFoldedNow = isFolded(line)
        if (isFoldedNow != wasFolded) {
            feedback.turn(line, isFoldedNow)
        }
        return handled
    }

    // #endregion

    // #region what the providers say, kept

    private fun windowStart(line: Int) = (line - 1) / WINDOW * WINDOW + 1
    private fun windowEnd(line: Int) = Math.min(host.document.getLineCount(), ((line - 1) / WINDOW + 1) * WINDOW)

    /** The buffer lines on screen, first and last. */
    fun linesOnScreen(): IntRange {
        val layout = layout()
        val lineHeight = Math.max(1, host.lineHeight)
        val lineCount = host.document.getLineCount()
        val firstRow = host.scrollY / lineHeight + 1
        val lastRow = (host.scrollY + host.viewportHeight) / lineHeight + 1
        if (layout is UnwrappedLayout) {
            // a row is a line, also while the layout is still being worked out
            return Math.min(firstRow, lineCount)..Math.min(lastRow, lineCount)
        }
        val first = layout.getBufferLine(firstRow).coerceIn(1, lineCount)
        val last = layout.getBufferLine(lastRow).coerceIn(first, lineCount)
        return first..last
    }

    private var bracketPair: BracketPair? = null
    private val bracketPairKey = longArrayOf(-1L, -1L, -1L, -1L)

    /** The bracket at the cursor and its partner; null without a matcher, with a selection, or without a pair. */
    fun bracketPair(): BracketPair? {
        val matcher = bracketMatcher ?: return null
        if (host.selectionLines != null) return null
        val line = host.cursorLine
        val column = host.cursorColumn
        val key = bracketPairKey
        if (key[0] != host.textVersion || key[1] != line.toLong() || key[2] != column.toLong() ||
            key[3] != generation.toLong()
        ) {
            bracketPair = matcher.findBracketPair(host.document.buffer, line, column)
            key[0] = host.textVersion
            key[1] = line.toLong()
            key[2] = column.toLong()
            key[3] = generation.toLong()
        }
        return bracketPair
    }

    private var indentGuides: List<IndentGuide> = emptyList()
    private val indentGuidesKey = longArrayOf(-1L, -1L, -1L, -1L)

    private fun indentGuidesFor(lines: IntRange): List<IndentGuide> {
        val provider = indentGuideProvider ?: return emptyList()
        val from = windowStart(lines.first)
        val to = windowEnd(lines.last)
        val key = indentGuidesKey
        if (key[0] != host.textVersion || key[1] != from.toLong() || key[2] != to.toLong() ||
            key[3] != generation.toLong()
        ) {
            // A frame without time left (an edit restyled its rows, a fast scroll): the
            // guides it has, moved along with the edits (see onTextChanged), are drawn
            // once more, and asked for on the next frame. None at all for a frame would
            // flicker; those of other lines are not on screen and draw nothing.
            val budget = host.frameBudget
            if (budget.isSpent) {
                budget.defer()
                return indentGuides
            }
            // still being worked out (in the background): the guides it had meanwhile; the
            // provider has the structure asked for again when they are there
            indentGuides = budget.measure { provider.getIndentGuidesToDraw(host.document.buffer, from, to) }
                ?: return indentGuides
            key[0] = host.textVersion
            key[1] = from.toLong()
            key[2] = to.toLong()
            key[3] = generation.toLong()
        }
        return indentGuides
    }

    private var foldingRanges: Map<Int, FoldingRange> = emptyMap()
    private val foldingRangesKey = longArrayOf(-1L, -1L, -1L, -1L)

    /**
     * The range that can be folded from [line]: the folded one, or what the provider
     * says. [drawing]: asked for a fold marker that is drawn; a frame without time left
     * does not ask the provider and draws the marker it had (moved along with the
     * edits, see onTextChanged), the next frame asks.
     */
    fun foldingRangeAt(line: Int, drawing: Boolean = false): FoldingRange? {
        // what is folded is what was folded, whatever the provider says by now
        folds.foldAt(line)?.let { return it }
        val provider = foldingRangeProvider ?: return null
        if (line < 1 || line > host.document.getLineCount()) return null
        val from = windowStart(line)
        val to = windowEnd(line)
        val key = foldingRangesKey
        if (key[0] != host.textVersion || key[3] != generation.toLong() || line < key[1] || line > key[2]) {
            val budget = host.frameBudget
            if (drawing && budget.isSpent) {
                budget.defer()
                // what it had: no marker for a frame would flicker
                return foldingRanges[line]
            }
            // the block of the line, or the lines on screen if it is one of them
            val screen = linesOnScreen()
            val first = if (line in screen) Math.min(from, windowStart(screen.first)) else from
            val last = if (line in screen) Math.max(to, windowEnd(screen.last)) else to
            val ranges = if (drawing) {
                // still being worked out (in the background): the marker it had meanwhile
                budget.measure { provider.getFoldingRangesToDraw(host.document.buffer, first, last) }
                    ?: return foldingRanges[line]
            } else {
                provider.getFoldingRanges(host.document.buffer, first, last)
            }
            foldingRanges = ranges.associateBy { it.startLine }
            key[0] = host.textVersion
            key[1] = first.toLong()
            key[2] = last.toLong()
            key[3] = generation.toLong()
        }
        return foldingRanges[line]
    }

    // #endregion

    // #region drawing, over the text

    /**
     * Draws what goes over the text, after the rows: the indent guides, the brackets
     * at the cursor (bold, underlined), the placeholders of folded lines and, with
     * the line numbers in the rows, the fold markers.
     */
    fun drawOverText(canvas: Canvas) {
        drawIndentGuides(canvas)
        drawBracketPair(canvas)
        drawFolds(canvas)
    }

    private fun drawIndentGuides(canvas: Canvas) {
        if (indentGuideProvider == null) return
        val lines = linesOnScreen()
        val guides = indentGuidesFor(lines)
        if (guides.isEmpty()) return

        val lineHeight = host.lineHeight
        val textLeft = host.textLeft
        val layout = layout()
        val rowsAreLines = layout is UnwrappedLayout
        val cursorLine = host.cursorLine

        // the guide of the block the cursor is in: the innermost one on its line
        var active: IndentGuide? = null
        var activeX = Float.NEGATIVE_INFINITY
        for (guide in guides) {
            if (cursorLine < guide.startLine || cursorLine > guide.endLine) continue
            val x = indentGuideX(guide, textLeft)
            if (x > activeX) {
                active = guide
                activeX = x
            }
        }

        for (guide in guides) {
            // the part on screen, without folded lines at its ends
            val startLine = folds.nextVisibleLine(Math.max(guide.startLine, lines.first))
            val endLine = folds.previousVisibleLine(Math.min(guide.endLine, lines.last))
            if (startLine > endLine) continue
            val firstRow = if (rowsAreLines) startLine - 1 else layout.getStartIndex(startLine)
            val lastRow = if (rowsAreLines) endLine - 1 else layout.getEndIndex(endLine)
            val x = if (guide === active) activeX else indentGuideX(guide, textLeft)
            host.drawIndentGuide(
                canvas, guide, x,
                (firstRow * lineHeight).toFloat(), ((lastRow + 1) * lineHeight).toFloat(),
                guide === active
            )
        }
    }

    // where the column of the guide starts on its anchor line, in content x
    private fun indentGuideX(guide: IndentGuide, textLeft: Int): Float {
        val document = host.document
        val line = guide.anchorLine.coerceIn(1, document.getLineCount())
        val endColumn = document.getLineEndColumn(line)
        val column = Math.max(1, guide.column)
        var x = host.metrics.measureText(document.getLineSegment(line, 1, Math.min(column, endColumn)))
        if (column > endColumn) {
            // past the end of the line: as if it went on with spaces
            x += (column - endColumn) * host.metrics.measureText(" ")
        }
        return (textLeft + x).toFloat()
    }

    // the brackets of the pair at the cursor: bold over the text, and underlined
    private fun drawBracketPair(canvas: Canvas) {
        if (!bracketBold && !bracketUnderline) return
        val pair = bracketPair() ?: return
        val layout = layout()
        val document = host.document
        for (bracket in arrayOf(pair.open, pair.close)) {
            if (folds.isHidden(bracket.startLine)) continue
            val row = layout.getIndexAt(bracket.startLine, bracket.startColumn) + 1
            val rowStartColumn = layout.getLineResult(row).start + 1
            if (bracket.startColumn < rowStartColumn) continue
            val left = host.textLeft + host.metrics.measureText(
                document.getLineSegment(bracket.startLine, rowStartColumn, bracket.startColumn)
            )
            val baseline = host.baselineOf(row).toFloat()
            if (bracketBold) {
                host.drawBracketText(canvas, bracket, row, left.toFloat(), baseline)
            }
            if (bracketUnderline) {
                val right = left + host.metrics.measureText(
                    document.getLineSegment(bracket.startLine, bracket.startColumn, bracket.endColumn)
                )
                host.drawBracketUnderline(canvas, bracket, row, left.toFloat(), right.toFloat(), baseline)
            }
        }
    }

    // The placeholders of the folds on screen, and the fold markers when the line
    // numbers are in the rows (in the gutter, the gutter draws them). Not part of the
    // recorded rows: folding and unfolding would have to record them again.
    private fun drawFolds(canvas: Canvas) {
        val markerWidth = host.metrics.foldMarkerWidth
        if (markerWidth <= 0) return
        val layout = layout()
        val lineHeight = host.lineHeight
        val lines = linesOnScreen()
        val placeholderWidth = foldStyle.placeholderWidth(lineHeight)
        for (fold in folds.getFolds()) {
            if (fold.startLine < lines.first || fold.startLine > lines.last) continue
            if (folds.isHidden(fold.startLine)) continue
            val row = layout.getEndIndex(fold.startLine) + 1
            val left = placeholderLeft(layout, row).toFloat()
            val top = ((row - 1) * lineHeight).toFloat()
            host.drawFoldPlaceholder(canvas, fold, left, top, left + placeholderWidth, top + lineHeight)
        }
        if (host.gutterEnabled) return
        val markerLeft = host.rowFoldMarkerLeft.toFloat()
        for (line in lines) {
            if (folds.isHidden(line)) continue
            val range = foldingRangeAt(line, drawing = true) ?: continue
            val top = (layout.getStartIndex(line) * lineHeight).toFloat()
            host.drawFoldMarker(canvas, range, isFolded(line), markerLeft, top, markerLeft + markerWidth, top + lineHeight)
        }
    }

    // #endregion
}

/**
 * Moves what the providers said along with one change of the text, so that it can be
 * drawn once more until they are asked again (see CodeStructure.onTextChanged): a guide
 * or a range below the change moves with its lines, one around it grows or shrinks
 * with them, one inside lines that were taken away goes. Like the folds of
 * FoldingModel.onTextChanged, but nothing opens: this is only what is drawn meanwhile.
 */
internal class StructureShift(change: EditorDocument.AppliedChange) {
    // the first and the last line the change replaced, as they were
    private val first = change.range.startLine
    private val lastBefore = change.range.endLine
    // the last line it put in their place, as it is now
    private val lastAfter = change.range.startLine + change.insertedLinesCnt
    private val delta = change.insertedLinesCnt - change.deletedLinesCnt

    // where a line where something starts is now; null if it was taken away
    private fun start(line: Int): Int? = when {
        line <= first -> line
        line > lastBefore -> line + delta
        line <= lastAfter -> line
        else -> null
    }

    // where a line where something ends is now
    private fun end(line: Int): Int = when {
        line < first -> line
        line >= lastBefore -> line + delta
        else -> Math.min(line, lastAfter)
    }

    fun guide(guide: IndentGuide): IndentGuide? {
        val startLine = start(guide.startLine) ?: return null
        val endLine = end(guide.endLine)
        if (endLine < startLine) return null
        val anchorLine = start(guide.anchorLine) ?: startLine
        return guide.copy(startLine = startLine, endLine = endLine, anchorLine = anchorLine)
    }

    fun range(range: FoldingRange): FoldingRange? {
        val startLine = start(range.startLine) ?: return null
        val endLine = end(range.endLine)
        if (endLine <= startLine) return null
        return range.copy(startLine = startLine, endLine = endLine)
    }
}

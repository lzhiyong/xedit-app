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
import android.graphics.RenderNode
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi

/**
 * Draws the visible lines of text: decides which lines are on screen, walks them for
 * the current [TextLayout] (wrapped or not), and caches one [RenderNode] per line when
 * the canvas is hardware accelerated.
 *
 * *What* a line looks like is not decided here. Highlights, the line number and the
 * text itself are painted through [Painter], which is where EditorView (and its
 * subclasses, e.g. a syntax highlighter) plug in their own drawing.
 */
class TextRenderer(
    private val host: TextRenderer.Host,
    private val painter: TextRenderer.Painter,
    // only used to find the visible column range of a long unwrapped line
    private val textPaint: TextPaint
) {

    /** On top of geometry and text: the current layout and where a line's text starts. */
    interface Host : EditorGeometry, EditorTextSource {
        val textLayout: TextLayout
        /** How many per-line RenderNodes to keep. Roughly the lines on one screen. */
        val nodeCacheSize: Int

        fun getGutterWidth(): Int
        fun getLineBaseline(lineNumber: Int): Int
    }

    /**
     * Paints the pieces of one line. In every method `bufferLine` / `line` is the real line
     * number in the buffer and `visualLine` the visual (on-screen) line; they only differ
     * in word wrap mode.
     */
    interface Painter {
        /** Backgrounds: current line, selection, search matches. */
        fun drawRegionHighlight(canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int)

        fun drawLineNumber(canvas: Canvas, line: Int, x: Float, y: Float)

        fun drawText(
            canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, x: Float, y: Float
        )
    }

    data class TextRenderNode(
        var line: Int = 1,
        val name: String? = null,
        val renderNode: RenderNode = RenderNode(name),
        var isDirty: Boolean = true
    )

    // cache the RenderNode for hardware accelerated
    private val cacheRenderNodes = mutableListOf<TextRenderNode>()

    /** Whether any line has been recorded yet, i.e. whether there is anything to invalidate. */
    val hasRenderNodes: Boolean get() = !cacheRenderNodes.isEmpty()

    /** Draws the visible lines. The canvas must already be translated by the padding. */
    fun draw(canvas: Canvas) {
        // read the layout once: it can be swapped from a worker thread
        val layout = host.textLayout
        val hardware = canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        when (layout) {
            is UnwrappedLayout ->
                if (hardware) drawHardware(canvas) else drawSoftware(canvas)
            is WordwrapLayout ->
                if (hardware) drawWordwrapHardware(canvas, layout) else drawWordwrapSoftware(canvas, layout)
        }
    }

    // #region render nodes

    /** Frees every cached display list, e.g. when the whole document was replaced. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun recycleRenderNodes() {
        val iter = cacheRenderNodes.iterator()
        while (iter.hasNext()) {
            // free the node memory
            iter.next().renderNode.discardDisplayList()
            // delete the current item
            iter.remove()
        }
    }

    /** Marks every cached line as needing to be recorded again. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun invalidateRenderNodes() {
        cacheRenderNodes.forEach { node ->
            node.isDirty = true
            node.renderNode.setPosition(0, 0, Int.MAX_VALUE, host.getLineHeight())
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getRenderNode(line: Int): TextRenderNode {
        val node = cacheRenderNodes.getOrNull((line - 1) % host.nodeCacheSize)
        // the node is in the cache array
        if (node != null && node.line != line) {
            node.line = line
            node.isDirty = true
        }

        // if the node is non-null meanning which is within the visible range
        // otherwise meanning the node is not in the cache array
        // we need to create an new node
        return node ?: TextRenderNode(line).also {
            it.renderNode.setPosition(0, 0, Int.MAX_VALUE, host.getLineHeight())
            cacheRenderNodes.add(it)
        }
    }

    // Records the line into its node if needed, then draws the node at the line's row.
    @RequiresApi(Build.VERSION_CODES.Q)
    private inline fun drawCached(canvas: Canvas, visualLine: Int, record: (Canvas) -> Unit) {
        // get the current line RenderNode
        val node = getRenderNode(visualLine)

        // update the displaylist
        if (node.isDirty || !node.renderNode.hasDisplayList()) {
            // start recording
            val recordingCanvas = node.renderNode.beginRecording()
            try {
                record(recordingCanvas)
            } finally {
                // end recording
                node.renderNode.endRecording()
                node.isDirty = false
            }
        }

        // translate to target position
        node.renderNode.setTranslationY((visualLine - 1f) * host.getLineHeight())
        // draw render node
        canvas.drawRenderNode(node.renderNode)
    }

    // #endregion

    // #region draw

    // the first visual line that is (partly) visible
    private fun firstVisibleLine() = host.viewScrollY / host.getLineHeight() + 1

    // whether the visual line is still above the bottom of the viewport (plus one spare)
    private fun isVisible(visualLine: Int): Boolean {
        val lineHeight = host.getLineHeight()
        return visualLine * lineHeight <= host.viewScrollY + host.viewHeight + lineHeight
    }

    // no hardware accelerated
    private fun drawSoftware(canvas: Canvas) {
        // the text start indent
        val gutterWidth = host.getGutterWidth()
        // visible start line number
        var line = firstVisibleLine()

        // draw the text content of the visible lines
        while (line <= host.getLineCount() && isVisible(line)) {
            val text = host.getLine(line)
            // the actual width measured
            val widths = FloatArray(1) { 0f }

            // visible start and end column indexes
            val start = textPaint.breakText(text, true, (host.viewScrollX - gutterWidth).toFloat(), widths)
            val end = start + textPaint.breakText(
                text, start, text.length, true, (host.viewScrollX + host.viewWidth).toFloat(), null
            )
            val paintX = gutterWidth + widths[0]
            val paintY = host.getLineBaseline(line).toFloat()

            // draw region highlight
            painter.drawRegionHighlight(canvas, line, line, start, end)

            // draw the line number
            painter.drawLineNumber(canvas, line, 0f, paintY)

            // draw the line content
            painter.drawText(canvas, text, line, start, end, paintX, paintY)

            // continue to next line
            line++
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawHardware(canvas: Canvas) {
        // visible start line number
        var line = firstVisibleLine()

        val paintX = host.getGutterWidth().toFloat()
        // note here the line number must be 1 for render node
        val paintY = host.getLineBaseline(1).toFloat()

        // draw the text content of the visible lines
        while (line <= host.getLineCount() && isVisible(line)) {
            // the line text content
            val text = host.getLine(line)
            // draw region highlight
            painter.drawRegionHighlight(canvas, line, line, 0, text.length)

            val bufferLine = line
            drawCached(canvas, line) { recordingCanvas ->
                // draw the line number
                painter.drawLineNumber(recordingCanvas, bufferLine, 0f, paintY)
                // draw the line content
                painter.drawText(recordingCanvas, text, bufferLine, 0, text.length, paintX, paintY)
            }

            // continue to next line
            line++
        }
    }

    // draw text for word wrap mode
    private fun drawWordwrapSoftware(canvas: Canvas, layout: TextLayout) {
        // the text start indent
        val paintX = host.getGutterWidth().toFloat()
        // visible start line number
        var line = firstVisibleLine()
        var result = layout.getLineResult(line)

        while (result.line <= host.getLineCount() && isVisible(line)) {
            val text = host.getLine(result.line)
            val paintY = host.getLineBaseline(line).toFloat()

            val start = Math.min(result.start, text.length)
            val end = Math.min(result.end, text.length)

            painter.drawRegionHighlight(canvas, result.line, line, start, end)

            // start index position
            if (line == layout.getStartIndex(result.line) + 1) {
                // draw the buffer line number
                painter.drawLineNumber(canvas, result.line, 0f, paintY)
            }

            // draw current line text
            painter.drawText(canvas, text, result.line, start, end, paintX, paintY)
            // continue to next line
            result = layout.getLineResult(++line)
        }
    }

    // hardware accelerated
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawWordwrapHardware(canvas: Canvas, layout: TextLayout) {
        // visible start line number
        var line = firstVisibleLine()

        val paintX = host.getGutterWidth().toFloat()
        val paintY = host.getLineBaseline(1).toFloat()

        while (line <= layout.count() && isVisible(line)) {
            val result = layout.getLineResult(line)
            val text = host.getLine(result.line)
            val start = Math.min(result.start, text.length)
            val end = Math.min(result.end, text.length)

            painter.drawRegionHighlight(canvas, result.line, line, start, end)

            val visualLine = line
            drawCached(canvas, line) { recordingCanvas ->
                // start index position
                if (visualLine == layout.getStartIndex(result.line) + 1) {
                    // draw the buffer line number
                    painter.drawLineNumber(recordingCanvas, result.line, 0f, paintY)
                }

                // draw the line content
                painter.drawText(recordingCanvas, text, result.line, start, end, paintX, paintY)
            }

            // continue to next line
            line++
        }
    }

    // #endregion
}

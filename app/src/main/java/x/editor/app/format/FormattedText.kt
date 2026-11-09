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

package x.editor.app.format

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

import x.github.module.lsp.LspFormattingOptions
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.EndOfLine
import x.github.module.piecetable.common.Range

/**
 * The text of a document as it would be formatted, for the preview of the formatting
 * sheet: built from a [SnapshotBuffer] of it into a new piece tree, a piece at a time, so
 * that the document's text is read once and never held as one String. Off the main thread.
 */
internal object FormattedText {

    // what is handed to the builder at once: the size of its own chunks
    private const val CHUNK = 64 * 1024

    /** A formatted text: [buffer], and how many places of the document it changed. */
    class Result(val buffer: PieceTreeTextBuffer, val changes: Int)

    /**
     * [source] with [edits] made (a server's: in the coordinates of [source], 1-based, not
     * overlapping). The edits are sorted here; one that overlaps the one before it is left
     * out, as an editor would refuse it.
     */
    fun withEdits(source: SnapshotBuffer, edits: List<Pair<Range, String>>): Result {
        val sorted = edits
            .map { (range, text) -> Triple(source.getOffsetAt(range.startLine, range.startColumn), source.getOffsetAt(range.endLine, range.endColumn), text) }
            .map { (a, b, text) -> if (a <= b) Triple(a, b, text) else Triple(b, a, text) }
            .sortedBy { it.first }
        val out = Output(source)
        var at = 0
        var made = 0
        for ((start, end, text) in sorted) {
            if (start < at) continue
            out.copy(at, start)
            out.append(text)
            at = end
            made++
        }
        out.copy(at, source.length)
        return Result(out.build(), made)
    }

    /** [source] laid out with [options] alone: see FormatSimulation. [tabWidth]: the editor's. */
    suspend fun simulated(source: SnapshotBuffer, options: LspFormattingOptions, tabWidth: Int): Result {
        val fallback = if (options.insertSpaces) options.tabSize else tabWidth
        val indentWidth = FormatSimulation.guessIndentWidth(lines(source), tabWidth, fallback)
        val simulation = FormatSimulation(options, tabWidth, indentWidth)
        val out = Output(source)
        simulation.run(lines(source), source.getEOL()) { out.append(it) }
        out.flush()
        return Result(out.build(), simulation.changedLines)
    }

    // the lines of [source], read as they are asked for
    private suspend fun lines(source: SnapshotBuffer): Iterator<String> {
        val context = coroutineContext
        return iterator {
            for (line in 1..source.getLineCount()) {
                // a sheet closed meanwhile: no need to go on
                if (line % 4096 == 0) context.ensureActive()
                yield(source.getLineContent(line))
            }
        }
    }

    // what goes into the new piece tree, in pieces of about CHUNK chars
    private class Output(private val source: SnapshotBuffer) {
        private val builder = PieceTreeTextBufferBuilder()
        private val pending = StringBuilder()

        fun append(text: String) {
            pending.append(text)
            if (pending.length >= CHUNK) flush()
        }

        // the text of the source from [start] to [end], as it is
        fun copy(start: Int, end: Int) {
            var at = start
            while (at < end) {
                val to = minOf(end, at + CHUNK)
                append(source.snapshot.substring(at, to))
                at = to
            }
        }

        fun flush() {
            if (pending.isEmpty()) return
            builder.acceptChunk(pending.toString())
            pending.setLength(0)
        }

        fun build(): PieceTreeTextBuffer {
            flush()
            // the line breaks of the document (a server writes \n), as the editor loads a file
            val eol = if (source.getEOL() == "\r\n") EndOfLine.CRLF else EndOfLine.LF
            return builder.build(eol, normalizeEOL = true)
        }
    }
}

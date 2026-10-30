# piecetable: Editor integration

> Part of the documentation of the [piecetable](../piecetable/README.md) module · [中文](piecetable_integration_cn.md)

The buffer is a data structure, not an editor. Four things belong in the layer that owns it:
recovering from a failed edit, validating positions that come from the UI, not copying very
long lines on every frame, and handing out snapshots for background work. One class that does
all four:

```kotlin
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

class EditorDocument(private val file: File) {

    /** One lock per document: buffers share no state, documents never wait for each other */
    private val lock = ReentrantReadWriteLock()

    @Volatile
    var buffer: PieceTreeTextBuffer = load()
        private set

    /** reverse edits, newest last; whatever undo manager you use, it must be reset on recovery */
    private val undoStack = ArrayDeque<List<SingleEditOperation>>()

    /** e.g. show "the document was reloaded from disk" and re-render everything */
    var onRecovered: (Throwable) -> Unit = {}

    private fun load(): PieceTreeTextBuffer {
        val builder = PieceTreeTextBufferBuilder()
        file.bufferedReader().use { reader ->
            val chars = CharArray(64 * 1024)
            while (true) {
                val n = reader.read(chars)
                if (n <= 0) break
                builder.acceptChunk(String(chars, 0, n))
            }
        }
        return builder.build(EndOfLine.LF, normalizeEOL = true)
    }

    // ------------------------------------------------------------- 1. edits and recovery

    /** Applies [ops]. Returns null when the edit failed and the document was rebuilt. */
    fun edit(ops: List<SingleEditOperation>): ApplyEditsResult? = lock.write {
        try {
            val result = buffer.applyEdits(ops, false, true)
            undoStack.addLast(result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) })
            result
        } catch (e: IllegalArgumentException) {
            // only thrown by the validation before the first change (overlapping ranges):
            // a bug in the caller, the buffer is intact
            throw e
        } catch (e: Throwable) {
            // anything else may have stopped half way through a tree operation
            recover(e)
            null
        }
    }

    // called with the write lock held
    private fun recover(cause: Throwable) {
        // the failed edit can only have damaged this buffer: other documents are unaffected
        // 1. best effort: save what can still be read. The tree may be damaged, so this can be
        //    wrong or fail; runCatching also catches a StackOverflowError from a broken tree
        runCatching {
            File(file.path + ".recovered").bufferedWriter().use { out ->
                buffer.readPiecesContent { out.write(it) }
            }
        }
        // 2. rebuild from a trusted source: the last saved file (or your autosave / journal)
        buffer = load()
        // 3. the undo history describes the old content
        undoStack.clear()
        onRecovered(cause)
    }

    // ------------------------------------------------------------- 2. validate positions

    /**
     * Clamps (line, column) into the document and moves it off the middle of a surrogate pair.
     * Use it for everything that comes from the UI: taps, restored cursors, search results of
     * an older revision.
     */
    fun validPosition(line: Int, column: Int): Position = lock.read {
        val b = buffer
        val l = line.coerceIn(1, b.getLineCount())
        var c = column.coerceIn(1, b.getLineMaxColumn(l))
        if (c in 2..b.getLineLength(l)) {
            val before = b.getLineCharCode(l, c - 2).toChar()
            val at = b.getLineCharCode(l, c - 1).toChar()
            if (before.isHighSurrogate() && at.isLowSurrogate()) c--
        }
        Position(l, c)
    }

    // ------------------------------------------------------------- 3. long lines

    /**
     * What the view draws for one line: the whole line when it is short, otherwise only the
     * visible window. getLineContent copies the entire line, which for a minified file can be
     * megabytes per frame; getValueInRange copies only the requested columns.
     */
    fun renderText(line: Int, firstVisibleColumn: Int, visibleColumns: Int): String =
        lock.read {
            val b = buffer
            when {
                line !in 1..b.getLineCount() -> ""
                // two offset lookups on a normalized buffer, no copy
                b.getLineLength(line) <= LONG_LINE -> b.getLineContent(line)
                else -> {
                    val maxColumn = b.getLineMaxColumn(line)
                    val start = firstVisibleColumn.coerceIn(1, maxColumn)
                    val end = (start + visibleColumns).coerceAtMost(maxColumn)
                    b.getValueInRange(Range(line, start, line, end))
                }
            }
        }

    // ------------------------------------------------------------- 4. background work

    /** A snapshot for background work (highlighting, saving), and the buffer it was taken from */
    fun snapshot(): Pair<PieceTreeTextBuffer, PieceTreeSnapshot> =
        lock.read { buffer to buffer.createSnapshot() }

    /** Whether a result computed on [snapshot] of [source] still matches the document */
    fun isCurrent(source: PieceTreeTextBuffer, snapshot: PieceTreeSnapshot): Boolean =
        lock.read { buffer === source && source.getVersionId() == snapshot.versionId }

    companion object {
        const val LONG_LINE = 10_000
    }
}
```

Notes on the four parts:

- **Recovery.** `IllegalArgumentException` is only thrown before anything was changed, so the
  buffer is still fine and the caller has a bug to fix. Every other exception (an internal bug,
  `OutOfMemoryError`) can leave the tree half updated: never edit that buffer again. The damage
  stays inside that buffer, every tree owns its sentinel, so the other documents need nothing.
  The `.recovered` file is a courtesy for the user, not a source of truth.
- **Positions.** Ranges passed to `applyEdits`, `getValueInRange` or `find` are clamped, and line
  based queries throw `IndexOutOfBoundsException` for a missing line. Positions that come from
  the UI should still be clamped in one place, which also keeps carets out of the middle of an
  emoji (range validation inside the buffer does not do that).
- **Long lines.** `getLineLength` / `getLineMaxColumn` are cheap only on an EOL-normalized buffer
  (`normalizeEOL = true`); on a non-normalized one they measure the line by reading it.
- **Background work.** Taking the snapshot is quick and happens under the read lock; reading it
  happens without any lock. A recovered document has a new buffer whose versions start again
  from 0, which is why `isCurrent` compares the buffer as well. See
  [Highlighting while typing](piecetable_architecture.md#highlighting-while-typing).

# piecetable

English | [中文](README_CN.md) · [Design](DESIGN.md)

A **piece tree text buffer** (a red-black tree of pieces) in Kotlin, ported from the `pieceTreeTextBuffer` of [VS Code](https://github.com/microsoft/vscode), for code editors on Android.

## Why

Compared with a `StringBuilder` or a `List<String>`:

- **Opening a large file copies nothing**: the original text is held in its chunks as it is, and what is typed is only appended to one buffer.
- **Insert, delete and line/column ↔ offset are O(log n)** (n pieces), whatever the size of the file.
- **Background work does not block typing**: a snapshot is cheap to make and can then be read on any thread while the buffer goes on being edited.
- **Undoing large edits copies nothing**: with slices, the undo history refers to the storage itself.

How it manages that: see [Design](DESIGN.md).

## Adding it

```kotlin
// settings.gradle.kts
include(":piecetable")
// build.gradle.kts
dependencies { implementation(project(":piecetable")) }
```

Its only dependency is `kotlinx-serialization` (for value types such as `Range` and `Position`). The buffer itself is not serializable: keep the text, and build again to restore.

## Use

```kotlin
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

// 1. Build: streamed, cut into chunks anywhere (\r\n and surrogate pairs across chunks are handled)
val builder = PieceTreeTextBufferBuilder()
file.bufferedReader().use { r ->
    val chars = CharArray(64 * 1024)
    while (true) { val n = r.read(chars); if (n <= 0) break; builder.acceptChunk(String(chars, 0, n)) }
}
val buffer = builder.build(eol = EndOfLine.LF, normalizeEOL = true)

// 2. Read: lines and columns from 1
buffer.getLineCount(); buffer.getLineContent(1); buffer.getOffsetAt(3, 5)

// 3. Edit: a batch, every range against the text before it, no overlaps
val result = buffer.applyEdits(
    listOf(SingleEditOperation(Range(1, 1, 1, 1), "// "), SingleEditOperation(Range(3, 1, 4, 1), "")),
    recordTrimAutoWhitespace = false,
    computeUndoEdits = true
)
result.changes        // to refresh the UI, update a syntax tree
result.reverseEdits   // the inverse edits, for the undo stack

// 4. Undo
buffer.applyEdits(result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }, false, false)

// 5. Search: ^ and $ are line start and end; patterns that can match a newline go multi-line; the last argument cancels
buffer.find(Regex("""fun\s+\w+"""), searchRange, limitResultCount = 1000) { isCancelled() }

// 6. Save: in pieces; not toString(), which can run out of memory on a large file
buffer.readPiecesContent { chunk -> out.write(chunk) }

// 7. Read in the background: a snapshot
val snapshot = buffer.createSnapshot()          // on the editing thread, cheap
executor.execute { snapshot.getNearestChunk(0) } // any thread, no lock
```

Undoing a large deletion with `applyEdits` keeps another copy of the deleted text. To copy nothing, use slices:

```kotlin
val result = buffer.applyEditsWithSliceUndo(ops)          // the same edit as applyEdits
val undone = buffer.applySliceEdits(result.reverseEdits)  // undo; returns the redo batch
buffer.applySliceEdits(undone.reverseEdits)               // redo
```

Content of 16K characters or more is kept by reference: delete 1 MB and undo, and the storage is exactly as before.

## Three rules you must know

**1. Threads: a buffer can be read by many threads at once, or written by one, but not both at the same time.**

Different buffers do not affect each other. The simplest way is a read-write lock per buffer:

```kotlin
val lock = ReentrantReadWriteLock()                           // one per buffer
lock.write { buffer.applyEdits(ops, false, true) }            // edits
lock.read { buffer.find(regex, range, 1000) { cancelled } }   // reads, any thread
```

Long work that must not block typing (highlighting, saving, indexing) takes a snapshot instead of holding the read lock. Making a snapshot is one read; after that it never touches the buffer. `snapshot.versionId == buffer.getVersionId()` tells whether a result still matches the content.

**2. Memory: the buffer never frees deleted text by itself.**

Edits never change or free existing text: deleted input stays in the append buffer, and long editing leaves many small pieces. Memory is roughly "the file + everything ever typed". Call `compact()` after saving or when idle to rebuild the storage:

```kotlin
lock.write {
    val s = buffer.getStorageStats()
    val garbage = s.storedLength - s.length
    if (garbage > maxOf(s.length.toLong(), 4L * 1024 * 1024) || s.pieceCount > 50_000) buffer.compact()
}
```

`compact()` is O(n) and needs the write lock and about one more document's worth of memory: keep it off the typing path. It changes no text, offset or line number, so cursors and undo data stay valid.

**3. The `CharSequence` implementation is handy but slow.**

`get(i)` is O(log n); a `Regex` run straight on the buffer degrades badly. Use `find()`.

## Main API

| Method | Notes |
| --- | --- |
| `getLineCount()`, `getLineContent(line)`, `getLineLength(line)` | By line; a line that does not exist throws `IndexOutOfBoundsException` |
| `getValueInRange(range)`, `getOffsetAt(line, col)`, `getPositionAt(offset)` | By range or position; clamped into the document |
| `getNearestChunk(offset)` | A run of text from offset (up to about 64K characters), for incremental parsers such as tree-sitter |
| `getVersionId()` | Changes with every change of content |
| `applyEdits(ops, …)` | The one entry for edits; line endings of inserted text become the buffer's |
| `applyEditsWithSliceUndo`, `applySliceEdits`, `exportSlice` | Edits, undo and export without copying large content |
| `createSnapshot()` | An immutable snapshot, see above |
| `compact()`, `getStorageStats()` | Rebuild the storage; see what it holds |
| `setEOL(eol)` | Change the line ending, rebuilding the text |
| `readPiecesContent { }` | All the content piece by piece, for saving |

`PieceTreeBase.insert` / `delete` are low-level primitives that do not normalize line endings; application code should use `applyEdits`.

## Limits

- While a snapshot is alive, the chunks it refers to cannot be freed, even after `compact()` replaced them: do not keep snapshots longer than needed. A snapshot's `read()` must not be called by two threads at once (its other members may).
- A slice holding references can only go back into the buffer it came from, and is not serializable; to persist an undo history keep `toText()`. Slices do not trim auto whitespace and have no `forceMoveMarkers`.
- Multi-line search copies every line in its range; on a buffer with `normalizeEOL = false` and mixed line endings, its ranges can be off.
- Range validation does not move a column out of the middle of a surrogate pair (an emoji); the caller has to (see [Design](DESIGN.md#editor-integration)).
- `getValueLengthInRange` ignores its `eol` argument.

## Tests

```bash
./gradlew :piecetable:testDebugUnitTest            # JVM
./gradlew :piecetable:connectedDebugAndroidTest    # device
```

Every test is in `src/sharedTest` and runs on both: VS Code's original suite, seeded random tests, search checked against a naive implementation, and one regression test per fixed bug. What to watch for when setting up the shared test sources in your own project: see [Design](DESIGN.md#test-setup).

## License

Apache License 2.0. The piece tree is ported from [VS Code](https://github.com/microsoft/vscode) (MIT, Copyright (c) 2015 - present Microsoft Corporation); its license is in [LICENSE-vscode](LICENSE-vscode), which has to stay with this module and works derived from it. Each file ported from VS Code says so in its header; `common/intList.kt`, `textSlice.kt` and the added tests are this module's own.

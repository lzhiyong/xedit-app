# piecetable: Usage

> Part of the documentation of the [piecetable](../piecetable/README.md) module · [中文](piecetable_usage_cn.md)

## Getting started

### 1. Add the module

```kotlin
// settings.gradle.kts
include(":piecetable")

// app/build.gradle.kts
dependencies {
    implementation(project(":piecetable"))
}
```

The module itself only depends on `kotlinx-serialization`, for the `@Serializable` annotations on
the public value types (`Range`, `Position`, `TextChange`, ...). The buffer and the tree are not
serializable: persist the text (plus your editor state) and rebuild the buffer on restore.

### 2. Build a buffer

```kotlin
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

// streaming: feed the file in chunks, no need to materialise the whole String
val builder = PieceTreeTextBufferBuilder()
file.bufferedReader().use { reader ->
    val chars = CharArray(64 * 1024)
    while (true) {
        val n = reader.read(chars)
        if (n <= 0) break
        builder.acceptChunk(String(chars, 0, n))
    }
}

val textBuffer: PieceTreeTextBuffer = builder.build(
    eol = EndOfLine.LF,
    normalizeEOL = true
)

textBuffer.getLineCount()      // number of lines
textBuffer.getLineContent(1)   // first line, line numbers are 1 based
textBuffer.getEOL()            // "\n" or "\r\n", sniffed from the content
textBuffer.getBOM()            // UTF-8 BOM if the file had one
```

`acceptChunk` handles a `\r\n` or a UTF-16 surrogate pair that straddles a chunk boundary, so
any chunk size is safe. Large chunks are sliced into buffers of at most 64K chars, so handing over
the whole file as one String (or to the `PieceTreeTextBufferBuilder(text)` constructor) is fine too.

### 3. Edit

```kotlin
val result = textBuffer.applyEdits(
    listOf(
        SingleEditOperation(Range(1, 1, 1, 1), "// "),   // insert at the start of line 1
        SingleEditOperation(Range(3, 1, 4, 1), "")       // delete line 3
    ),
    recordTrimAutoWhitespace = false,
    computeUndoEdits = true                              // needed for undo
)

result.changes        // ContentChange list, feed it to the view / highlighter
result.reverseEdits   // inverse operations, push them onto your undo stack
```

Operations are applied bottom-up internally, so the offsets of one operation are unaffected by
the others. Ranges must not overlap (touching is fine); overlapping ranges throw
`IllegalArgumentException`. Out of range ranges are clamped into the document instead of
corrupting the tree, and inserted text is converted to the buffer's line ending, also when it
mixes several kinds.

### 4. Undo

```kotlin
val undo = result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }
textBuffer.applyEdits(undo, false, false)   // back to the previous state
```

`TextChange.compressConsecutiveTextChanges` can merge the changes of several edits into a single
undo step, which is what an editor usually wants for rapid typing.

The reverse edits hold the removed text as Strings, so undoing a delete of a large selection
keeps a full copy of it. `applyEditsWithSliceUndo` returns the undo batch as slices that refer
to the storage instead, see [Slices](#slices-undo-without-copying-large-content):

```kotlin
val result = textBuffer.applyEditsWithSliceUndo(ops)       // same edits as applyEdits
val undone = textBuffer.applySliceEdits(result.reverseEdits) // undo; returns the redo batch
textBuffer.applySliceEdits(undone.reverseEdits)              // redo
```

### 5. Search

```kotlin
// plain text; a search text with line breaks matches across lines, whatever break it uses
buffer.find("TODO", searchRange, limitResultCount = 1000) { false }

// regex, matched line by line
buffer.find(Regex("""fun\s+\w+"""), searchRange, 1000) { isCancelled() }

// regex across lines: RegexOption.MULTILINE, or a pattern containing \n, \r or \W
buffer.find(Regex("""\{\s*\n\s*\}"""), searchRange, 1000) { isCancelled() }
```

- The search range is clamped into the document; its start and end columns are honoured.
- `^` and `$` always mean start and end of a **line**, like VS Code's `m` flag, in both engines.
  The start and end columns of the search range are not a line start / end, and lookarounds and
  `\b` see the text of the line outside the range.
- A line-by-line match never spans a line break. Patterns that can match one (a literal line
  break, `\n`, `\r`, `\W`) go to the multiline engine automatically: it joins the lines of the
  range with `\n` and matches inside the range, so `\n` matches every line break kind.
- The last argument is a cancellation callback, useful for long searches on a background job.

### 6. Save

```kotlin
textBuffer.readPiecesContent { chunk ->
    outputStream.write(chunk.toByteArray())   // piece by piece, no full copy
}
```

`toString()` materialises the whole document into one String and can throw `OutOfMemoryError`
on large files; prefer `readPiecesContent` for I/O.

### 7. Read in the background: snapshots

```kotlin
// on the editor thread (or under the read lock): cheap, the file content is not copied
val snapshot = textBuffer.createSnapshot()

// any thread, any time, no lock, also while the user keeps typing
executor.execute {
    var offset = 0
    while (true) {
        val chunk = snapshot.getNearestChunk(offset)   // how an incremental parser reads
        if (chunk.isEmpty()) break
        offset += chunk.length
    }
    // snapshot.versionId is textBuffer.getVersionId() of the moment it was taken:
    // compare them to know whether a result is still current
}
```

A snapshot is the document at one version and never changes, whatever happens to the buffer
afterwards (edits, `compact()`, `setEOL()`). Background highlighting and saving read it instead
of holding a lock; see [Highlighting while typing](piecetable_architecture.md#highlighting-while-typing).

## API overview

### Reading

| Method | Description |
| --- | --- |
| `getLineCount()` | number of lines, at least 1 |
| `getLineContent(line)` | line content **without** the line terminator, cached |
| `getLineContentWithEOL(line)` | line content plus the EOL |
| `getLinesContent()` | all lines as a list |
| `getLineLength(line)` / `getLineMaxColumn(line)` | line length / length + 1, exact also on a non-normalized buffer |
| `getLineFirstNonWhitespaceColumn(line)` | first non blank column, 0 when the line is blank |
| `getValueInRange(range, eol)` | text of a range (clamped into the document), optionally with a forced line ending |
| `getOffsetAt(line, column)` / `getPositionAt(offset)` | position conversions, both clamped |
| `getCharCode(offset)` / `getLineCharCode(line, index)` | UTF-16 code unit, `charCodeAt` semantics; 0 at the end of the document |
| `getNearestChunk(offset)` | the contiguous run of text at `offset` (about 64K chars at most, `""` at the end), for incremental parsers |
| `createSnapshot(preserveBOM = false)` | immutable copy of the current content that any thread can read during edits, see [Snapshots](#snapshots) |
| `getVersionId()` | changes with every change of the content (`applyEdits`, `setEOL`), not with reads or `compact()` |
| `find(text or regex, range, limit, isCancelled)` | search, see [Search](#5-search) |
| `length` / `get(i)` / `subSequence(a, b)` | `CharSequence` implementation, out of range indices throw `IndexOutOfBoundsException` |
| `contentEquals(other)` | compares BOM, EOL and content |

### Writing

| Method | Description |
| --- | --- |
| `applyEdits(ops, recordTrimAutoWhitespace, computeUndoEdits)` | the entry point for all edits |
| `setEOL(eol)` | switch the line ending and rebuild the document |
| `compact()` | rebuild the storage from the current text, see [Memory and compaction](piecetable_architecture.md#memory-and-compaction) |
| `getStorageStats()` | document length, stored chars, change buffer size, piece count |
| `applyEditsWithSliceUndo(ops)` | text edits like `applyEdits`, the undo batch as slices, see [Slices](#slices-undo-without-copying-large-content) |
| `applySliceEdits(edits)` | replace ranges with `TextSlice`s (undo / redo); returns the changes and the reverse batch |

**Arguments.** Line based queries (`getLineContent`, `getLineContentWithEOL`, `getLineLength`,
`getLineMinColumn` / `getLineMaxColumn`, `getLineCharCode`, the non-whitespace helpers) need a
line that exists, `1..getLineCount()`, and throw `IndexOutOfBoundsException` otherwise. Position
and range based methods (`getOffsetAt`, `getValueInRange`, `getValueLengthInRange`,
`getCharacterCountInRange`, `applyEdits`, `find`) clamp instead.

`PieceTreeBase.insert` / `delete` are the low level primitives. They validate their offsets and
throw `IndexOutOfBoundsException`, but they do not normalise the EOL of the inserted text, so
prefer `applyEdits`.

### Snapshots

`createSnapshot()` returns a `PieceTreeSnapshot`: the content at that moment, never changing
afterwards. Creating it is a read of the buffer and costs O(number of pieces) plus a copy of the
typed text still in the document; it does not copy the file content. Reading it needs no lock,
from any thread, also while the buffer is edited, compacted or switched to another EOL.

| Member | Description |
| --- | --- |
| `length` / `get(i)` / `substring(a, b)` / `subSequence(a, b)` | `CharSequence` over document offsets (the BOM is not counted); `get` is O(log n) |
| `getNearestChunk(offset)` | same contract as the buffer's: the rest of the piece at `offset`, at most about 64K chars, `""` at the end |
| `readPiecesContent { }` | the whole content, piece by piece, e.g. to save it |
| `toString()` | the whole content as one String |
| `versionId` / `lineCount` / `eol` | the buffer's state when the snapshot was taken |
| `bom` / `read()` | VS Code style sequential reading: `bom` (the BOM with `preserveBOM = true`, else `""`), then piece by piece, then `null`. `read()` keeps a read position, so one thread at a time; all other members are safe from any number of threads |

How it stays independent: when the snapshot is created every piece is resolved to (text, start,
length). Pieces of the original chunks point at those chunks, which nothing writes to once the
tree holds them; pieces of the change buffer, which grows with every edit, are copied. Nothing is
looked up through the tree, its buffer list or its line starts later. The price: a snapshot keeps
the chunks it points at in memory, also after `compact()` / `setEOL()` replaced them in the
buffer, so drop snapshots once they are processed.

### Slices: undo without copying large content

`applyEdits` returns what each edit removed as a String (`reverseEdits[i].text`), so deleting or
replacing a large selection keeps one more full copy of the removed text for undo. The slice
API returns it as `TextSlice`s: short content as a String, longer content as references to the
pieces of storage it lies in, without a copy.

```kotlin
// edit: the same text edits as applyEdits, the undo batch comes back as slices
val result = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(selection, "")))
undoStack.add(result.reverseEdits)

// undo: applying the reverse batch returns the redo batch
val undone = buffer.applySliceEdits(undoStack.removeAt(undoStack.size - 1))
redoStack.add(undone.reverseEdits)

// redo, and so on
val redone = buffer.applySliceEdits(redoStack.removeAt(redoStack.size - 1))
undoStack.add(redone.reverseEdits)
```

| Member | Description |
| --- | --- |
| `exportSlice(range)` | the content of a range (clamped) as a slice; a read |
| `applySliceEdits(edits)` | replace each `SliceEdit.range` with its `content` (`null` deletes); returns `changes` and `reverseEdits` |
| `applyEditsWithSliceUndo(ops)` | text edits like `applyEdits`, with `reverseEdits` as slices |
| `TextSlice.of(text)` | a slice holding a String; can go into any buffer |
| `TextSlice.length` / `eolCount` / `firstLineLength` / `lastLineLength` | size and line shape of the content, without reading it |
| `TextSlice.toText()` | the content as a String (copies it when held as references) |

- **Two forms.** Content shorter than `TextSlice.TEXT_THRESHOLD` (16K chars) is kept as a String,
  like `reverseEdits`. Longer content is kept as (buffer, start, length) runs; putting it back
  inserts pieces that point at those runs, so nothing is copied or appended to the change
  buffer. Storage that `compact()` or `setEOL()` replaced in the meantime is taken back into the
  buffer. Deleting 1 MB and undoing it leaves `getStorageStats()` exactly as it was.
- **Line breaks.** Content goes back unchanged when it can: same EOL, and exported from an
  EOL-normalized buffer or inserted into a buffer that is not normalized either. Otherwise it
  is normalized to the buffer's EOL like the text of `applyEdits`. So undo restores the exact
  text, mixed line breaks included, unless `setEOL()` was called in between.
- **Rules.** As `applyEdits`: ranges refer to the document before the batch and are clamped,
  overlapping ranges throw `IllegalArgumentException`, edits apply bottom up, and everything is
  checked before the first change. Not covered: trimming of auto-inserted whitespace,
  `forceMoveMarkers`, collapsing of 1000+ edits.
- **Changes.** `SliceEditResult.changes` are `SliceChange`s (range, offset, length, and the new
  content as a slice) instead of `ContentChange`s: update decorations or feed an incremental
  parser from `content.length` / `eolCount` / `lastLineLength`, not from the text.
  `SliceChange.removed` is what the change removed (the same slice as the content of its reverse
  edit; null when nothing was removed), so a caller that keeps its own offset-based undo history
  need not match changes and reverse edits up.
- **Form and size.** `TextSlice.isText` tells whether the content is held as a String (readable
  on any thread, can outlive its buffer); `segmentCount` is the number of runs of storage it
  refers to, a measure of the slice's own size.
- **A slice holding references belongs to its buffer.** Inserting it into another buffer throws
  `IllegalArgumentException` (move content with `TextSlice.of(slice.toText())`). Reading it
  (`toText()`, putting it back) is a read of that buffer, so keep slices where the undo stack
  lives, under the buffer's lock. It keeps the storage it refers to alive, and it is not
  serializable: to persist the history, store `toText()`.
- **Merging consecutive edits.** `TextChange.compressConsecutiveTextChanges` works on texts.
  With slices, keep one undo entry per batch, or merge only entries whose content is text.

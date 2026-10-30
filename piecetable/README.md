# piecetable

A **piece tree text buffer** (red-black tree + piece table) written in Kotlin, ported from the
`pieceTreeTextBuffer` of [VSCode](https://github.com/microsoft/vscode) and tuned for code editors
on Android.

[中文文档](README_CN.md)

Compared with a `StringBuilder` or a `List<String>` of lines:

- **Opening a large file copies nothing.** The original chunks are kept as they were read, and
  edits only append to a single growing change buffer.
- **Insert and delete are O(log n)** in the number of pieces, independent of document size.
- **Line/column to offset conversion is O(log n)**, every tree node caches the length
  (`size_left`) and the line feed count (`lf_left`) of its left subtree.
- **Reads can run in parallel** on several threads while no edit is running (see
  [Threading](../docs/piecetable_architecture.md#threading)).
- **Background work does not block typing.** An immutable snapshot can be read by any thread
  while the buffer keeps being edited (see [Snapshots](../docs/piecetable_usage.md#snapshots)).

## Documentation

The details are in the documents of this module:

- [Usage](../docs/piecetable_usage.md): Getting started, API overview
- [Design and internals](../docs/piecetable_architecture.md): How it works, Complexity, Threading, Memory and compaction
- [Editor integration](../docs/piecetable_integration.md): Editor integration
- [Testing](../docs/piecetable_testing.md): Testing

## Remaining limitations

- **A snapshot keeps old chunks alive** as long as it lives, also after `compact()` / `setEOL()`
  replaced them. Its `read()` keeps a read position and must not be used from two threads at
  once (every other member can).
- **A slice holding references is tied to its buffer** and keeps the storage it refers to alive;
  it is not serializable. The slice API does not trim auto-inserted whitespace, has no
  `forceMoveMarkers`, and its changes carry no text.
- **`CharSequence` is convenient but slow.** `get(i)` is O(log n), so running a `Regex` directly
  over the buffer degrades badly; use `find()` instead.
- **Compaction is manual.** Deleted text and fragmented pieces are only released by `compact()`
  (or `setEOL()`); the buffer never compacts by itself, see
  [Memory and compaction](../docs/piecetable_architecture.md#memory-and-compaction).
- **Multiline search copies the lines of the search range** (the whole first and last line) into
  one String, and on a CRLF buffer maps
  offsets back assuming every line break is `\r\n`. On a buffer built with
  `normalizeEOL = false` and mixed line endings the reported ranges can be off.
- **An edit can split a surrogate pair.** Range validation does not move a column that falls
  between the two halves of an emoji (VS Code does).
- **`getValueLengthInRange` ignores its `eol` argument.**

## License

Apache License 2.0, as the rest of X Editor. The piece tree is ported from
[Visual Studio Code](https://github.com/microsoft/vscode), under the MIT License,
Copyright (c) 2015 - present Microsoft Corporation: its text is in
[LICENSE-vscode](LICENSE-vscode), which has to stay with the module and its derived files.
The algorithm and the original test cases belong to their authors.

Every source file ported from VS Code says so in its header, after its own licence header,
with the VS Code file it comes from: the piece tree (`pieceTreeBase`, `pieceTreeSnapshot`,
`pieceTreeTextBuffer`, `pieceTreeTextBufferBuilder`, `rbTreeBase`), `common/` (`charCode`,
`strings`, `position`, `range`, `model`), and the tests `PieceTreeBaseTest`,
`PieceTreeEditOperationTest` and `TestSupport`. `common/intList.kt`, `textSlice.kt` and the
other tests are this module's own.

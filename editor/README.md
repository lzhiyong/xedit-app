# editor

English | [中文](README_CN.md) · [Design](DESIGN.md)

`EditorView`, a code editing view for Android (package `x.github.module.editor`), on the [piecetable](../piecetable/README.md) buffer, made for files of tens of MB and millions of lines.

- Large files: recorded rows, incremental layout, full relayouts in the background
- Word wrap (switchable any time), code folding, indent guides, bracket matching, automatic pairs
- Undo and redo: typing merged into steps, large content kept by reference (delete tens of MB and undo without copying); an exact modified state (undoing back to the save point is unmodified)
- Edit sessions: cursor and undo history saved with a file and restored the next time
- Syntax highlighting plugged in through an interface, no subclassing
- Cursor and selection handles (drawn or images), a magnifier, a fixed gutter, search highlighting, pinch to zoom
- Input method composition, hardware keyboard shortcuts, basic accessibility

The code supports API 24 and up; recorded rows need API 29, below that rows are drawn directly.

## Getting started

```xml
<x.github.module.editor.view.EditorView
    android:id="@+id/editor"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:textSize="14sp"
    android:fontFamily="monospace"
    app:wordwrap="false" />
```

Layout attributes: `textSize`, `textColor`, `textColorHighlight` (selection), `fontFamily`, `typeface`, `textStyle`, `text`, and `app:editable`, `app:wordwrap`, `app:lineNumberColor`, `app:currentLineColor`, `app:searchMatchColor`, `app:currentMatchColor`.

**Loading a file**: read it in chunks of characters on a background thread (do not decode chunks of bytes: a multi-byte character can be split between two):

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    val builder = PieceTreeTextBufferBuilder()
    InputStreamReader(input, Charsets.UTF_8).use { reader ->
        val chunk = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(chunk)
            if (n == -1) break
            builder.acceptChunk(String(chunk, 0, n))
        }
    }
    editor.setBuffer(builder.build())   // called off the main thread: laid out when it returns
}
// a short text can simply be editor.setText("...")
```

**Editing and searching**:

```kotlin
editor.insert("text"); editor.delete(); editor.replace("new", range)
editor.undo(); editor.redo(); editor.canUndo()

val matches = editor.find("keyword")      // slow: off the main thread
editor.setSearchResults(matches)          // highlights them all; they move with later edits
editor.gotoNextMatch()
editor.replaceAll("new", editor.getSearchResults())
```

**Saving and the modified state**: take the text and its version together on the main thread, mark it saved once written:

```kotlin
val version = editor.getTextVersion()
val text = editor.getText()
lifecycleScope.launch(Dispatchers.IO) {
    writeFile(uri, text)
    editor.markSaved(version)              // right even if the user typed on meanwhile
}
editor.setOnModifiedChangeListener { modified -> updateTitle(modified) }
```

**Edit sessions** (cursor, undo history, folds): a session holds no text, and the app stores it however it likes; before restoring, the hash of the content the app gives is checked to be sure the text is still the same.

```kotlin
val session = editor.saveSession(contentHash)          // main thread; may be null (loading, save point lost)
// ... serialize and store it (EditSession supports kotlinx.serialization)

// next time: after setBuffer has finished, on the main thread
when (editor.restoreSession(session, contentHash)) {
    EditSession.RestoreResult.CONTENT_MISMATCH -> deleteSessionFile()   // the file was changed elsewhere
    else -> {}   // RESTORED / TEXT_MODIFIED / BUSY (try later)
}
```

On rotation the view keeps its text size, word wrap, cursor, scroll position and folds itself (it needs an `android:id`); the text is still reloaded by the app.

## Threads

The editor's state belongs to the main thread, but these methods lay out the whole text, seconds on a large file, so **the calling thread decides where the layout runs**:

| Methods | Called off the main thread | Called on the main thread |
| --- | --- | --- |
| `setBuffer` `setTextSize` `setTypeface` `setWordwrap` | Lays out there, returns when done | Hands it to the editor's layout thread and returns at once; takes an `onDone` (main thread) |

- During a layout (`isLayoutBusy()`) every change to the text is refused; scrolling and reading work. `onBackgroundRelayoutStarted` / `Finished` can show progress.
- `find` is best off the main thread; `markSaved`, `isModified`, `invalidateStructure` work on any thread; `saveSession`, `restoreSession` and everything else are main thread only.

## Highlighting and code structure

A highlighter implements `SyntaxHighlighter`, with any technique (tree-sitter, TextMate, regular expressions, a language server):

```kotlin
editor.setSyntaxHighlighter(object : SyntaxHighlighter {
    // the styles of a line (ForegroundColorSpan, BackgroundColorSpan, StyleSpan, UnderlineSpan); null: plain text
    override fun styleLine(text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Spanned? = ...
    // optional: onAttached, onTextChanged (update a syntax tree), onEditFinished (reparse), onTextReplaced
})
```

- Every callback is on the main thread, and `styleLine` is called while drawing: it has to be fast. Parse on your own thread, then call `host.invalidateStyles(startLine, endLine)` (any thread) to redraw just the lines that changed.
- For coloring alone, the lighter `setLineStyler { }` is enough.

Bracket matching, indent guides and folding come from interfaces too, all off by default:

```kotlin
editor.setBracketMatcher(PlainBracketMatcher())                // knows nothing of strings and comments
editor.setIndentGuideProvider(IndentationGuideProvider(4))     // by indentation
editor.setFoldingRangeProvider(IndentationFoldingProvider(4))  // by indentation
editor.invalidateStructure()                                   // after a background parse, any thread
```

One tree-sitter engine can implement `SyntaxHighlighter` and all three interfaces from the same syntax tree (as the app's `TreeSitterStructure` does). For folding: `toggleFold(line)`, `foldAll()`, `unfoldAll()`, `setOnFoldChangeListener`; its look can be replaced with `setFoldStyle` or `setFoldMarkerDrawables`.

## Common settings

| Setting | Default | How to change |
| --- | --- | --- |
| Indent unit / what Tab types | 4 spaces | `setIndentUnit("\t")` for tabs |
| Tab stops | 4 | `setTabSize` |
| Auto indent | On | `setAutoIndent` |
| Automatic pairs | Off | `setAutoPairs(AutoPair.DEFAULTS)`, with the rules of nvim-autopairs; `setAutoPairFilter` to leave out strings and comments |
| Gutter (fixed, does not scroll sideways) | Off | `setGutterEnabled`; `setGutterDecoration` for marks such as breakpoints, `setOnGutterClickListener` |
| Line numbers | Shown | `setLineNumbersVisible` |
| Matching bracket | Highlight + bold + underline | `setBracketMatchStyle` |
| Handles | Drawn | `setHandleDrawables`, `useSystemHandles`, `setHandleColor` |
| Cursor | Drawn, 2dp wide | `setCursorDrawable`, `setCursorWidth`, `useSystemCursor` |
| Handles sway while scrolling | On, up to 12dp | `setHandleSwayEnabled`, `setHandleSwayAmplitude` |
| Edge auto-scroll while selecting | 20 lines a second | `setAutoScrollSpeed`, 0 turns it off |
| Held delete key deletes words | After 0.9 s, 10 words a second | `setFastDeleteSpeed`, 0 turns it off |
| History kept in an edit session | 200 steps, 1M characters | `setSessionLimits` |
| Undo steps / size / referenced storage | 1000 steps / 4M / 64M characters | In a subclass: `undoManager.maxSteps` / `maxChars` / `maxReferencedChars` |
| Typing merge interval | 500 ms | `EditHistory.DEFAULT_MERGE_DELAY_MS` |
| Largest copy | 400K characters | `EditorClipboard.MAX_TEXT_LENGTH` |

To change how something is drawn, or step into the editing flow, subclass `EditorView` and override its `protected open` methods (`drawText`, `drawSelectionBackground`, `drawFoldMarker`, `afterTextChanged`, ...); see [Design](DESIGN.md#extension-points).

## Limits

**Performance**

- An edit inserting fewer than 20,000 lines is still measured on the main thread; near that size it can pause for a hundred or two milliseconds (threshold `LARGE_EDIT_LINES`).
- During a relayout (text size, font, word wrap, after a large undo) editing is locked; on a large file you cannot type for a few seconds, so the app should show progress.
- Reading the piece tree as a `CharSequence` character by character is slow: never run a `Regex` on it directly; use `find()` or a snapshot.
- The piece tree never compacts itself: after long editing the app should call `compact()`. Storage referenced by the undo history is kept until its steps are dropped (capped around 128 MB).
- Unwrapped very long lines with tabs are drawn whole every frame; multi-line search copies every line in its range.

**Features**

- No right-to-left text or complex shaping (Arabic, Hebrew, some Indic scripts).
- No multiple cursors.
- Basic accessibility: only the visible text (up to 4000 characters), no reading by word or line.
- With word wrap, tab stops count from the start of each display row, so tabs on continuation rows do not line up as they would unwrapped.
- `PlainBracketMatcher` knows nothing of strings and comments.
- Saving and loading text is up to the app: neither the instance state nor edit sessions hold text.

## Tests

```bash
./gradlew :editor:testDebugUnitTest          # JVM: model, undo, layouts, folding, input methods, pairs, ...
./gradlew :editor:connectedDebugAndroidTest  # device: EditorViewTest
```

The two layout tests each make 12,000 random edits and compare the incremental result with a full relayout line by line. Touch gestures, scrolling and keyboard insets have no automated tests and are checked by hand.

## License

Apache License 2.0

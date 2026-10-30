# editor: Design and internals

> Part of the documentation of the [editor](../editor/README.md) module · [中文](editor_architecture_cn.md)

## Threading

The editor's state belongs to the main thread, but a few operations lay out the whole text again, which takes seconds on a large file. That must not happen on the main thread, or the app stops answering (ANR). So where these methods are called decides where the layout is done:

- **On a worker thread**: the layout runs right there, and the call returns when it is done. The way to go when the caller wants to wait for it, e.g. `withContext(Dispatchers.Default) { editor.setWordwrap(true) }`.
- **On the main thread** (a click handler, `lifecycleScope.launch { }`, which runs on the main thread too): the layout goes off to a thread of the editor's, and the call returns at once. Calls made this way are carried out one after the other, in the order they were made. `onDone` says when one is through, on the main thread; in between, `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished` are called as for a zoom.

```kotlin
// main thread: returns at once, the layout is done in the background
editor.setWordwrap(true) { /* laid out; main thread */ }

// worker thread: returns when laid out
lifecycleScope.launch(Dispatchers.Default) {
    editor.setWordwrap(true)
}

editor.isWordwrap()        // whether the layout in use wraps lines
```

| Method | Thread | Notes |
|---|---|---|
| `setTextSize` `setTypeface` `setWordwrap` | any | done when it returns on a worker thread; on the main thread handed to the layout thread, returns at once, takes an `onDone` |
| `setBuffer` | any | likewise; refused during a zoom, returns `false` |
| `saveSession` `restoreSession` | main | lay nothing out; call `restoreSession` once `setBuffer` is through (after it returned on a worker thread, or in its `onDone` when called on the main thread) |
| `isWordwrap` `markSaved` `isModified` `invalidateStructure` | any | `markSaved` takes effect on the main thread |
| `getTextVersion` | any, preferably main | only a version taken on the main thread lets `markSaved` place the saved text when the user typed on meanwhile |
| `find` | background recommended | read-only; hand the result back to the main thread with `setSearchResults` |
| everything else | main | |

These methods hand whatever touches the view over to the main thread themselves; callers need not do anything about it.

While a layout is in progress, the editor is "busy":

```kotlin
editor.isLayoutBusy()      // a relayout is running, or a zoom has not finished
editor.isZoomInProgress()  // a zoom only
```

While busy, every operation that changes the text is refused: typing, paste, undo, `setText` and so on. Scrolling and reading are not affected.

This is not a convention that callers have to keep to. Edits and relayouts go through the same lock: while a relayout reads the whole text no edit gets in, and a relayout on another thread waits for an edit in progress to finish. Every entry point that changes the text goes through this gate; there is no other way in.

Two kinds of relayout are triggered by the user, and the editor runs them in the background itself: after a pinch-zoom, and after the width changes in word wrap mode (rotation, split screen). The thread pool they run on can be chosen:

```kotlin
editor.setLayoutExecutor(Dispatchers.Default.asExecutor())
```

## Text buffers

The editor does not care how the text is stored. Everything it needs is in the `EditorBuffer` interface (lines, offsets, ranges, search, applying edits), and `EditorDocument` works against that interface only. The built-in implementation is `PieceTreeBuffer`, the piece tree used by VS Code.

```kotlin
editor.setBuffer(pieceTree)          // a piece tree, wrapped in a PieceTreeBuffer
editor.setBuffer(myBuffer)           // anything that implements EditorBuffer
editor.getBuffer()                   // the current EditorBuffer
editor.getTextBuffer()               // the current piece tree; throws if the buffer is not one
```

To plug in another structure (a rope, a gap buffer, a plain string for tests), implement `EditorBuffer`. The conventions are in the interface's comments: line numbers and columns start at 1, offsets at 0, ranges include their start and exclude their end; within one `applyEdits`, every range refers to the text as it was before the edit.

Every edit of the editor goes through `applySliceEdits`: contents come in as `TextSlice`s, and every change says what it removed (`SliceChange.removed`), which is what the undo history keeps. The interface has a default implementation on top of `applyEdits` and Strings, so another structure works without implementing it, only with large content copied. `PieceTreeBuffer` uses the piece tree's own `applySliceEdits`, which keeps large content as references.

Edit sessions (`saveSession` / `restoreSession`) do not contain the text, so they work whatever buffer the text is stored in.

## Architecture

`EditorView` itself is a thin shell with three jobs: the public API, the extension points for subclasses, and the Android View lifecycle. Every other concern has one owner:

| Concern | Class | Package |
|---|---|---|
| The buffer, queries, the edit pipeline | `EditorDocument` | `model` |
| Undo/redo stacks, merging rapid typing, the save point | `EditHistory`, `UndoManager`, `UndoStep`, `HistoryChange` | `model` |
| One file's edit session (cursor, undo history, folds) | `EditSession` | `model` |
| Text versions, save point marks, building and restoring sessions | `SaveTracker` | `view` |
| Interfaces and simple implementations for matching brackets, indent guides, folding; what is folded | `BracketMatcher`, `IndentGuideProvider`, `FoldingRangeProvider`, `FoldingModel` | `structure` |
| The layout without the folded lines | `FoldedLayout` | `layout` |
| Asking, caching, folding and drawing for brackets, indent guides, folding | `CodeStructure` | `view` |
| How fold markers and placeholders look | `FoldStyle`, `DefaultFoldStyle`, `DrawableFoldStyle` | `render` |
| The edits of indenting; Enter, Tab and Shift+Tab | `IndentEdits`, `Indenter` | `input` |
| Tab stops: the width of tabs, drawing around them | `TabStops` | `layout` |
| Taking back a handle's last step when the finger lifts | `TouchUpFilter` | `input` |
| A new selection growing out from the finger | `SelectionReveal` | `input` |
| Search matches and how they follow edits | `SearchResults` | `model` |
| Font measurements (cached) | `TextMetrics` | `layout` |
| Mapping between screen rows and buffer lines | `TextLayout`, `UnwrappedLayout`, `WordwrapLayout` | `layout` |
| Full relayouts, their threads, edits vs. relayouts | `LayoutCoordinator` | `layout` |
| The visible-line loop, the render cache | `TextRenderer` | `render` |
| Line backgrounds, selection, line numbers | `LineDecorationPainter` | `render` |
| Smooth scrolling, flinging, overscroll glow | `ScrollController` | `input` |
| Scrollbar thumbs | `ScrollbarController` | `input` |
| Cursor, selection, handles | `SelectionController` | `input` |
| Moving the caret along the text by key (character, word, line and text start and end) | `CaretNavigator`, `CaretMove` | `input` |
| Handle looks (abstract base and two looks) | `TextHandleDrawable`, `DrawnTextHandle`, `ResourceTextHandle` | `input` |
| Scrolling at the edges while selecting | `EdgeAutoScroller` | `input` |
| Cursor look (the drawn bar) | `DrawnCursorDrawable` | `input` |
| The gutter that stays put | `Gutter` | `render` |
| Speeding up a held delete key | `BackspaceRepeat` | `input` |
| Touch routing | `GestureController` | `input` |
| Hardware keyboard | `KeyboardController` | `input` |
| Input method composing | `InputComposer`, `InputMethodBridge`, `EditorInputConnection` | `input` |
| Screen readers | `EditorAccessibility` | `input` |
| Drawing styled text | `LineStyler`, `StyledTextPainter` | `render` |
| The highlighting engine interface | `SyntaxHighlighter` | `highlight` |
| The text storage interface and its default implementation | `EditorBuffer`, `PieceTreeBuffer` | `model` |
| Soft keyboard visibility and what it covers | `ImeController` | `input` |
| Clipboard | `EditorClipboard` | `input` |

Dependencies go one way: `view` depends on the other packages, none of them depends on `view`, and `model` depends on no UI class.

Each collaborator that needs something from the view declares a narrow `Host` interface. They are all gathered in `EditorEnvironment`, which a private adapter inside `EditorView` implements. So the collaborators do not know one another, and internal callbacks do not show up among the public methods of `EditorView`.

### Two coordinate systems

- **View coordinates**: used by touch events; the origin is the top left corner of the view, padding included.
- **Content coordinates**: used by everything else; the origin is the top left corner of the text.

`content = view + scroll − padding`. The cursor, the handles and the scrollbar thumbs are positioned in content coordinates and drawn under the same canvas transform as the text.

### Two layouts

| | No wrap | Word wrap |
|---|---|---|
| One screen row is | one buffer line | a piece of a buffer line |
| Storage | one `Int` width per line | one object per screen row |
| Text size change | scaled in proportion, at once | needs a relayout |
| One edit | measures the changed lines only | relays out the changed lines only |

Both build a full relayout in new data and swap it in as a whole, so a relayout that is called off leaves no trace.

With code folded, the editor uses a `FoldedLayout` laid over either of them: the folded lines have no rows, everything else is the inner layout's. It only keeps where the hidden rows are, and works that out again after the inner layout or the folds have changed (told by `TextLayout.version`); edits and relayouts still happen to the inner layout only.

## Limits and defaults

| Item | Default | How to change |
|---|---|---|
| Undo steps | 1000 | `undoManager.maxSteps` in a subclass |
| Undo memory | 4 million (chars of Strings + 16 per run of references + 32 per change) | `undoManager.maxChars` in a subclass |
| Size of changes that merge | up to 16K chars | `HistoryChange.MERGE_LIMIT` |
| History kept in an edit session | 200 steps, 1 000 000 chars | `setSessionLimits` |
| Matching brackets, indent guides, code folding | off | `setBracketMatcher`, `setIndentGuideProvider`, `setFoldingRangeProvider` |
| Merge interval for rapid typing | 500 ms | `EditHistory.DEFAULT_MERGE_DELAY_MS` |
| Largest copy | 400,000 chars | `EditorClipboard.MAX_TEXT_LENGTH` |
| Longest line in the render cache | 4096 chars | internal constant of `TextRenderer` |
| Smallest scrollbar touch target | 48dp | internal to `ScrollbarController` |
| Scrollbars follow the scroll | 32dp of scrolling slides a thumb its whole thickness: in while its axis leads, out while the other one does, and it stays where it is when the finger stops | internal to `ScrollbarController` |
| Scrollbars fade after scrolling stops | after 2 s, sliding out from where they are in 300 ms | internal to `ScrollbarController` |
| Selection handle sway on scroll | on, at most 12dp | `setHandleSwayEnabled`, `setHandleSwayAmplitude` |
| Scrolling speed at the edges | 20 lines a second | `setAutoScrollSpeed` |
| Word deletion speed of a held delete key | 10 words a second, after 0.9 seconds | `setFastDeleteSpeed` |
| Handle look | drawn by the editor | `setHandleDrawables`, `useSystemHandles` |
| Cursor look | drawn by the editor, 2dp wide | `setCursorDrawable`, `setCursorWidth`, `useSystemCursor` |
| Gutter | off (numbers in the rows) | `setGutterEnabled` |
| Line numbers | shown | `setLineNumbersVisible` |
| How matching brackets are marked | highlight, bold, underline | `setBracketMatchStyle` |
| One level of indentation | 4 spaces | `setIndentUnit` |
| A tap on a fold placeholder unfolds | on | `setFoldPlaceholderClickable` |
| Storage the undo history may refer to | 64 million chars | `undoManager.maxReferencedChars` in a subclass |
| Auto-indent | on | `setAutoIndent` |
| Auto-closing pairs | none | `setAutoPairs` |
| Tab stops | every 4 spaces | `setTabSize` |
| How folding looks | VS Code style (chevron, three dots) | `setFoldStyle` |
| Room between gutter and text | 8dp | `setGutterTextGap` |

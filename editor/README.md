# editor

English | [中文](README_cn.md)

A code-oriented text editing widget for Android, built on a piece tree text buffer and designed for large files (tens of MB, millions of lines).

Package `x.github.module.editor`, entry class `EditorView`.

## Features

- Large-file editing: per-line render caching, incremental layout, background relayout
- Word wrap, switchable at any time
- Undo and redo, with rapid typing merged into one step; large content is kept as references, so deleting tens of megabytes and undoing it copies no text
- An exact modified state: undoing back to the saved text makes it unmodified
- Edit sessions that are saved with a file and restored the next time it is opened, unsaved edits coming back with redo
- Matching brackets (highlighted, bold, underlined), indent guides and code folding, the parsing left to an engine such as tree-sitter
- Pinch-to-zoom text size
- The cursor and the selection handles drawn by the editor or taken from images, with pop-in, spring-back and scroll-sway animations, and a magnifier (see below)
- An optional gutter with the line numbers that stays put while the text scrolls sideways, with markers and taps of the app's; line numbers line up in proportional fonts too
- Action mode (cut / copy / paste toolbar)
- Search match highlighting that follows the text as it is edited
- Input method composing (pinyin, swipe typing, ...)
- Hardware keyboard: arrow keys, word-wise movement, Shift selection, common shortcuts
- Edge-to-edge: the cursor and the scrollbars stay visible above the soft keyboard
- Text size, colors, font and more can be set in the layout file
- Syntax highlighting plugs in through an interface, no subclassing needed
- Text size, word wrap and reading position survive screen rotation
- Basic accessibility support (screen readers)

Minimum Android 7.0 (API 24). Render caching needs Android 10; below that the editor draws directly.

## Quick start

### Layout

```xml
<x.github.module.editor.view.EditorView
    android:id="@+id/editor"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="8dp"
    android:textSize="14sp"
    android:textColor="#D4D4D4"
    android:fontFamily="monospace"
    app:lineNumberColor="#858585"
    app:wordwrap="false" />
```

Supported attributes:

| Attribute | Effect |
|---|---|
| `android:textSize` | Text size |
| `android:textColor` | Text color |
| `android:textColorHighlight` | Selection background |
| `android:fontFamily` | Font family name or `@font/` resource |
| `android:typeface` | `sans` / `serif` / `monospace`, used when `fontFamily` is not set |
| `android:textStyle` | `bold` / `italic` |
| `android:text` | Initial text |
| `app:editable` | `false` makes the editor read-only |
| `app:wordwrap` | Word wrap |
| `app:lineNumberColor` | Line number color |
| `app:currentLineColor` | Background of the cursor's line |
| `app:searchMatchColor` | Background of search matches |
| `app:currentMatchColor` | Background of the current match |

### Loading text

```kotlin
// a short text: the main thread is fine
editor.setText("fun main() {}")

// a file: read it in chunks, build the buffer and load it on a background thread
lifecycleScope.launch(Dispatchers.IO) {
    val builder = PieceTreeTextBufferBuilder()
    InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
        val chunk = CharArray(64 * 1024)
        while (true) {
            val count = reader.read(chunk)
            if (count == -1) break
            builder.acceptChunk(String(chunk, 0, count))
        }
    }
    editor.setBuffer(builder.build())    // the layout is done when this returns
}
```

Read the chunks as characters through a `Reader`, not as bytes that are decoded chunk by chunk: a multi-byte character (a Chinese character, say) may be split between two chunks, and decoding each chunk on its own turns it into garbage.

`setBuffer` shows the new text as soon as the buffer is swapped; the layout goes on on the calling thread. Until it is done the text can be scrolled and read, but not edited.

### Editing

```kotlin
editor.insert("text")                  // insert at the cursor, or replace the selection
editor.delete()                        // backspace, or delete the selection
editor.replace("new", range)
editor.undo()
editor.redo()
editor.canUndo()                       // includes what was just typed and not merged yet

editor.setIndentUnit("    ")             // one level of indentation: 4 spaces (default), other spaces, or "\t"
editor.setAutoIndent(true)               // Enter keeps the indentation of the line (default)
editor.setTabSize(4)                     // tab stops every so many spaces (default 4); lays the text out again
editor.indentLines()                     // one level more on the selected lines (Tab)
editor.outdentLines()                    // one level less on the selected lines (Shift+Tab)
```

- **Tab types spaces**: the Tab key (of a keyboard, and the `"\t"` an input method commits) types one level of indentation, 4 spaces by default rather than a tab character; Backspace takes spaces away one at a time. Tabs already in a text stay, shown at their tab stops. For tab characters, `setIndentUnit("\t")`.
- **Indentation**: Tab indents the lines of a selection that spans several lines, and types one level of indentation otherwise; Shift+Tab takes one level away. Indenting and outdenting are an undo step each, and the selection stays on its text. Enter starts the new line with the indentation of the line, as far as it goes before the cursor.
- **Tab stops**: a tab reaches to the next tab stop, one every `setTabSize` spaces, counted from the start of its row. Measuring, drawing, the cursor, the selection, hit testing and line breaking all go by the same stops.
- **Dead keys**: an accent followed by a letter gives the accented letter (´ then e gives é); an accent followed by a space gives the accent itself.
- **Characters stay whole**: when an end of an edit range falls between the two halves of a surrogate pair (an emoji, some CJK characters), the range takes the whole character; an insertion there goes in front of it. Undo and redo replay their exact ranges and are not affected.

### Auto-closing pairs

```kotlin
editor.setAutoPairs(AutoPair.DEFAULTS)            // () [] {} "" '' and backticks; empty list: off (default)
editor.setAutoPairs(AutoPair.parse("() [] <>"))   // the pairs as text, two chars each
editor.setAutoPairFilter { pair, offset -> ... }  // say no at some places (in a string, a comment)
editor.setAutoPairWrapSelection(true)             // an open char typed over a selection wraps it (default)
editor.typeText("(")                              // types like the keyboard: may complete a pair
```

As nvim-autopairs does it: an open char types its close char too, with the cursor in between; not before a letter, a digit or one of `%'["`.$`, a quote not right after a letter (`don't`), and a bracket not on a line that has more close brackets than open ones (`|a)`). A close char typed right before the same char steps over it, Backspace between an empty pair removes both, and Enter between a bracket pair (`{|}`) puts the close bracket on a line of its own with the cursor indented in between. It works for the hardware keyboard, single chars committed by an input method outside of composing, and `typeText`; `insert` puts text in as it is.

### Undo and redo

Rapid typing (no more than 500 ms between edits) is merged into one step. Only small changes merge; a large one, pasting a big block or deleting a whole section, becomes a step of its own at once.

**A large undo does not stall the main thread**: an edit that inserts 20 000 lines or more (undoing the deletion of half a large file, pasting a whole file) gives the inserted lines a rough layout at first: a width estimated from their length without word wrap, one row each with it. Once the edit is done, the text is laid out properly in the background, as after a zoom: edits are refused meanwhile, and the app hears `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished`, for its progress bar. Edits of fewer lines are still measured on the main thread right away. With word wrap, an edit now replaces the rows it affects in one go, so large deletions and insertions take time in proportion to the lines, no longer to their square.

The content in the undo history is held as `TextSlice`s (piecetable module): short content as a String, content of 16K chars and more as references into the storage of the piece tree, without copying it. So "select all, delete" on a file of tens of megabytes leaves a few references on the undo stack, and undoing puts them back, again without a copy. A large paste comes in as a String, and becomes references the first time it is undone.

Worth knowing:

- References only work with the piece tree they came from. The history is cleared when another text is loaded, so in normal use this never comes up.
- References keep the storage they point at alive, also the old chunks after `compact()`, until the undo steps holding them are dropped.
- The undo memory limit counts "chars of Strings + 16 per run of references + 32 per change". A replace-all of 100 000 matches counts for its objects too.
- An `EditorBuffer` other than the piece tree has no references; the default implementation copies text as before, and is still correct.

### Searching

```kotlin
val matches = editor.find("keyword")   // slow on large texts: better on a background thread
editor.setSearchResults(matches)       // highlight all matches
editor.gotoNextMatch()                 // go to the next one and highlight it as current
editor.gotoPreviousMatch()
val index = editor.getMatchResultIndex()   // which one is current, from 0
editor.replace("new", editor.getSearchResults()[index])
editor.replaceAll("new", editor.getSearchResults())
editor.clearSearchResults()
```

The editor keeps its own copy of the matches. When the text is edited, matches after the edit move along with the text, and matches the edit touches are removed.

### Modified state and save points

```kotlin
// writing: on the main thread, take the version together with the text, mark it saved once written
val version = editor.getTextVersion()
val text = editor.getText()
lifecycleScope.launch(Dispatchers.IO) {
    val hash = writeFileAndHash(uri, text)     // the app writes the file, hashing on the way
    editor.markSaved(version)
}

editor.isModified()    // does the text differ from the saved one?
editor.setOnModifiedChangeListener { modified -> updateTitle(modified) }
```

Text loaded with `setBuffer` or `setText` counts as saved.

`isModified` follows the undo history instead of counting edits:

- Undoing back to the saved text makes it unmodified; so does undoing past it and redoing back up to it.
- Typing a char and deleting it again within one typing burst leaves it unmodified.
- Undoing past the save point and then typing something new means the saved text can no longer be reached: the text counts as modified until it is saved again.
- The same when the save point is dropped with the oldest steps beyond the undo limit.
- After an edit that is not undoable (`applyEdits(..., computeUndoEdits = false)`) the editor can no longer tell where the saved text is; modified until saved again.

Take `getTextVersion` on the main thread, together with the text. It notes where the undo history stands and ends the current typing burst (a save point lies between two steps), so that `markSaved(version)` finds the text that was written even if the user typed on in the meantime. A version taken on another thread is only good if nothing is edited until `markSaved`; otherwise the editor plays safe and the text counts as modified.

`markSaved` can be called on any thread and takes effect on the main thread. Changes of the modified state reach the listener of `setOnModifiedChangeListener` on the main thread; a subclass can also override `onModifiedChanged`.

### Edit sessions

When a file is closed, its edit session can be saved and put back the next time the same file is opened: the cursor position and the undo and redo history.

**A session always belongs to the saved text, the one on disk.** It can be taken while there are unsaved edits: the edits made since the save point go onto the redo stack of the session. When the file is opened next, the text is the one on disk, and redo brings the unsaved edits back one step at a time. The other way round, steps undone past the save point go back onto the undo stack.

```kotlin
// saving: at any time, e.g. when the app goes to the background; take the session on
// the main thread, serialize it in the background
val session = editor.saveSession(contentHash)          // contentHash: the hash of the file on disk
if (session != null) {
    lifecycleScope.launch(Dispatchers.IO) {
        sessionFile(uri).writeText(Json.encodeToString(session))
    }
}

// restoring: load the text in the background, put the session back on the main thread
lifecycleScope.launch(Dispatchers.IO) {
    editor.setBuffer(buffer)                           // laid out when it returns
    val session = runCatching {
        Json.decodeFromString<EditSession>(sessionFile(uri).readText())
    }.getOrNull() ?: return@launch
    withContext(Dispatchers.Main) {
        when (editor.restoreSession(session, contentHash)) {
            EditSession.RestoreResult.CONTENT_MISMATCH -> sessionFile(uri).delete()   // will never fit again
            else -> {}
        }
    }
}
```

An `EditSession` contains neither the text nor which file it belongs to. The text can be tens of megabytes and is on disk already; the app loads it again. How a file is identified (a uri, a path) is the app's business: it stores sessions in whatever way suits it, for example named after a hash of the uri.

Undo steps are edits at fixed offsets; replayed on another text they would garble it. So a session carries what it takes to recognise its text, and `restoreSession` checks it all before restoring:

| Check | Provided by | Catches |
|---|---|---|
| `contentHash` | the app; the editor only stores and compares it | the file was changed outside the app |
| `textLength`, `lineCount` | the editor, worked out for the saved text when saving | the same bytes read into another text (another encoding, other line breaks), or a hash computed for the wrong thing |
| edited since loaded | the editor's modified state | the text is no longer the one the session belongs to |

What `restoreSession` returns:

| Result | Meaning | What the app does |
|---|---|---|
| `RESTORED` | history and cursor are back | nothing |
| `CONTENT_MISMATCH` | the session belongs to another text and never will fit | may delete the session file |
| `TEXT_MODIFIED` | the text was edited since it was loaded | skip it, keep the session file |
| `BUSY` | a text is being loaded, laid out or zoomed; nothing was checked | try again later |

- A session keeps the part of the history nearest the save point: by default at most 200 steps and 1 000 000 chars (old and new content together), the redo side (unsaved edits) first. A step that does not fit ends its side, since the steps beyond it cannot be reached without it. Change this with `setSessionLimits(maxSteps, maxChars)`.
- In a session the content is Strings: references mean nothing outside their piece tree. The steps kept are copied into Strings on the main thread; the limits above keep that quick.
- `saveSession` returns `null` while a text is being loaded, and when the save point is lost (see the previous section). Write the file then, and take the session after.
- The cursor is kept as an offset into the saved text: with unsaved edits, where the first of them is. When restored it is kept within the text and scrolled to the middle of the screen.
- `saveSession` and `restoreSession` are main-thread only and throw elsewhere. Call `restoreSession` once `setBuffer` is done; before that it returns `BUSY`.
- The hash algorithm is the app's choice (SHA-256, say); use the same one for saving and restoring. Compute it while writing or reading the file, no extra pass needed.
- `EditSession` is serializable (kotlinx.serialization) and holds copies of both stacks; later edits do not change it.

### Screen rotation

The widget saves and restores the state that is its own (it needs an `android:id`): the text size the user zoomed to, whether lines are wrapped, and the cursor and scroll position. The text is not part of it; the app loads it again. If the same text is loaded, the cursor and the scroll position go back to where they were; for any other text they are ignored.

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

## Syntax highlighting

A highlighting engine plugs in through the `SyntaxHighlighter` interface. No subclass of `EditorView` is needed, and any technique will do (tree-sitter, TextMate grammars, regular expressions, a language server):

```kotlin
class MyHighlighter : SyntaxHighlighter {
    // required: the styles of one line, as offsets into text; null draws it as plain text
    override fun styleLine(text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Spanned? = ...

    // optional, all do nothing by default
    override fun onAttached(host: SyntaxHighlighter.Host) {}          // attached; host.buffer is the current text
    override fun onTextReplaced(buffer: EditorBuffer) {}                // the whole text was replaced (another file)
    override fun onTextChanged(change: EditorDocument.AppliedChange) {}   // one change was written
    override fun onEditFinished() {}                                  // an edit is complete: a good time to reparse
    override fun onDetached() {}
}

editor.setSyntaxHighlighter(MyHighlighter())
```

`styleLine` returns an `android.text.Spanned`; `ForegroundColorSpan`, `BackgroundColorSpan`, `StyleSpan` (bold, italic) and `UnderlineSpan` are supported.

A styler can also give a whole line a background, from where the text starts to the right edge of the view, by overriding `lineBackground(bufferLine): Int?` (a code block in Markdown, for instance). It is drawn under the current line, the selection and the search matches, and asked for every visible row on every frame, so it must be a quick lookup.

All methods are called on the main thread, and `styleLine` is called while drawing, so it must be fast. Do slow work (parsing a large file) on a thread of your own, answer from what you have in the meantime, and call `host.invalidateStyles()` when done to have it redrawn; that method can be called on any thread. When you know which lines changed (tree-sitter's changed ranges after an incremental parse), call `host.invalidateStyles(startLine, endLine)` instead: only those lines are styled again, the others keep their recorded rows. After an edit the editor itself records again only the rows of the edited lines, or everything from the first edited line down when lines were added or removed.

The `AppliedChange` given to `onTextChanged` carries positions and shape (how many lines were inserted, how long); the inserted content is in `content`, a `TextSlice`. Call `insertedText()` for the text: free for typing, a copy of the whole block for a large undo or redo, so only when needed. Incremental parsing with tree-sitter needs the positions only.

The same syntax tree can usually answer for matching brackets, indent guides and folding too; see the next section.

For coloring alone, without following changes to the text, the lighter `editor.setLineStyler { ... }` will do. And any object can follow the three stages of an edit with `editor.addEditCallback(...)`.

## Code structure: matching brackets, indent guides, folding

Which brackets pair up, where indent guides go and what can be folded depends on the language, and is answered by implementations of three interfaces: tree-sitter, a language server, or the simple ones of the `structure` package that go by the text. The editor asks, caches the answers, draws, and does the actual folding.

```kotlin
editor.setBracketMatcher(PlainBracketMatcher())                // matching brackets, off by default
editor.setIndentGuideProvider(IndentationGuideProvider(4))     // indent guides, off by default
editor.setFoldingRangeProvider(IndentationFoldingProvider(4))  // code folding, off by default

editor.jumpToMatchingBracket()              // the cursor to the partner bracket
editor.getBracketPair()                     // the pair at the cursor
editor.invalidateStructure()                // when an engine finished parsing; any thread
```

| Interface | Answers | Asked | Comes with |
|---|---|---|---|
| `BracketMatcher` | the bracket before or after the cursor, and its partner | after the cursor moved or the text changed | `PlainBracketMatcher`: scans line by line, knows no strings or comments; override `isBracket` to leave them out |
| `IndentGuideProvider` | the guides of the lines around the screen | when the text changed, or the screen moved to another block of 64 lines | `IndentationGuideProvider`: from the indentation, a blank line taking the smaller of its neighbours' |
| `FoldingRangeProvider` | the ranges starting on the lines around the screen | as above | `IndentationFoldingProvider`: a line followed by deeper indented lines starts a range |

All three are called on the main thread, and their answers are cached: as long as the text, the cursor and the lines asked about stay the same, they are not asked again. An engine that parses in the background answers with what it has, and calls `invalidateStructure()` (or `host.invalidateStructure()` from a highlighter) when done. A tree-sitter engine can implement `SyntaxHighlighter` and these three interfaces at once, one syntax tree serving all of them.

### Matching brackets

The bracket before or after the cursor and its partner are marked, in three ways by default:

- **Highlight**: their background is filled with the color of `setBracketMatchColor`.
- **Bold**: the bracket is drawn once more, in bold, over the text, in the color it has (from the highlighter). Bold thickens the same glyph and keeps its width, so it covers the plain one exactly, and no recorded row has to be recorded again.
- **Underline**: a line under the bracket, a little thicker than an ordinary underline, in the color of `setBracketUnderlineColor` (null: the text color).

```kotlin
editor.setBracketMatchStyle(highlight = true, bold = true, underline = false)   // highlight and bold only
editor.setBracketUnderlineColor(Color.RED)
```

The three parts are drawn by `drawBracketHighlight` (background), `drawBracketText` (the bracket) and `drawBracketUnderline` (the line), each of which a subclass can override.

### Indent guides

An indent guide is an `IndentGuide(startLine, endLine, column, anchorLine)`: drawn from `startLine` to `endLine` at the x where `column` starts on `anchorLine`, straight down. An engine that knows the block can anchor it on the line that opens the block, at its first char. The guide of the innermost block around the cursor is highlighted. Guides are drawn outside the recorded rows: a cursor move that changes the highlighted guide records nothing again.

### Code folding

With a `FoldingRangeProvider`, a fold marker stands next to the number of each line a range starts on: pointing down while open, to the right while folded. With the gutter on it is in a column of its own at the gutter's right; with the gutter off, right of the line number in the row (in the same place with the line numbers hidden). A tap on it folds or unfolds; a folded line ends in a "⋯" placeholder, and a tap on that unfolds too.

A tapped marker answers the way a Material 3 icon button does, with the Material Components library the editor depends on anyway:

- **A ripple** spreads from the marker and fades (an unbounded `RippleDrawable`), in the theme's `colorOnSurfaceVariant` at the MD3 pressed-state opacity (10%), resolved with `MaterialColors`; with a theme that is not a Material one, in the line number color.
- **The chevron turns** to its new direction, with the duration and easing of the theme's `motionDurationMedium2` and `motionEasingStandardInterpolator`; with a theme that does not define them, the MD3 values (300 ms, cubic-bezier(0.2, 0, 0, 1)). Unfolding by tapping the placeholder turns it back too. With animations turned off in the system, the chevron is simply there.

A subclass that overrides `drawFoldMarker` decides what to draw; the ripple stays.

**Looks**: the default `DefaultFoldStyle` draws like VS Code, a chevron for the marker and three dots on a faint rounded background for the placeholder. To change it:

```kotlin
// drawables: one marker for open and one for folded (crossfaded while turning),
// tinted in the marker color by default
editor.setFoldMarkerDrawables(expandedIcon, foldedIcon)
editor.setFoldPlaceholderDrawable(ellipsisIcon)     // the placeholder; null for the three dots

// all at once, also untinted, in the drawables' own colors
editor.setFoldStyle(DrawableFoldStyle(expandedIcon, foldedIcon, ellipsisIcon, tint = false))

// drawn entirely your way: implement FoldStyle's drawMarker, placeholderWidth, drawPlaceholder
editor.setFoldStyle(object : FoldStyle { ... })
```

A marker drawable is drawn into a square in the marker's place, centered; a placeholder drawable is a row tall, and as wide as its aspect ratio makes it. A subclass can also override `drawFoldMarker` and `drawFoldPlaceholder`.

```kotlin
editor.toggleFold(line)                     // fold or unfold the range starting on this line
editor.fold(FoldingRange(3, 10))            // lines 4 to 10 hidden, line 3 stays
editor.unfold(3)
editor.foldAll()                            // every range the provider knows of (asks about all lines)
editor.unfoldAll()
editor.isFolded(3)                          // is the range starting on line 3 folded?
editor.isLineHidden(5)                      // is line 5 hidden in a fold?
editor.getFoldedRanges()
editor.setOnFoldChangeListener { folded -> /* after folding or unfolding, edits included */ }
editor.setOnFoldMarkerClickListener { range -> false }   // asked first; true means taken care of
```

Folding is done in the layout: a `FoldedLayout` laid over the current layout leaves the folded lines out of the rows. Rendering, the selection, the gutter, scrolling and the up and down keys all work by rows, so all of them skip folded lines, with word wrap and without.

- **Edits**: an edit below a fold leaves it alone; one above it moves it with its lines; one on its start line alone (no lines added or removed) keeps it; one that reaches into the folded lines (an undo, a replace-all) unfolds it.
- **The cursor**: folding moves a cursor or selection end in the folded lines to the end of the start line. When the cursor gets into folded lines (search, go to line, an arrow key, an undo), they unfold as the cursor moves there, and the cursor is brought into view.
- **Clickable placeholder**: by default a tap on the placeholder unfolds. With `setFoldPlaceholderClickable(false)` it is as in VS Code: a tap there is a tap on the text, and the cursor goes to the end of the start line. That makes it easy to put the cursor after the last char of a folded line without unfolding by mistake. The fold marker still folds and unfolds.
- Folds can nest. Loading another text, or setting the provider to `null`, unfolds everything.
- **Kept**: folds are in the saved instance state, and come back after a rotation when the same text is loaded again. They are in edit sessions too (`EditSession.foldedLines`), but only when the session's text is the one on screen: with unsaved edits the line numbers belong to another text, and none are kept.
- The color of markers and placeholders is set with `setFoldMarkerColor`.

## Subclassing and extending

Most customisation needs no subclass: `SyntaxHighlighter` for highlighting, `addEditCallback` for following edits. To change how things are drawn, or to step into the edit pipeline, subclass `EditorView` and override its `protected open` methods.

### Drawing

| Method | Draws |
|---|---|
| `drawText` | the text of one line; the default uses the `LineStyler` |
| `drawLineNumber` | line numbers; only called with the gutter off (`setGutterEnabled(false)`), when the numbers are drawn in the rows of text |
| `drawLineBackground` | the background of the cursor's line |
| `drawSelectionBackground` | the selection |
| `drawMatchesBackground` | search matches |
| `drawComposingUnderline` | the underline of the text being composed |
| `drawBracketHighlight` | the background of the bracket at the cursor and its partner |
| `drawBracketText` | the bracket at the cursor and its partner themselves, bold over the text |
| `drawBracketUnderline` | the line under the bracket at the cursor and its partner |
| `drawIndentGuide` | one indent guide; `active` for the one of the block the cursor is in |
| `drawFoldMarker` | a fold marker; `folded` tells whether it is folded |
| `drawFoldPlaceholder` | the placeholder at the end of a folded line |
| `drawRegionHighlight` | decides which backgrounds a line gets; rarely needs overriding |

`drawText` is called in two situations: when a line is recorded into the render cache (once per line, then replayed) and when drawing directly (every frame). Do not assume how often it is called, and do not keep state across lines in it.

### Text changes

| Method | When |
|---|---|
| `beforeTextChanged(edits: List<SliceEdit>)` | an edit is about to be applied; the buffer still holds the old text |
| `onTextChanged` | one change was written to the buffer; called once per change of an edit |
| `afterTextChanged(changes: List<AppliedChange>, ...)` | the whole edit is complete |

Overrides must call `super`. Incremental parsing usually updates the syntax tree in `onTextChanged` and reparses in `afterTextChanged`. Content travels as `TextSlice`s and is read only when asked for (see "Syntax highlighting"), so a large undo does not copy the whole block into the callbacks.

### Others

| Method | Purpose |
|---|---|
| `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished` | a background relayout starts and ends, always in pairs: after a zoom, after the width changed with word wrap, and for `setTextSize` / `setTypeface` / `setWordwrap` called on the main thread; e.g. for showing progress |
| `onCopyResult` | the outcome of a copy or cut (copied, nothing selected, too large, refused by the system); the app decides how to tell the user |
| `onMergeStarted` / `onMergeCommitted` | rapid typing starts being merged / the merged step is pushed; e.g. for updating an undo button |
| `onModifiedChanged` | the modified state changed, see `isModified` |
| `onFoldMarkerClick` | a tap on a fold marker; asks `setOnFoldMarkerClickListener` first, then folds or unfolds |
| `onFoldChanged` | after folding or unfolding; hands it to `setOnFoldChangeListener` by default |
| `onZoomBegin` / `onZoom` / `onZoomEnd` | the stages of a pinch-zoom |

## Cursor and handles

By default the handles are drawn by the editor itself: a thin stem standing on the text, with a round knob. The knob of the selection start is above the row, the knob of the end below it, so the two never overlap; the insertion handle is a knob below the cursor. They can also be images, see "Handle looks" below.

| Behavior | Details |
|---|---|
| Character by character | Over text, a handle stands on the character it marks and moves with the selection one character at a time |
| Follows the finger off the text | Where there is no text (past the end of a line), the handle and the cursor follow the finger sideways, but not off what can be seen, and not under the gutter; vertically they always stand on a row, never between two |
| Springs back | Let go off the text, a handle springs back to its place on the text, a little past it and back. If that place is no longer on screen (the handle was dragged past the end of a short line or below the last line while the view scrolled on at an edge), the view scrolls back to show it, as it does for the cursor after a tap |
| No flicker between rows | The finger has to go some way into the next row (half a line height, limited to 8dp–45dp) before the handle changes rows; resting on the border between two rows does not make it jump |
| Appearing and disappearing | A handle grows in with a slight overshoot, and shrinks away when it goes |
| Long press selects a word | The selection grows out from where the finger pressed; the handles stay hidden while the finger is down and pop up when it lifts. Pressed at an edge of the screen (half under the keyboard, close to the left or right edge), the selection is scrolled into view on release, as the cursor is after a tap |
| Dragging on after a long press | Moving on without lifting extends the selection from the pressed word to the finger, character by character, with the same row rules as a dragged handle. The finger itself does not drag the content, but at an edge the view scrolls by itself, as when dragging a handle |
| Scrolling at the edges | While a handle is dragged or a long-press selection is extended, a finger held near an edge of what can be seen (any of the four; the bottom one above the keyboard) keeps the view scrolling frame by frame, and the selection grows with it; the finger need not move. The closer to the edge, the faster, up to the set speed at the edge or past it (20 lines a second by default). It speeds up gently over 0.3 seconds, so a finger merely passing through does not throw the text away. It stops when the finger moves away from the edge, lifts, or the text ends. It only scrolls towards text: not further right once the text of the row being selected on is all in view, not further down once the last line is; the finger can go out into the blank without the text running away from under it |
| Jumping back to the row | When a drag goes onto a short line whose text is all off to the left (the view was scrolled sideways for a long one), the view jumps sideways at once, putting the end of that line about two thirds across, and the selection goes on under the finger |
| Handles never meet, and can swap | The two handles never mark the same position: at least one character stays selected. Dragged past the other one, a handle swaps roles with it and the finger goes on holding its end of the selection. On a swap the character that was left stays selected, and the handle that was not dragged does not move |
| Overlapping handles | When a short selection makes the touch areas of the two handles overlap, the one whose knob is nearer to the touch is taken |
| Sways when scrolled | Scrolled sideways or up and down, the two selection handles hang back a little behind the text, by how fast it is scrolled: further the faster it goes, hardly at all for a slow drag, 12dp at most. When the scrolling eases off they swing back into place with a slight overshoot. The insertion handle does not sway; it stays with the cursor. Can be switched off or made smaller or larger, see below |
| No drift while dragging | While the view scrolls at an edge, the handle stays under the finger |
| Touch area | There is a margin around the knob, and the area reaches one line height further towards the text, so the stem can be taken hold of too; but a tap or long press on that extra part counts as a tap or long press on the text |
| Start handle on the first row | Without room above, its knob goes below the row |
| Insertion handle | Pops up where each tap lands; goes away after 4 seconds without activity, but not while held |
| Cursor blink | Shown for 500 ms, hidden for 500 ms; shown at once and restarted after every move or keystroke. While text is selected the cursor stays hidden, also after the app went to the background and came back, or the editor left the screen and returned |
| Touch-up filter | The wobble of a finger leaving the screen does not move the position one step too far |
| Magnifier | Looks at the row the handle is on, glides along it with the finger, and slides over when the row changes |

The handle color defaults to the theme's accent color and can be changed. Swaying on scroll is on by default:

```kotlin
editor.setHandleColor(color)
editor.setHandleSwayEnabled(false)                          // no swaying
editor.setHandleSwayAmplitude(8 * resources.displayMetrics.density)  // furthest sway, in px; 0 switches it off
editor.setAutoScrollSpeed(6f)                               // scrolling at the edges, lines per second; 0 switches it off
```

The amplitude scales the whole sway, not only its limit: half the amplitude, half the sway at any speed. The default is `SelectionController.DEFAULT_HANDLE_SWAY_AMPLITUDE_DP` (12dp).

### Handle looks

```kotlin
editor.useSystemHandles()                                   // the theme's handles, as in Android's text fields
editor.setHandleDrawables(R.drawable.start, R.drawable.end, R.drawable.middle)  // images of your own
editor.setHandleDrawables(null, null, null)                 // back to the handles the editor draws
```

The two looks differ in one point: **which side of the row a handle is on**. The start handle the editor draws stands above its row; handle images, by Android's convention, all three hang below it. The two image handles are kept apart by their direction instead: the start handle reaches out to the left of its tip, the end handle to the right, so they do not cover each other however short the selection is. Where the tip is across the image follows the convention too: three quarters of its width for the start handle, a quarter for the end handle, the middle for the insertion handle.

The rest of the editor no longer assumes a side; it asks the handle. The touch area, where the knob ends and the text begins, and whether to go below the row on the first row are all worked out from the knob area the handle describes. So touching, dragging, the animations and the sway work the same for both looks. `TextHandleDrawable` is the abstract base class, `DrawnTextHandle` and `ResourceTextHandle` are the two looks; a third look subclasses the base and says where its knob is and how it is drawn.

- Images keep their own colors; `setHandleColor` only affects the drawn handles. The theme's handles follow `colorControlActivated` by themselves.
- The images handed in are copied before use, so one resource can safely be handed to several places.
- An image start handle at the far left of the screen sticks out of it a little; it is not mirrored the way Android's are.

### Cursor looks

Like the handles, the cursor can be drawn by the editor or be an image:

```kotlin
editor.setCursorDrawable(null)                 // the bar the editor draws (default), in the handle color
editor.setCursorWidth(3 * density.toInt())     // width of the drawn bar, in px
editor.setCursorDrawable(R.drawable.my_cursor) // an image, stretched to a row's height, as wide as it says (2dp if not)
editor.useSystemCursor()                       // the cursor of Android's text fields (textCursorDrawable of editTextStyle)
```

## Gutter

By default the line numbers are drawn in the rows of text and scroll sideways with them. With the gutter on, they are drawn in a strip left of the text that stays put while the text scrolls sideways; text that scrolls under it is hidden, like text that scrolls past the right edge.

It is not a View of its own. A View beside the editor would have to follow its scrolling frame by frame, its text size when zooming, its rows when lines are wrapped, its padding and the keyboard covering it, and would always be a frame behind. Drawn by the editor in the same pass as the text, from the same layout, it cannot be out of step.

```kotlin
editor.setGutterEnabled(true)                  // on; off by default
editor.setLineNumbersVisible(false)            // hide the line numbers; shown by default
editor.setGutterTextGap(12f.dp)                // room between gutter and text, 8dp by default
editor.setGutterBackgroundColor(color)         // background; null takes the editor's
editor.setGutterDividerColor(color)            // the line between gutter and text; null is the line number color, faint
editor.setCurrentLineNumberColor(color)        // the number of the cursor's line; null is the text color

// markers per line (breakpoints, changed lines), drawn under the numbers
editor.setGutterDecoration { canvas, line, left, top, right, bottom ->
    if (line in breakpoints) canvas.drawCircle(left + 6f.dp, (top + bottom) / 2, 4f.dp, paint)
}

// taps on the gutter; returning false makes it a tap on the text of that row
editor.setOnGutterClickListener { line -> toggleBreakpoint(line); true }
```

- **The room for line numbers only grows**: while a text is edited across 999 / 1000 lines the text does not move back and forth; with word wrap it is laid out again in the background once per extra digit. Loading another text starts over.
- **Line numbers line up**: they are drawn on a grid of equal cells, each as wide as the widest digit of the font, one digit in the middle of each. In a proportional font numbers of as many digits take as much room (20 like 21), and ones and tens stand in columns. With the gutter on or off.
- **The gap**: there is room between the gutter and the text (`setGutterTextGap`, 8dp by default); the gutter's background stops before it, so that a handle at the start of a line has room to hang out.
- **Cursor and handles**: the cursor is drawn only where the text can be seen. A handle whose tip is on text that can be seen is drawn whole, also where it hangs out over the gutter; one whose tip has scrolled under the gutter is not drawn.
- With word wrap, a line's number is on its first row only.
- `getGutterWidth()` is where the text starts: the numbers with their margins, the column of fold markers, and with the gutter on the gap. Markers share that room with the numbers. With word wrap, a change of that width lays the text out again.
- With the gutter off, the `drawLineNumber` hook is used, and the numbers are in the rows.
- **Hidden line numbers** (`setLineNumbersVisible(false)`): the numbers and their room go, and the text moves left, up to a small margin, the column of fold markers (with folding on) and the gap (with the gutter on). A gutter that is on stays, for its decorations and fold markers. With the gutter off, `drawLineNumber` is no longer called.

## Accessibility

Screen readers see the editor as a multi-line text field: they can read the text on screen, follow the cursor, speak what is typed, and copy, cut, paste and move the cursor.

So as not to hand over a whole large file on every cursor move, a screen reader gets only the lines that are visible (at most 4000 characters). Moving through the text by word or line with screen reader gestures is not supported yet.

## Input

```kotlin
// the default: multi-line, no suggestions, no autocorrect; right for code
editor.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

// a symbol bar at the bottom that rides on the keyboard and lies over the editor
editor.setBottomOverlay(symbolBar)
```

Hardware keyboard keys:

| Keys | Action |
|---|---|
| Arrows, Home, End, Page Up/Down | Move the cursor |
| Ctrl + Left/Right | Move by words |
| Ctrl + Home/End | Start / end of the text |
| Shift + any of the above | Move and select |
| Ctrl + A / C / X / V | Select all, copy, cut, paste |
| Ctrl + Z, Ctrl + Y or Ctrl + Shift + Z | Undo, redo |
| Delete, Backspace, Enter, Tab, Esc | Edit, drop the selection |

Holding the delete key (on a hardware or a soft keyboard alike) deletes character by character at first, and after about 0.9 seconds word by word, 10 words a second by default; letting go starts over with characters. A key repeats about 20 times a second, and a word at every repeat would be too fast to let go in time, so in the word phase the repeats between two words do nothing. The speed can be changed:

```kotlin
editor.setFastDeleteSpeed(3f)    // words per second; 0 keeps deleting characters
```

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

## Tests

```bash
./gradlew :editor:testDebugUnitTest          # on the computer
./gradlew :editor:connectedDebugAndroidTest  # needs a device or an emulator
```

The build script needs `useJUnitPlatform()` and `junit-platform-launcher`; see `tests/README.md`.

| Scope | Test classes |
|---|---|
| Model | `EditorDocumentTest` `EditHistoryTest` `UndoManagerTest` `SearchResultsTest` |
| Layout | `UnwrappedLayoutTest` `WordwrapLayoutTest` `FoldedLayoutTest` `TabStopsTest` `LineBreaksTest` `LayoutCoordinatorTest` |
| Code structure | `StructureProvidersTest` `FoldingModelTest` |
| Input | `InputComposerTest` `InputMethodBridgeTest` `IndentEditsTest` `BackspaceRepeatTest` `CaretNavigatorTest` `TouchUpFilterTest` |
| Utilities | `RangeSearchTest` `TextBoundariesTest` |
| Whole widget (device) | `EditorViewTest` |

The two layout tests each run twelve thousand random edits and compare the incrementally updated result with a full relayout, line by line.

The device test draws the editor into a bitmap and checks that plain, wrapped and styled text was actually drawn. Beyond that, touch gestures, scrolling, padding conversion and keyboard overlap have no automated tests and are verified by hand; accessibility is tested only for what the screen reader is handed, never with a real screen reader.

## Remaining issues and their impact

Known issues that are not dealt with yet, with what they cause, for prioritizing later work. The "Where" column points at the code involved.

### Performance

| Issue | Impact | Where / how |
|---|---|---|
| An edit inserting fewer than 20 000 lines still measures them on the main thread | close to 20 000 lines, a pause of a hundred milliseconds or two; larger edits are laid out in the background (see "Undo and redo") | `LARGE_EDIT_LINES` in `EditorView`; can be lowered for slow devices |
| Edits are locked out during a relayout: text size, typeface, word wrap, tab size, an extra line number digit with word wrap, after a large undo | for seconds on a large file nothing can be typed, and text the input method commits is refused | **Accepted**: the app shows a progress bar meanwhile (`onBackgroundRelayoutStarted` / `Finished`). Running both needs merging an edit into the relayout's result |
| The piece tree as a `CharSequence` costs an O(log n) lookup per char | a `Regex` run on the buffer directly is very slow | `PieceTreeTextBuffer.get`; use its `find()`, or a snapshot from `createSnapshot()` |
| The piece tree is not compacted on its own | after long editing of a large file, pieces fragment and lookups slow down; deleted text still takes memory | the app calls `compact()`; compacting by itself first needs settling how it lives with the slice references of the undo history |
| Slice references in the undo history keep the storage they point at alive (also old chunks after `compact()`) | memory does not go down right after deleting a large block; the referenced storage has a limit (`maxReferencedChars`, 64 million chars, about 128 MB, by default), beyond which the oldest steps go, so it takes at most that much | `UndoManager`; the limit can be set to fit the device |
| An unwrapped row with tabs is drawn whole every frame (no horizontal clipping) | scrolling along a very long (tens of thousands of chars) single line with tabs can slow down | `TextRenderer.drawRowDirect`; clipping could accumulate tab stops from the row start |
| A multi-line search copies all the lines of its range | high memory use for multi-line searches in a large file | the piece tree's search |

### Features

| Issue | Impact | Where / how |
|---|---|---|
| No right-to-left text, no complex shaping | Arabic, Hebrew and some Indic scripts are shown, placed and selected wrongly | **Not planned for now.** Measuring and drawing go char by char in UTF-16, without `Layout` / BiDi |
| No multiple cursors | no typing in several places at once | `SelectionController` has one cursor and one selection |
| Accessibility is basic | a screen reader gets the visible text only, without word or line navigation | `EditorAccessibility` |
| Tab stops count from the start of each row | with word wrap, tabs on continuation rows do not line up with how they would unwrapped (each row counts anew) | `TabStops`; counting per line needs an origin for measuring |
| `PlainBracketMatcher` knows no strings or comments | brackets in strings and comments are matched too | an engine's `isBracket`; the app's `TreeSitterStructure` does that |
| Neither instance state nor an edit session contains the text | saving and reloading it is the app's job | by design |

## License

Apache License 2.0

# editor: Usage

> Part of the documentation of the [editor](../editor/README.md) module · [中文](editor_usage_cn.md)

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

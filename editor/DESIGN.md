# editor: design

English | [中文](DESIGN_CN.md) · [Guide](README.md)

For developers changing the editor or bringing it into their own project.

## Structure

`EditorView` is a thin shell: the public API, the extension points for subclasses, the View lifecycle. Everything else is by package:

| Package | Owns |
| --- | --- |
| `model` | The buffer interface `EditorBuffer`, the edit pipeline `EditorDocument`, the undo history `UndoManager`, search results, edit sessions |
| `layout` | Display rows and buffer lines (unwrapped, wrapped, folded), font metrics, tab stops, and the threads and locking of full relayouts (`LayoutCoordinator`) |
| `render` | The visible-row loop and recorded rows (`TextRenderer`, `RowCache`), line backgrounds and selection, the gutter, the look of folds |
| `input` | Touch, scrolling, selection and handles, hardware keys, input methods, clipboard, automatic pairs, indentation, accessibility |
| `structure` | The interfaces of bracket matching, indent guides and folding, with simple implementations by indentation |
| `view` | `EditorView` itself, and asking and caching code structure (`CodeStructure`), the save point (`SaveTracker`), the floating bar |

Dependencies go one way: `view` depends on the others, none of them on `view`, and `model` on no UI class at all. Collaborators that need the View each declare a narrow `Host` interface, implemented by a private adapter inside `EditorView`, so internal callbacks never show up in the public API.

There are two coordinate systems: touch events use View coordinates (padding included), everything else content coordinates (origin at the top left of the text); `content = view + scroll − padding`.

## The text buffer

The editor works only through `EditorBuffer` (lines and columns from 1, offsets from 0, ranges end-exclusive, every range of an `applyEdits` against the text before it). The built-in `PieceTreeBuffer` wraps a piece tree; to plug in a rope, a gap buffer or anything else, implement the interface.

Every edit goes through `applySliceEdits`: content comes in as `TextSlice`, and each change reports what it removed, which is what the undo history keeps. On a piece tree, content of 16K characters or more is kept as a reference to the storage, so selecting and deleting tens of MB puts a few references on the undo stack, and undoing puts them back without a copy. Other buffers fall back to a string-based default: correct, but large content is copied. References are valid only in the piece tree they came from; loading another text clears the undo history.

## Layout and relayout

| | Unwrapped | Wrapped |
| --- | --- | --- |
| One display row | One buffer line | A piece of a buffer line |
| Storage | One `Int` width per line | One object per display row |
| Text size change | Scaled, at once | Needs a relayout |
| One edit | Only changed lines measured | Only changed lines rewrapped |

- With folds, `FoldedLayout` wraps one of the two and takes the folded lines out of the row sequence. Drawing, selection, line numbers, scrolling and arrow keys all work by rows, so they skip folded lines by themselves.
- A full relayout is built in new data and swapped in whole; a cancelled one leaves no trace.
- **Edits and relayouts go through one lock**: while a relayout reads the text no edit gets in, and a relayout on another thread waits for an edit to finish. Every entry that changes text goes through this gate; that is where "editing is refused during a layout" comes from, not from callers behaving.
- An edit inserting 20,000 lines or more (undoing "delete half the file") first gives the new lines a rough layout and relays out properly in the background afterwards; smaller edits are measured directly on the main thread.

## Drawing

- Each visible line is recorded once (`RowCache`) and replayed every frame. After an edit only the edited lines are recorded again; when lines are added or removed, the recordings below move as a whole.
- A line already colored is never replaced with plain text, which is why background highlighting does not flicker.
- The cursor, handles, bold brackets and indent guides are drawn outside the recordings, so moving the cursor records nothing.
- Lines over 4096 characters are drawn directly, not recorded.

## Undo and the save point

- Typing within 500 ms, in changes of up to 16K characters, merges into one step; larger changes are steps of their own at once.
- `isModified` follows the undo history, not a count: undoing back to the save point is unmodified. Undoing past it and typing something new, losing the save point to the step limit, or a non-undoable edit leaves the text modified until the next save.
- `getTextVersion` notes the position in the history and ends the typing being merged (a save point has to fall between steps), so `markSaved(version)` finds the text that was written even if the user typed on meanwhile.

## Edit sessions

Undo steps are edits at fixed positions, which would wreck another text, so `restoreSession` checks before restoring:

| Check | From | Catches |
| --- | --- | --- |
| `contentHash` | The app | The file changed elsewhere |
| `textLength`, `lineCount` | The editor, when saving | The same bytes read as another text (encoding, line endings) |
| Edited since loading | The editor's modified state | The text shown is no longer the session's |

A session always belongs to **the text on disk**: unsaved changes after the save point go onto the redo stack and come back with redo next time. Only the history nearest the save point is kept (the redo side first), as strings, since references mean nothing outside their piece tree.

## Extension points

Most customization needs no subclass. When it does, these `protected open` methods of `EditorView` can be overridden:

- **Drawing**: `drawText` (once per line when recording, every frame when drawing directly: assume no frequency and keep no state across lines), `drawLineNumber` (only with the gutter off), `drawLineBackground`, `drawSelectionBackground`, `drawMatchesBackground`, `drawComposingUnderline`, `drawBracketHighlight` / `drawBracketText` / `drawBracketUnderline`, `drawIndentGuide`, `drawFoldMarker`, `drawFoldPlaceholder`.
- **Text changes** (call `super`): `beforeTextChanged` (the buffer still has the old text), `onTextChanged` (once per change, for updating a syntax tree), `afterTextChanged` (the whole edit done, for reparsing). Content comes as `TextSlice`; take its text only when needed, so a large undo does not copy it into the callback.
- **Others**: `onBackgroundRelayoutStarted` / `Finished`, `onCopyResult`, `onMergeStarted` / `onMergeCommitted` (for an undo button), `onModifiedChanged`, `onFoldMarkerClick`, `onFoldChanged`, `onZoomBegin` / `onZoom` / `onZoomEnd`.

**Handles**: `TextHandleDrawable` is the abstract base, `DrawnTextHandle` (drawn; the start handle above the line) and `ResourceTextHandle` (images; all below the line, as Android does) its two looks. Touch areas, flipping on the first line and the rest are worked out from the "knob area" each handle describes, so both behave the same; a third look only needs a subclass.

**The gutter** is not a View of its own: a View beside the editor would have to follow scrolling, zoom, wrapping and keyboard insets frame by frame and would always be a frame late; drawn by the editor in the same pass from the same layout, it cannot drift. Its width only grows, so crossing a digit boundary does not make it jump back and forth.

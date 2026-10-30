# editor

English | [中文](README_CN.md)

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

## Documentation

The details are in the documents of this module:

- [Usage](../docs/editor_usage.md): Quick start, Syntax highlighting, Code structure: matching brackets, indent guides, folding
- [Customization and extension](../docs/editor_customization.md): Subclassing and extending, Cursor and handles, Gutter, Accessibility, Input
- [Design and internals](../docs/editor_architecture.md): Threading, Text buffers, Architecture, Limits and defaults
- [Testing](../docs/editor_testing.md): Tests

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

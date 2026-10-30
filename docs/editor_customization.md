# editor: Customization and extension

> Part of the documentation of the [editor](../editor/README.md) module · [中文](editor_customization_cn.md)

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

# How it stays fast

> Part of the [X Editor](../README.md) documentation · [中文](performance_cn.md)

The main thread never runs a tree-sitter query. Most of this is in `TreeSitterEngine` and
`CaptureStore` (`app/.../highlight`) and in `RowCache` (`editor/.../render`).

- **Highlighting in the background.**
  - The text is highlighted in blocks of 2,048 characters on a worker thread, using a copy
    of the syntax tree and a snapshot of the text.
  - The main thread only turns blocks that are ready into spans. A row whose block is
    still being worked out keeps what it showed before; rows that newly scroll into view
    may be painted plainly for a frame.
- **Edits don't wait.**
  - Blocks move along with an edit. A letter typed at the end of a name takes the name's
    color immediately.
  - Only the blocks that the next parse reports as changed are queried again.
- **No flicker.** Every recorded row knows what it shows (`RowCache`), so a row that shows
  styled text is never replaced by plain text. A newline or a joined line moves the
  recordings of the rows below instead of recording them again.
- **Parses stay off the main thread.**
  - The parse runs on its own thread. Comparing the old and new trees and freeing the old
    tree happen there too.
  - Indent guides and fold markers are worked out on the worker thread. Until they're
    ready, the editor draws the ones it had, shifted along with the edits.
- **Predicates are decided natively.**
  - `eq?`, `any-of?`, `lua-match?` (a port of LuaJIT's pattern matcher, checked against
    LuaJIT on more than 120,000 cases), `contains?`, `has-ancestor?`, `has-parent?` and
    `kind-eq?` are evaluated in C++ before a match reaches Kotlin.
  - Only Vim regexes (`match?`) are left to Kotlin.
- **Comments are skipped when they can't produce anything.** A comment is parsed with the
  comment grammar only if it could produce a highlight: a TODO or NOTE keyword, an issue
  number, a URL. These conditions are derived from the installed query files. In
  `sqlite3.c`, 92% of the comments are skipped.

On the development device, scrolling and editing `sqlite3.c` went from 31% janky frames
(90th percentile 89 ms) to 4% (90th percentile 18 ms).

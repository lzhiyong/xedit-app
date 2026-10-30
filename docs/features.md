# Features

> Part of the [X Editor](../README.md) documentation · [中文](features_cn.md)

**Editing**

- Undo and redo. The editing session (cursor and history) is restored when a file is
  reopened, if the file has not changed since.
- Word wrap, line numbers with an optional fixed gutter, indent guides, a background for
  code blocks, and a preview of color literals.
- Code folding, from tree-sitter's `folds` queries or from indentation.
- Bracket matching, and automatic pairs (the pairs are configurable).
- Search and replace, with regular expressions.
- **Language servers** (LSP) that you set up (clangd, pyright, lua-language-server, ...):
  completion, diagnostics (wavy underlines), hover, signature help, go to definition, find
  references, rename, code actions, formatting and an outline. Without a server, words of
  the text are completed, fuzzily, as blink.cmp does. See
  [Code completion](config.md#language-servers-serversjson).
- **Go to line**: type a `line` or `line:column`. A preview card shows the target line, a
  slider spans the whole file, and chips jump to the first, current or last line. After
  the jump, the line flashes briefly so you can see where you landed.
- **Floating selection bar**:
  - cut, copy, paste, select all and search in the bar;
  - share and every app that handles `PROCESS_TEXT` (translators, readers, assistants) in
    the overflow menu;
  - the overflow menu caps its height like the system's toolbar (4½ rows) and scrolls the
    rest.
- **Console**, as VS Code's panel: the output of each channel (X Editor, the language
  servers, each server's own log, builds), an errors tab with the diagnostics of the open
  documents and the errors of the last build (a tap goes there), and a build tab that runs a command in Termux
  (`clang rbtree.c -o $PREFIX/tmp/rbtree && $PREFIX/tmp/rbtree`) and finds gcc, clang and Python errors in what
  it prints. Only a drag on its header (the title and the tabs) pulls
  it down and closes it: scrolling the pages never does.
- Pinch to zoom, a magnifier while dragging the selection handles, and a row of extra
  keys above the keyboard.
- Read-only and edit modes. Several files open in tabs, with a file tree in the drawer.
- **Changes by other apps**: when the file of the document shown is changed by another app (a
  build, git, another editor), you choose to reload it or to keep editing your text, which then
  saves over the change; when it is deleted, to keep the text (saving creates the file again) or
  to close it. See [Configuration](config.md) for where the files are.

**Look**

- Light, dark and pure-black themes, and the system's dynamic colors.
- Highlighting themes (astrodark and astrolight are shipped), the editor font (JetBrains
  Mono and Source Code Pro are shipped, or import your own), the font size and the tab
  width.

**Robustness**

- Java crashes, native crashes and ANRs are caught, written to disk first, and shown in a
  separate `:crash` process. From there they can be copied, exported or reported.

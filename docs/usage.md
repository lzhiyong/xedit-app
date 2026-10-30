# Using the editor

> Part of the [X Editor](../README.md) documentation · [中文](usage_cn.md)

## Code completion

Completion asks the server right after one of its trigger characters (`.`, `->`, `::`), and
after a short pause while a word is typed. The list is filtered on the device as you keep
typing. Taking an item:

- replaces the word;
- expands snippets, with the cursor at the first tab stop and its placeholder selected;
- for a function the server sends only the name of (pyright, lua-language-server,
  typescript-language-server by default), puts in its call with a placeholder for each
  required parameter of its signature, `foo(a, b)`, or `foo()` with the cursor between the
  brackets if its parameters are not known; not on an import line, nor before a `(`;
- applies extra edits such as an `#include`;
- goes over a closing char that is already after it instead of adding a second one
  (`#include <st|>` with clangd's `stdlib.h>` gives `<stdlib.h>`, not `<stdlib.h>>`), and
  likewise takes in an opening char before it that the item brings itself; a `)` that
  still closes a bracket opened before, as in `bar(foo()|)`, stays.

All of this is one undo step. With a hardware keyboard, the arrows move through the list,
Enter or Tab takes an item, and Esc closes it.

As in nvim, Tab then goes from one placeholder to the next and on past the `)` (Shift+Tab,
or holding the Tab of the extra keys, goes back). An item taken while filling in a
placeholder keeps the snippet going; if it is a snippet itself, Tab goes through its
placeholders first, then on through those of the outer one.

Typing a path lists the files there (as cmp-path): `./` or `../`, a path in quotes
(`#include "dir/`, a file name in a string), or an absolute path such as `/sdcard/Do`; and
the files next to the document right after `#include "` or `src="`. Relative paths start
from the document's directory; a folder taken lists what is in it. Without all files
access, the app sees only the folders and media files of the shared storage by their
path, so a directory is listed through the folder of the file tree it is in (opened with
**Add folder**); a document opened alone (**Open file**) has no folder to list through, and
the editor says so once.

The outline, the code actions and the references open in a sheet: a badge in the color of
each kind, the line on the right, the symbol (or the place) the cursor is in marked and
scrolled to, the tree of the outline drawn with guides, code actions grouped (quick fixes,
refactorings, source actions) with the server's preferred one first and tagged, and a
filter field for a long list.

## Comments

**Comment**, in the ⋮ menu of the floating bar, comments the selection out, or the line of
the cursor; on a comment it is **Uncomment** and takes it back in.
Whole lines get line comments (`//`, `#`, `--`) at the indentation of the least indented
line, blank lines left alone; a part of a line gets a block comment (`/* … */`), as does
any region in a language without line comments (CSS, HTML). It is one step to undo, and
the selection stays over what was toggled.

# Configuration

> Part of the [X Editor](../README.md) documentation · [中文](config_cn.md)

What the app reads and writes, and the files you configure it with. Everything you edit
yourself is in `Android/data/x.editor.app/files/` on the shared storage: `lsp/servers.json`,
`lsp/formatting.json`, `lsp/packages.json` (see [Toolchain](toolchain.md)) and `snippets/`.

## Highlighting resources

Grammars, queries, themes and snippets are not part of the APK. On first start, the app
downloads four packages from the releases of
[lzhiyong/tree-sitter-parser](https://github.com/lzhiyong/tree-sitter-parser/releases), as
listed in [`app/src/main/assets/treesitter.json`](../app/src/main/assets/treesitter.json):

| Package | Contents |
| --- | --- |
| `tree-sitter-configs.zip` | the file-type map (`config/filetypes.json`), the highlighting themes (`themes/`) |
| `tree-sitter-languages-{arch}.zip` | the grammar libraries for the device's architecture |
| `tree-sitter-queries.zip` | the queries: `highlights`, `injections`, `folds`, `indents`, `locals` |
| `tree-sitter-snippets.zip` | [friendly-snippets](https://github.com/rafamadriz/friendly-snippets), for the completion |

They go into the app's home, `/data/data/x.editor.app/files/home`: the first three into
`treesitter/`, the snippets into `lsp/friendly-snippets/`. The sessions of the documents
(`sessions/`, JSON) and the crash logs (`logs/`, `native_crash/`) are kept there too.

Downloads resume where they stopped, and a package is downloaded again only when its ETag
changes. **Settings → Highlighting resources** shows their state.

## Language servers (`servers.json`)

The app ships no language server, because they would make the APK far too large. You set
them up in `servers.json`, in `Android/data/x.editor.app/files/lsp/` (with `lsp.log`, the log of the servers). Your
own snippets are next to it, in `snippets/` (see [Your own snippets](#your-own-snippets)).
The app keeps nothing else on the shared storage.

The app writes an example there the first time, with the kinds below switched off
(`"enabled": false`). Each server names the file extensions it is for, and how to reach it.

**Yours, else Termux's** (`"type": "auto"`). The command of the server, wherever it is
installed: if the app's own toolchain has it (in its usr/bin, see [Toolchain](toolchain.md)),
the app starts it itself; if not, Termux is asked to start it behind a bridge on `port`
(`socat TCP-LISTEN:<port> EXEC:<command>`; without a port, one of 5100 to 5899 is chosen
from the id). The example's first server is one.

```json
{ "id": "clangd", "extensions": ["c", "cpp", "h"],
  "connection": { "type": "auto", "command": ["clangd", "--background-index"], "port": 5009 } }
```

**In Termux, over a socket** (`"type": "socket"`): the easy way for the servers that Termux
packages. The configurations of clangd and pyright, and what to do when a server does not
connect, are in [Example: language servers in Termux](example.md).

**As a process the app starts** (`"type": "process"`):

```json
{
  "id": "lua",
  "extensions": ["lua"],
  "connection": { "type": "process", "command": ["lua-language-server"], "env": {} }
}
```

- `command` is the absolute path of the binary, or a path in the app's home
  (`/data/data/x.editor.app/files/home`); the app does not install servers itself (yet).
- A binary in the app's own storage is started through the system's dynamic linker
  (`/system/bin/linker64`): Android 10 and later do not let an app execute such files
  directly. Its libraries are found through `LD_LIBRARY_PATH` in `env`.
- So the binary has to be built for Android (bionic) and dynamically linked. Termux's own
  packages need Termux's libraries: run those with the socket kind.

Optional keys:

- `languageIds`: the LSP language id per extension, e.g. `{ "h": "cpp" }`.
- `root`: the project root. Otherwise it is the nearest directory above the file with a
  marker such as `.git` or `compile_commands.json`.
- `rootMarkers`: the markers to look for.
- `initializationOptions`: passed to the server as they are.
- `initializeTimeout`: how many seconds the server may take to start (60 by default). A
  Node.js server such as pyright can take long the first time on a phone.

## Formatting rules

**Format document** and **Outline** are in the floating bar over the selection or the
cursor (its ⋮ menu), when the server can do them; with text selected, and a server that
can format a part, it is **Format selection** instead.

The rules a document is formatted with are generated from the editor: the indentation
(spaces or tabs, and how wide), tabs for Go and Makefiles, trailing white space taken off,
one newline at the end. **Menu → Developer tools → Formatting rules** opens `formatting.json`, next to
`servers.json`, with a section for the language of the document shown and for each
language of the servers that are on, filled with those generated rules; change them there:

```json
{
  "cpp": {
    "tabSize": 4,
    "insertSpaces": true,
    "trimTrailingWhitespace": true,
    "insertFinalNewline": true,
    "trimFinalNewlines": true
  },
  "lua": { "tabSize": 2, "quote_style": "single" }
}
```

A rule left out, or a language without a section, is generated; `null` for one of the
whitespace rules leaves it to the server; a section taken out is generated again the next
time the file is opened. Other keys (`quote_style`) go to the server as they are. Saved,
the file is read again at once.

A project's own style files go over these rules:

- **`.editorconfig`** is read by the editor, from the document's directory up to one with
  `root = true` (the nearer file and the later section win, as the specification says).
  `indent_style`, `indent_size` and `tab_width` are what the editor indents with while
  typing in that document; with `trim_trailing_whitespace` and `insert_final_newline`
  they are sent to the server when formatting, over `formatting.json`.
- **`.clang-format`** (or `_clang-format`) is clangd's: clangd finds it from the document's
  path up and formats by it, whatever options it is sent. The editor reads its
  `IndentWidth`, `TabWidth`, `UseTab` and `BasedOnStyle` too, so that typing indents as
  formatting will (for C, C++, Objective-C, Java, JavaScript, Proto, C#).
- Other formatters' files (`rustfmt.toml`, `pyproject.toml` for ruff or black,
  `.prettierrc`, `stylua.toml`) are read by their servers.

A server reads its style file by the document's path, so it needs to be able to read the
project's directory: for a server in Termux, run `termux-setup-storage` once for
`/sdcard`. The editor reads `.editorconfig` and `.clang-format` by their path, or through the
folder you opened in the file tree. `lsp.log` says which files a formatting went by.

## Your own snippets

friendly-snippets is replaced whole when it is updated, so it is not the place for your
snippets. They go in `Android/data/x.editor.app/files/snippets/`, one file per language,
in the format of VS Code:

| File | For |
| --- | --- |
| `c.json`, `python.json`, ... | a language, by friendly-snippets' name for it |
| `<extension>.json` | files of a kind friendly-snippets has no language for, e.g. `just.json` for `x.just` |
| `all.json` | every file |

**Menu → Developer tools → My snippets** opens the file for the language of the document shown (written
from a template with an example the first time). Saving it reads it again and says how
many snippets it has, or the line of the first error. Comments and a trailing comma are
allowed.

Your snippets come first in the list, and one with the same prefix as a snippet of
friendly-snippets takes its place. They also work when friendly-snippets is not
downloaded.

Servers can also be registered in code with `LanguageServers.register(spec)`
(`app/.../lsp`). Documents have to be on storage the server can read by path: files opened
from the shared storage are, while documents from other providers (cloud drives) are not
sent to a server.

## Keyboard shortcuts (`keymap.json`)

**Menu → Developer tools → Keyboard shortcuts** lists the shortcuts as they are, by
group, with the action name to use in the file and anything the file had that was left
out. **Edit** there opens `keymap.json` (next to the `lsp` folder); the first time it is
written from a template that lists every action and the default keys. It is read again when it is saved, with a message saying how many mappings
were read, or which one was left out and why.

Keys are written as in Neovim: `<C-s>`, `<C-S-f>`, `<A-z>` (or `<M-z>`), `<D-k>` (Meta),
`<F5>`, `<C-/>`, `<C-Space>`, and sequences such as `<C-k>c` or `<leader>f`. The first key
must have Ctrl, Alt or Meta, or be F1–F12; the keys after it may be plain.

```jsonc
{
  "leader": "<C-k>",
  "timeoutlen": 1000,
  "keymaps": [
    { "keys": "<leader>f", "action": "format", "desc": "Format the document" },
    { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**" },
    { "keys": "<C-y>", "action": "nop" }
  ]
}
```

A mapping replaces a default of the same keys; `"nop"` takes a default away. `"insert"`
puts text in at the cursor with the syntax of snippets.

The same keys work from a hardware keyboard, from a soft keyboard that has Ctrl and Alt
keys of its own, and from **Ctrl** and **Alt** of the row of extra keys: a tap holds one for
the next key (a char of the soft keyboard or a symbol of the row), a long press until it is
tapped again.

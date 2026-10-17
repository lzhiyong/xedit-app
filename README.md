# X Editor

A code editor for Android. Built to stay smooth on large source files: an 8.8 MB,
239,000-line `sqlite3.c` scrolls, folds and edits without dropping frames.

- **Text** lives in a piece tree, the data structure of VS Code's text buffer.
- **Highlighting** comes from tree-sitter with the queries of nvim-treesitter, worked out
  off the main thread.
- **Files** are opened and saved through the Storage Access Framework.

中文说明见 [README_cn.md](README_cn.md).

## Features

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
  [Code completion](#code-completion-language-servers).
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

**Look**

- Light, dark and pure-black themes, and the system's dynamic colors.
- Highlighting themes (astrodark and astrolight are shipped), the editor font (JetBrains
  Mono and Source Code Pro are shipped, or import your own), the font size and the tab
  width.

**Robustness**

- Java crashes, native crashes and ANRs are caught, written to disk first, and shown in a
  separate `:crash` process. From there they can be copied, exported or reported.

## How it stays fast

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

## Modules

| Module | What it is | Used by the app |
| --- | --- | --- |
| [`app`](app) | the application: screens, settings, highlighting with tree-sitter, downloads of the highlighting resources | — |
| [`editor`](editor) | `EditorView`: layout (wrapped or not, folded), rendering, input and IME, selection, the floating bar, folding, indent guides, undo | yes |
| [`piecetable`](piecetable/README.md) | the piece tree text buffer, ported from VS Code | yes |
| [`treesitter`](treesitter) | Kotlin bindings of tree-sitter over JNI, with native predicate evaluation | yes |
| [`document`](document/README.md) | a faster rewrite of AndroidX `DocumentFile` for SAF and plain files | yes |
| [`treeview`](treeview/README.md) | a tree view on a single `RecyclerView` (the file tree) | yes |
| [`crash`](crash/README.md) | crash capture: Java, native, ANR | yes |
| [`lsp`](lsp/README.md) | a Language Server Protocol client on lsp4j: completion, diagnostics, hover, signatures, definitions, references, rename, code actions, formatting, symbols | yes |
| [`bypass`](bypass) | access to hidden Android APIs, through a `ContentProvider` and JNI | not yet |

## Building

Requirements:

- JDK 21
- Android SDK: platform 36, build tools 37.0.0
- NDK 29.0.14206865 and CMake 4.1.2 or later, for the `treesitter` and `crash` native code
- Python 3 and git, to fetch the tree-sitter sources
- Gradle 9 or later (the Android Gradle plugin is 9.2.0, Kotlin is 2.3.21)

The versions are in [`libs.versions.toml`](libs.versions.toml).

Steps:

```sh
# 1. the tree-sitter sources, cloned into treesitter/src/main/cpp/tree-sitter
cd treesitter && python3 get_sources.py && cd ..

# 2. a Gradle wrapper (none is checked in), or build from Android Studio
gradle wrapper

# 3. the app
./gradlew :app:assembleDebug
```

Native code is built for `arm64-v8a` only (`abiFilters` in `treesitter/build.gradle.kts`).
Add ABIs there for other devices or emulators.

### Highlighting resources

Grammars, queries and themes are not part of the APK. On first start, the app downloads
three packages from the releases of
[lzhiyong/tree-sitter-parser](https://github.com/lzhiyong/tree-sitter-parser/releases), as
listed in [`app/src/main/assets/treesitter.json`](app/src/main/assets/treesitter.json):

| Package | Contents |
| --- | --- |
| `tree-sitter-configs.zip` | the file-type map (`config/filetypes.json`), the highlighting themes (`themes/`) |
| `tree-sitter-languages-{arch}.zip` | the grammar libraries for the device's architecture |
| `tree-sitter-queries.zip` | the queries: `highlights`, `injections`, `folds`, `indents`, `locals` |

Downloads resume where they stopped, and a package is downloaded again only when its ETag
changes. **Settings → Highlighting resources** shows their state.

## Code completion (language servers)

The app ships no language server, because they would make the APK far too large. You set
them up in `servers.json`:

- in `/storage/emulated/0/xedit/lsp/` when the app may manage all files;
- otherwise in `Android/data/x.editor.app/files/lsp/`.

The app writes an example there the first time, with both kinds below switched off
(`"enabled": false`). Each server names the file extensions it is for, and how to reach it.

**In Termux, over a socket** (`"type": "socket"`). This is the easy way for servers that
Termux packages.

```sh
pkg install clang socat          # clangd, and a bridge from TCP to its stdio
termux-setup-storage             # so that Termux sees the files you edit
echo "allow-external-apps = true" >> ~/.termux/termux.properties
```

```json
{
  "id": "clangd",
  "extensions": ["c", "h", "cc", "cpp", "hpp"],
  "connection": {
    "type": "socket", "host": "127.0.0.1", "port": 5007,
    "termux": {
      "command": "socat",
      "arguments": ["TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork", "EXEC:clangd --background-index"]
    }
  }
}
```

When nothing listens on the port, the app asks Termux to run the `termux` command, through
Termux's `RUN_COMMAND` service, then connects to it. Allow X Editor to *run commands in
Termux* when asked. Without a `termux` entry, start the bridge yourself.

pyright, for Python, the same way, on another port:

```sh
pkg install nodejs && npm install -g pyright
```

```json
{
  "id": "pyright",
  "extensions": ["py", "pyi"],
  "initializeTimeout": 120,
  "connection": {
    "type": "socket", "host": "127.0.0.1", "port": 5008,
    "termux": {
      "command": "socat",
      "arguments": [
        "-d", "-d", "-lf", "/data/data/com.termux/files/home/socat-pyright.log",
        "TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork",
        "EXEC:/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/lib/node_modules/pyright/langserver.index.js --stdio"
      ]
    }
  }
}
```

Three things differ from clangd:

- **pyright is started with node and the absolute path of its entry file, not with
  `EXEC:pyright-langserver --stdio`.** `pyright-langserver` is a script that starts with
  `#!/usr/bin/env node`. In Termux's terminal that shebang is rewritten to Termux's own
  path, so it works in the foreground; when the app has Termux start socat in the
  background (`RUN_COMMAND`), the rewriting may not happen, and pyright fails as soon as it
  starts. The connection succeeds, but `initialize` is never answered, until the timeout.
  clangd is a native binary and is not affected.
- **`-lf` writes socat's log to `~/socat-pyright.log`.** Nothing is seen of a server run in
  the background: look there when something goes wrong.
- **`initializeTimeout` is 120 seconds.** pyright loads a lot of JavaScript the first time,
  which can be slow on a phone.

Check that the entry file is there before you use the configuration:

```sh
ls $PREFIX/lib/node_modules/pyright/langserver.index.js
```

Other Node.js servers installed with npm (`typescript-language-server`,
`bash-language-server`, ...) are scripts too: start them the same way, with `node` and the
absolute path of their entry file.

### When a server does not connect

1. **Stop the socat left on the port.** The app has Termux start socat with your
   configuration only when nothing listens on the port; while an old socat is there, the app
   connects to it, and a changed configuration has no effect. If the log has no line
   "asking Termux to run socat", the app connected to one that ran already.

   ```sh
   pgrep -af socat                    # the socats running, with their arguments
   pkill -f 'TCP-LISTEN:5008'         # only the one on 5008, not the other servers
   ```

2. **Run socat in the foreground, and watch what it prints.** Leave it running in Termux,
   go back to X Editor and open the file. The app connects to this socat, and the server's
   errors and the exit status of its process show in Termux:

   ```sh
   socat -d -d TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork 'EXEC:pyright-langserver --stdio'
   ```

   If it works in the foreground but not in the background, it is the shebang above: start
   the server with node and an absolute path.

3. **Read the log of the background socat**: `cat ~/socat-pyright.log`, and look for lines
   such as `child ... exited with status ...`.

4. **Keep Android from stopping Termux in the background**: let Termux run without battery
   restrictions in the system settings; if a server goes away after a while, run
   `termux-wake-lock` in Termux once.

`pyright-langserver --version` fails with "Connection input stream is not set": that is
expected, it has no `--version` and only says that it needs `--stdio` or another way to
talk. `pyright --version` shows the version.

**As a process the app starts** (`"type": "process"`):

```json
{
  "id": "lua",
  "extensions": ["lua"],
  "connection": { "type": "process", "command": ["lua-language-server"], "env": {} }
}
```

- Download the server yourself, and put its binary in `xedit/lsp/bin/` and the libraries it
  needs in `xedit/lsp/lib/`.
- The app copies them into its own storage when they change, and starts them through the
  system's dynamic linker (`/system/bin/linker64`). Android 10 and later do not let an app
  execute files from storage directly.
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

Completion asks the server right after one of its trigger characters (`.`, `->`, `::`), and
after a short pause while a word is typed. The list is filtered on the device as you keep
typing. Taking an item:

- replaces the word;
- expands snippets, with the cursor at the first tab stop and its placeholder selected;
- applies extra edits such as an `#include`.

All of this is one undo step. With a hardware keyboard, the arrows move through the list,
Enter or Tab takes an item, and Esc closes it.

Servers can also be registered in code with `LanguageServers.register(spec)`
(`app/.../lsp`). Documents have to be on storage the server can read by path: files opened
from the shared storage are, while documents from other providers (cloud drives) are not
sent to a server.

## Tests

| Module | Test classes | Covers |
| --- | --- | --- |
| `editor` | 24 | edit history and undo, the document, search, folding, the structure providers, text boundaries, row recordings (`RowCache`, including a randomized no-flicker property), layouts (unwrapped, word wrap, folded, line breaks, tab stops), caret navigation, input and IME, auto pairs, indentation edits, the floating bar's overflow height (`FloatingBarTest`), structure shifting |
| `lsp` | 5 | conversions, snippets, completion ranking, the client |
| `document` | 3 | document files and queries |
| `app` | 7 | when typing asks for completion, words of the text, problems in build output, run commands, hover Markdown, go to line, `servers.json` |
| `piecetable` | 16 | the VS Code suite and more, on the JVM and on a device (`src/sharedTest`) |
| `treesitter` | 1 | what the native side hands back, points, ranges, query errors, predicates without captures |
| `treeview` | 1 | the tree: children, parents, depths, ancestors, descendants |
| `crash` | 2 | crash signatures, crash files and their counts |
| `bypass` | 1 | reflection on the JVM; the bypass needs a device |

```sh
./gradlew test                                  # all JVM unit tests
./gradlew :editor:testDebugUnitTest             # one module
./gradlew connectedDebugAndroidTest             # instrumented tests, needs a device
```

## Project layout

```
xedit-app/
├── app/                        the application (x.editor.app)
│   └── src/main/java/x/editor/app/
│       ├── activity/           EditorActivity, SettingsActivity, CrashActivity
│       ├── controller/         file list, search panel, go to line
│       ├── highlight/          TreeSitterEngine, CaptureStore, StructureQuery,
│       │                       TreeSitterInjections, InjectionPrefilter, QueryPredicates
│       ├── service/            EditorService: reading and writing documents, sessions, downloads
│       ├── view/               CodeEditorView (EditorView with tree-sitter), drawers, insets
│       └── ...                 settings, downloads, database, search, sessions, utilities
├── editor/                     the editor view (x.github.module.editor)
│   └── src/main/java/.../editor/
│       ├── view/               EditorView, FloatingBar, CodeStructure
│       ├── render/             TextRenderer, RowCache, FrameBudget, Gutter
│       ├── layout/             unwrapped, word wrap and folded layouts
│       ├── input/              gestures, selection, IME, auto pairs
│       ├── structure/          folding, indent guides, brackets
│       └── model/              the document, edit history, search results
├── piecetable/                 the text buffer
├── treesitter/                 tree-sitter bindings (Kotlin + C++), get_sources.py
├── document/  treeview/  crash/  lsp/  bypass/
├── libs.versions.toml          versions of the plugins and libraries
└── settings.gradle.kts
```

## Documents and sessions

`EditorActivity` decides which document is open:

- One document is loaded at a time. Choosing another cancels the load in progress, and the
  new load starts only after the old one has stopped.
- A document is written back only if it was loaded completely, so the text of one file can
  never be written into another.
- Saves run one after another, and opening another document does not cancel them. A load
  waits for the pending save before it replaces the text in the editor.
- If writing the previous document back fails, it stays open, with its changes, and the
  error is shown.

The editing session of a document is stored with the SHA-256 of the file content it
belongs to. The text is always read from the file; the session is restored only if the
hash still matches.

## Third-party code

- [tree-sitter](https://github.com/tree-sitter/tree-sitter) (MIT), cloned by
  `get_sources.py`.
- [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter) (MIT), where the
  `treesitter` module comes from: its Kotlin code reworked, its JNI layer rewritten from C
  to C++; see [its license](treesitter/LICENSE-kotlin-tree-sitter).
- The piece tree of [VS Code](https://github.com/microsoft/vscode) (MIT), ported in
  `piecetable`; see [its license](piecetable/LICENSE-vscode).
- [nvim-treesitter](https://github.com/nvim-treesitter/nvim-treesitter) queries, in the
  downloaded resources.
- [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) (EPL-2.0 or EDL-1.0), a library of `lsp`;
  see [its license](lsp/LICENSE-lsp4j).

See each project for its license.

## License

Apache License 2.0, see [LICENSE](LICENSE).

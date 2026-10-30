# X Editor

A code editor for Android. Built to stay smooth on large source files: an 8.8 MB,
239,000-line `sqlite3.c` scrolls, folds and edits without dropping frames.

中文说明见 [README_CN.md](README_CN.md).

## Features

- **Fast on large files**: the text lives in a piece tree (the data structure of VS Code's text
  buffer), and tree-sitter highlighting with the queries of nvim-treesitter is worked out off the
  main thread.
- **Editing**: undo and redo kept across sessions, word wrap, code folding, indent guides,
  bracket matching and automatic pairs, search and replace with regular expressions, go to line,
  commenting, a floating selection bar, pinch to zoom and extra keys above the keyboard.
- **Language servers** you set up (clangd, pyright, lua-language-server, ...): completion,
  diagnostics, hover, signatures, definitions, references, rename, code actions, formatting and an
  outline; snippets from friendly-snippets and your own.
- **Files**: several tabs, a file tree, opened and saved through the Storage Access Framework or
  by path; a file changed or deleted by another app is noticed, and you choose to reload or keep
  your text.
- **Console and toolchain**: build and run in Termux or with the app's own toolchain, with the
  errors of gcc, clang and Python found in the output.
- **Look**: light, dark, pure-black and dynamic-color themes, highlighting themes, fonts.
- **Robustness**: Java crashes, native crashes and ANRs are captured and shown in a separate
  process.

The whole list is in [Features](docs/features.md).

## Documentation

| Document | What is in it |
| --- | --- |
| [Building](docs/building.md) | requirements, steps, release builds with R8 |
| [Configuration](docs/config.md) | highlighting resources, `servers.json`, formatting rules, your own snippets |
| [Example: language servers in Termux](docs/example.md) | clangd and pyright over a socket, when a server does not connect |
| [Using the editor](docs/usage.md) | code completion, snippets, paths, the outline, comments |
| [Toolchain](docs/toolchain.md) | `packages.json`, the app's own prefix, Termux, run commands |
| [Features](docs/features.md) | everything the editor does |
| [How it stays fast](docs/performance.md) | background highlighting, no flicker, native predicates |
| [Architecture](docs/architecture.md) | project layout, documents and sessions |
| [Testing](docs/testing.md) | the tests of every module and how to run them |

Each module has a README of its own, with documents of its own in [`docs`](docs) (`lsp_usage.md`,
`editor_architecture.md`, ...).

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

## Known limitations

- Native code (`treesitter`, `crash`) is built for `arm64-v8a` only; add ABIs in their
  `build.gradle.kts` for other devices or emulators.
- The highlighting resources are downloaded at the first start: until they are, files are shown
  without highlighting.
- No language server ships with the app. Documents of providers that have no file path (cloud
  drives) are not sent to a server.
- Files are read and written as UTF-8 (a byte order mark is kept).
- Without all files access, path completion and the project's style files only work through a
  folder opened in the file tree; building a project on the shared storage with the app's own
  toolchain needs that access too.
- Programs of the app's own toolchain must be built for Android (bionic) and dynamically linked;
  Termux's packages run in Termux.
- Changes by other apps are noticed for the document shown, from the modification time and size
  its provider reports; a provider that reports neither is not watched.
- The limitations of each module are listed in its README.

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
- [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) (MIT), the snippets of the
  completion, in the downloaded resources.
- [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) (EPL-2.0 or EDL-1.0), a library of `lsp`;
  see [its license](lsp/LICENSE-lsp4j).

See each project for its license.

## License

Apache License 2.0, see [LICENSE](LICENSE).

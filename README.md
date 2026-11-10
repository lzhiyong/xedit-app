# X Editor

English | [中文](README_CN.md)

A code editor for Android, built to stay smooth on large files: an 8.8 MB, 239,000-line `sqlite3.c` scrolls, folds and edits without dropping frames.

## Features

- **Fast on large files**: the text lives in a piece tree (the data structure of VS Code's text buffer); tree-sitter highlighting, parsing and folding are worked out off the main thread.
- **Editing**: undo and redo kept across sessions, word wrap, code folding, indent guides, bracket matching and automatic pairs, search and replace with regular expressions, go to line, commenting, a floating selection bar, pinch to zoom, extra keys above the keyboard.
- **Language servers** (clangd, pyright, ..., set up by you): completion, diagnostics, hover, signature help, definitions, references, rename, code actions, formatting, outline; snippets from friendly-snippets and your own.
- **Files**: tabs and a file tree; opened and saved through the Storage Access Framework or by path; a file changed or deleted by another app is noticed.
- **Console and toolchain**: build and run in Termux or with the app's own toolchain, with the errors of gcc, clang and Python picked out of the output.
- **Look**: light, dark, pure-black and dynamic-color themes; highlight themes and fonts can be imported.
- **Robustness**: Java crashes, native crashes and ANRs are captured and shown in a separate process.

How to use it: see the [app module](app/README.md).

## Building

Requirements: JDK 21; Android SDK platform 36, build tools 37.0.0; NDK 29.0.14206865 with CMake 4.1.2+; Python 3 and git; Gradle 9+. Exact versions are in [`libs.versions.toml`](libs.versions.toml).

```sh
python3 treesitter/get_treesitter_source.py         # fetch the tree-sitter sources (also run by the build)
gradle wrapper                                     # the repository has no wrapper; Android Studio works too
./gradlew :app:assembleDebug
```

- **tree-sitter branch**: `treeSitterBranch` in `gradle.properties` (default `master`), or `-PtreeSitterBranch=v0.25.x` for one build; a branch or a tag. See the [treesitter module](treesitter/README.md#building).
- **Only `arm64-v8a` is built**: for an emulator or another architecture, add its ABI to `abiFilters` in the `build.gradle.kts` of `treesitter`, `crash` and `bypass`.
- **Release**: `./gradlew :app:assembleRelease`, shrunk, optimized and obfuscated by R8, with unused resources removed.
  - Classes reached by reflection or JNI are kept by `app/proguard-rules.pro` and each module's `consumer-rules.pro`; when you add such code, add a rule to the `consumer-rules.pro` of its module.
  - Keep `app/build/outputs/mapping/release/mapping.txt` for every release, to turn crash stack traces back with `retrace mapping.txt stacktrace.txt`.
  - There is no signing configuration in the repository: sign with your own key. CI builds the debug APK.

## Tests

```sh
./gradlew test                          # JVM unit tests of every module
./gradlew :editor:testDebugUnitTest     # one module
./gradlew connectedDebugAndroidTest     # device tests, on a phone or emulator
```

What each module's tests cover is in its README.

## Modules

| Module | What it is | Docs |
| --- | --- | --- |
| [`app`](app) | The app: screens, settings, highlighting, language servers, console, toolchain | [Guide](app/README.md) · [Configuration](app/CONFIG.md) · [Toolchain](app/TOOLCHAIN.md) |
| [`editor`](editor) | The code editor view, `EditorView` | [Guide](editor/README.md) · [Design](editor/DESIGN.md) |
| [`piecetable`](piecetable) | Piece tree text buffer, ported from VS Code | [Guide](piecetable/README.md) · [Design](piecetable/DESIGN.md) |
| [`treesitter`](treesitter) | Kotlin/JNI bindings of tree-sitter, with predicates decided natively | [Guide](treesitter/README.md) |
| [`lsp`](lsp) | LSP client on lsp4j, with plain Kotlin types | [Guide](lsp/README.md) |
| [`document`](document) | A faster, safer `DocumentFile` for SAF documents and plain files | [Guide](document/README.md) |
| [`treeview`](treeview) | Tree view on a single `RecyclerView` (the file tree) | [Guide](treeview/README.md) · [Design](treeview/DESIGN.md) |
| [`crash`](crash) | Capture of Java crashes, native crashes and ANRs | [Guide](crash/README.md) · [Design](crash/DESIGN.md) |
| [`bypass`](bypass) | Lifts the hidden API restrictions (**not used by the app yet**) | [Guide](bypass/README.md) |

A **guide** says what a module does, how to use and configure it, and what its limits are. A **design** explains how it works inside; you need it only to bring the module into your own project or to change it.

## Known limits

- Native code is built for `arm64-v8a` only (see above).
- The highlighting resources are downloaded on first launch; until then there is no syntax highlighting.
- No language server comes with the app; documents without a file path (such as cloud drive files) are not sent to language servers.
- Files are read and written as UTF-8 (a BOM is kept).
- Without all files access, path completion and project style files work only through folders opened in the file tree.
- Each module's own limits are in its guide.

## Third-party code

| Project | License | Use |
| --- | --- | --- |
| [tree-sitter](https://github.com/tree-sitter/tree-sitter) | MIT | Fetched by `treesitter/get_treesitter_source.py` |
| [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter) | MIT | Origin of the `treesitter` module, see [LICENSE](treesitter/LICENSE-kotlin-tree-sitter) |
| The piece tree of [VS Code](https://github.com/microsoft/vscode) | MIT | Ported as the `piecetable` module, see [LICENSE](piecetable/LICENSE-vscode) |
| [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) | EPL-2.0 or EDL-1.0 | Dependency of the `lsp` module, see [LICENSE](lsp/LICENSE-lsp4j) |
| The queries of [nvim-treesitter](https://github.com/nvim-treesitter/nvim-treesitter) | Apache-2.0 | In the downloaded highlighting resources |
| [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) | MIT | In the downloaded highlighting resources |

The licenses in each project's repository are what count.

## License

Apache License 2.0, see [LICENSE](LICENSE).

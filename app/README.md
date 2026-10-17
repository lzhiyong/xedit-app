# app

The application module of X Editor: its screens, settings, syntax highlighting with
tree-sitter, language servers and completion, and the console. It is built on the library
modules: [`editor`](../editor), [`piecetable`](../piecetable/README.md),
[`treesitter`](../treesitter/README.md), [`document`](../document/README.md),
[`treeview`](../treeview/README.md), [`crash`](../crash/README.md) and
[`lsp`](../lsp/README.md).

中文说明见 [README_cn.md](README_cn.md).

## Packages

All under `x.editor.app`:

| Package | What is in it |
| --- | --- |
| `activity` | `EditorActivity`, the editor screen: documents and tabs, the options menu, the floating bar's items, the row of extra keys, double back to leave. `SettingsActivity`, `CrashActivity`. |
| `controller` | Parts of the editor screen: the file tree, the tabs, the search panel, the downloads panel, go to line (`GotoLineDialog`), and the language servers (`LspController`). |
| `highlight` | Highlighting with tree-sitter: `TreeSitterEngine` (queries on a worker thread, blocks in `CaptureStore`), `StructureQuery` (folds, indents), `TreeSitterInjections` with `InjectionPrefilter` (comments that cannot give a capture are not parsed), `QueryPredicates`, the themes. |
| `lsp` | Language servers: `LanguageServers` (`servers.json`, `register`), `LanguageServerSpec`, the connections (Termux over a socket, an installed binary), `LocalFiles`, `CompletionTyping`, `LspLog`, `HoverMarkdown` and `HoverSpans`. |
| `completion` | `BufferWords`: completion from the words of the text, as blink.cmp's buffer source. |
| `console` | The console: `ConsoleSheet` (output, problems, build), `ConsoleLog`, `BuildRunner`, `ProblemMatcher`, `RunCommands` and `RunSettingsDialog` (compile and run). |
| `service` | `EditorService`: reading and writing documents, sessions, downloads; `DocumentSaves`. |
| `view` | `CodeEditorView`: `EditorView` with tree-sitter, messages for copy and cut; the drawers; insets. |
| `filetree` | The entries of the file tree, loading them, `DocumentPaths` (the URIs of documents in their folders). |
| `settings`, `preference` | The settings screens and their preferences; `EditorSettings`. |
| `download`, `treesitter` | Downloading the highlighting resources, keeping them up to date. |
| `crash`, `database`, `session`, `search`, `viewmodel`, `adapter`, `recyclerview`, `util` | Crash reports, the downloads database, editing sessions, search queries, view models, list adapters, helpers. |

## Files it reads and writes

| What | Where |
| --- | --- |
| Highlighting resources (grammars, queries, themes) | downloaded as [`assets/treesitter.json`](src/main/assets/treesitter.json) lists them, into the app's files |
| Language servers | `servers.json` in `xedit/lsp/` on the shared storage (with all-files access), else in `Android/data/x.editor.app/files/lsp/`; opened from the menu, *language servers* |
| Binaries of servers the app starts | `bin/` and `lib/` next to `servers.json`, copied into the app's own storage to run |
| The language servers' log | `lsp.log` next to `servers.json`, and the console |
| Compile and run commands | the app's preferences, per file extension (a long press on the run button) |

## How the parts work

- **Documents and sessions**: see [Documents and sessions](../README.md#documents-and-sessions)
  in the project README.
- **Highlighting**: see [How it stays fast](../README.md#how-it-stays-fast).
- **Language servers and completion**: see
  [Code completion](../README.md#code-completion-language-servers). `LspController` keeps one
  client per server and project root, sends every edit (on one thread, in order), asks for
  completion as the user types (`CompletionTyping`), and falls back to the words of the text.
- **The console**: the output of each channel, the problems of the open documents and of the
  last build, and builds run in Termux (`BuildRunner`, with Termux's `RUN_COMMAND` and its
  result).

## Tests

Plain Kotlin, on the JVM: `./gradlew :app:testDebugUnitTest`.

| Test | Covers |
| --- | --- |
| `CompletionTypingTest` | When typing asks for completion: one char, a word composed by the keyboard, trigger characters, what ends a word, deleting |
| `BufferWordsTest` | The words of a text and how often, fuzzy matching, ranking, what of a label to highlight |
| `ProblemMatcherTest` | Problems in a build's output: gcc and clang, Python tracebacks and syntax errors |
| `RunCommandsTest` | The variables of a run command, the defaults |
| `HoverMarkdownTest` | Hover Markdown as text and marks: headings, code, lists, code blocks and their tokens |
| `GotoLineTargetTest` | What is typed in go to line: lines, `line:column`, the ends of the file |
| `LanguageServerSpecTest` | `servers.json` read into specs, document IDs to files, the project root |

What needs Android (the screens, the views, Termux) is not covered by these.

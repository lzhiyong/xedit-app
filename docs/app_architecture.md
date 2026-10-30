# app: Design and internals

> Part of the documentation of the [app](../app/README.md) module · [中文](app_architecture_cn.md)

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

## How the parts work

- **Documents and sessions**: see [Documents and sessions](architecture.md#documents-and-sessions)
  in the project README.
- **Highlighting**: see [How it stays fast](performance.md).
- **Language servers and completion**: see
  [Code completion](config.md#language-servers-serversjson). `LspController` keeps one
  client per server and project root, sends every edit (on one thread, in order), asks for
  completion as the user types (`CompletionTyping`), and falls back to the words of the text.
- **The console**: the output of each channel, the problems of the open documents and of the
  last build, and builds run in Termux (`BuildRunner`, with Termux's `RUN_COMMAND` and its
  result).

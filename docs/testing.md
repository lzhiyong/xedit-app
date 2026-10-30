# Testing

> Part of the [X Editor](../README.md) documentation · [中文](testing_cn.md)

| Module | Test classes | Covers |
| --- | --- | --- |
| `editor` | 28 | edit history and undo, the document, search, folding, the structure providers, text boundaries, row recordings (`RowCache`, including a randomized no-flicker property), layouts (unwrapped, word wrap, folded, line breaks, tab stops), caret navigation, input and IME, auto pairs, indentation edits, the floating bar's overflow height (`FloatingBarTest`), structure shifting, where popups by the cursor go (`PopupPlacementTest`), snippet tab stops (`SnippetSessionTest`), commenting out and in (`CommentToggleTest`), completions over closing chars (`ClosingCharsTest`) |
| `lsp` | 5 | conversions, snippets, completion ranking, the client |
| `document` | 3 | document files and queries |
| `app` | 16 | when typing asks for completion, words of the text, problems in build output, run commands, hover Markdown, go to line, `servers.json`, which files hold your own snippets, formatting rules, path completion, calls with parameter placeholders, `.editorconfig` and `.clang-format`, comment marks, unpacking packages, sources and catalogs, where commands run (`ToolchainRulesTest`), the root of a project (`ProjectRootTest`) |
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

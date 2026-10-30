# lsp: Testing

> Part of the documentation of the [lsp](../lsp/README.md) module · [中文](lsp_testing_cn.md)

```sh
./gradlew :lsp:testDebugUnitTest
```

| Test | What it covers |
|---|---|
| `LspClientTest` | The whole client against `FakeLanguageServer` over in-memory pipes: the handshake and the capabilities announced, `didOpen` / incremental and full `didChange` / `didSave` / `didClose` with versions, diagnostics published and cleared, completion (trigger characters), resolve, hover, shutdown, a server that does not answer, requests before the start |
| `LspConvertersTest` | Diagnostics, completion items (edits, snippets, documentation, tags, insert-or-replace edits), completion lists, hovers, message types |
| `CompletionRankerTest` | The word before the cursor, the scores, the order |
| `SnippetParserTest` | Tab stops, placeholders (nested), mirrors, choices, variables, escapes, malformed snippets |
| `LspTypesTest` | Positions from and to 1-based, comparisons, ranges, completion kinds |
| `SocketConnectionTest` | `SocketConnection` against a real socket: it connects to the port it is given, and fails where nothing listens |

`FakeLanguageServer` is a complete lsp4j server for the tests: it records what it
hears and answers with fixed items. The pipes are `java.nio` pipes, not
`PipedInputStream`, which fails once the thread that last wrote has ended (lsp4j
writes from several).

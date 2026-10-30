# lsp: Design and internals

> Part of the documentation of the [lsp](../lsp/README.md) module · [中文](lsp_architecture_cn.md)

## Architecture

| File | Responsibility |
|---|---|
| `LspClient.kt` | The client of one server: start (`initialize` / `initialized`), shutdown (`shutdown` / `exit`), the capabilities it announces, diagnostics as a `StateFlow`, requests with a timeout |
| `LspDocument.kt` | One open document: `didOpen`, `didChange` (incremental or full, as the server wants), `didSave`, `didClose`, versions; completion and hover at a position |
| `LanguageServerConnection.kt` | Where the server is: `ProcessConnection`, `SocketConnection`, `StreamConnection` |
| `LspClientConfig.kt` | `LspClientConfig` (how the client introduces itself), `LspClientListener` (what it hears), `ServerFeatures` (what the server can do) |
| `LspTypes.kt` | The types the editor works with: `TextPosition`, `TextRange`, `TextChange`, `LspDiagnostic`, `LspCompletionItem`, `HoverInfo`, ... |
| `LspConverters.kt` | Between lsp4j and those types (internal) |
| `LspFeatureTypes.kt` | The types of the other features: `LspLocation`, `SignatureHelpInfo`, `LspWorkspaceEdit`, `LspCodeAction`, `LspCommand`, `LspSymbol` |
| `LspFeatureConverters.kt` | Between lsp4j and those types (internal) |
| `CompletionRanker.kt` | Filters and orders completions on the client as the user types |
| `SnippetParser.kt` | Turns snippets (`printf(${1:fmt})$0`) into text and tab stops |

```
 editor ── LspDocument ── LspClient ── lsp4j (JSON-RPC) ── LanguageServerConnection ── server
                │              │
                │              └── diagnostics: StateFlow<Map<uri, List<LspDiagnostic>>>
                └── completion / hover (suspend)
```

### What the client announces

The client only says it can do what it handles, so a server never sends what
would go unanswered:

- text sync with `didSave`; completion with snippets (optional), Markdown
  documentation, `completionItem/resolve`, context (trigger characters);
- hover in Markdown or plain text;
- diagnostics with versions;
- workspace folders;
- positions in **UTF-16** code units: the chars of a Kotlin `String`, so no
  conversion is needed.

Not announced: dynamic registration, `workspace/configuration`, progress,
workspace edits. Such a request is answered politely all the same.

## Threading

- lsp4j reads the messages of the server on a thread of the client's own
  executor; `LspClientListener` is called on it. Post to the main thread from
  there.
- Notifications (`didOpen`, `didChange`, ...) are written in the order they are
  called, before any request called after them: a completion asked for after a
  change sees the text of that change.
- `LspDocument` is thread-safe; its changes go out one after the other.
- `diagnostics` is a `StateFlow`: collect it in a lifecycle scope.

# Language Server Protocol Module

A client of the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
for the editor, built on [lsp4j](https://github.com/eclipse-lsp4j/lsp4j): it talks
to a language server (clangd, pyright, rust-analyzer, kotlin-language-server, ...)
and gives the editor **completion**, **diagnostics** and **hover**, in plain
Kotlin types. The editor never sees lsp4j, JSON-RPC or the `Either` types of the
protocol.

Package `x.github.module.lsp`. The main code uses no Android API, so every test is
a fast local JVM test.

## Contents

1. [Architecture](#architecture)
2. [Usage](#usage)
3. [Connecting the editor](#connecting-the-editor)
4. [Running a server on Android](#running-a-server-on-android)
5. [Threading](#threading)
6. [Integration](#integration)
7. [Testing](#testing)
8. [Known limitations](#known-limitations)

---

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

---

## Usage

```kotlin
val client = LspClient(
    connection = SocketConnection("127.0.0.1", 5007),
    config = LspClientConfig(rootUri = "file:///storage/emulated/0/project"),
    listener = object : LspClientListener {
        override fun onShowMessage(kind: MessageKind, message: String) = showToast(message)
    }
)

// connect and introduce the client; what the server can do
val features = client.start()

// open a document; from now on the server hears of its changes
val document = client.open("file:///storage/emulated/0/project/main.c", "c", text)

// a change: replace a range (0-based); the whole text is read only if the server wants it
document.change(TextChange(range, "42")) { buffer.toString() }

// completion at the cursor, filtered on the client as the user keeps typing
val result = document.completion(TextPosition(line, character), trigger = ".")
val shown = CompletionRanker.rank(result.items, CompletionRanker.prefixAt(lineText, character))

// documentation that comes only when asked for
val item = client.resolve(shown.first())

// apply a snippet: the text, and where the cursor goes
val snippet = SnippetParser.parse(item.insertText)   // if item.isSnippet

// diagnostics, as the server publishes them
client.diagnostics.collect { byUri -> show(byUri[document.uri].orEmpty()) }

// hover
val hover = document.hover(TextPosition(line, character))

document.save()
document.close()
client.shutdown()
```

`start`, `completion`, `resolve`, `hover` and `shutdown` are `suspend` functions;
a request that gets no answer within `LspClientConfig.requestTimeoutMillis` fails
with `LspException`, and is cancelled on the server. Cancelling the coroutine
cancels the request too.

### Completion, step by step

1. Ask when the user types a trigger character of the server
   (`ServerFeatures.completionTriggers`: `.`, `->`, `::`, ...) or a letter after
   a pause, or explicitly.
2. As the user goes on typing, filter the list with `CompletionRanker.rank`;
   ask again only if the result was `isIncomplete`.
3. When an item is chosen: `client.resolve(item)` if it lacks documentation;
   replace `item.edit.range` (or the word at the cursor) with `item.insertText`,
   expanded with `SnippetParser` if `item.isSnippet`; apply
   `item.additionalEdits` (an `#include`, an `import`).

---

## Connecting the editor

The piece table counts lines and columns from 1, the protocol from 0:

```kotlin
val position = TextPosition.fromOneBased(lineNumber, column)
val (lineNumber, column) = position.toOneBased()
```

The changes of the editor (`EditorDocument.AppliedChange`) are what an
incremental `didChange` wants: the range that was replaced, in the text before
the change, and the text inserted.

```kotlin
override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, ...) {
    document.change(
        changes.map { change ->
            val r = change.range
            TextChange(
                TextRange(
                    TextPosition.fromOneBased(r.startLine, r.startColumn),
                    TextPosition.fromOneBased(r.endLine, r.endColumn)
                ),
                change.insertedText()
            )
        }
    ) { getTextBuffer().toString() }
}
```

When several changes come at once, each range has to be in the text after the
ones before it (that is how the protocol applies them). If the editor reports all
of them against the text before the first, send them one by one, last first, or
send the whole text with `TextChange.full(...)`.

Documents are named by `file://` URIs that the **server** can read: a path on the
storage both the app and the server can reach. A `content://` URI of the Storage
Access Framework means nothing to a server.

---

## Running a server on Android

An app cannot execute the binaries of another app, so a server installed in
Termux cannot be started by the editor with `ProcessConnection`. Two ways work:

- **A server in Termux behind a TCP bridge** (`SocketConnection`), e.g. clangd:

  ```sh
  pkg install clang socat
  socat TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork EXEC:clangd
  ```

  Each connection starts its own clangd. Keep the project on shared storage
  (`termux-setup-storage`) so both see the same files.

- **A server shipped with the app** (`ProcessConnection`), e.g. a binary packaged
  as `lib<name>.so` in `jniLibs`, which Android extracts to
  `applicationInfo.nativeLibraryDir`, where it may be executed.

---

## Threading

- lsp4j reads the messages of the server on a thread of the client's own
  executor; `LspClientListener` is called on it. Post to the main thread from
  there.
- Notifications (`didOpen`, `didChange`, ...) are written in the order they are
  called, before any request called after them: a completion asked for after a
  change sees the text of that change.
- `LspDocument` is thread-safe; its changes go out one after the other.
- `diagnostics` is a `StateFlow`: collect it in a lifecycle scope.

---

## Integration

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":lsp"))
}

android {
    packaging {
        resources {
            // lsp4j and its JSON-RPC jar both carry these
            // one copy of the licences and notices kept in the APK; Maven's dependency list left out
            pickFirsts += setOf("about.html", "META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }
}
```

The module brings its R8 rules (`consumer-rules.pro`): lsp4j reads its messages
with Gson by reflection, and calls the services through `java.lang.reflect.Proxy`.

A connection to a server on `127.0.0.1` needs the `INTERNET` permission.

---

## Testing

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

---

## Known limitations

- One server per `LspClient`, one project per server.
- Completion, diagnostics, hover, signature help, definition, references, formatting
  (document and range), rename, code actions (with `workspace/executeCommand`, and
  `workspace/applyEdit` handed to `LspClientListener.applyEdit`) and document symbols.
  Not yet: semantic tokens, inlay hints, workspace symbols, call hierarchy, folding
  ranges. Each is a request like `hover`, plus its capability in `clientCapabilities()`.
- A workspace edit's creating, renaming and deleting of files is left out: only edits
  of documents are made.
- Positions are UTF-16 code units only: a server that only counts in UTF-8 is
  not supported (servers fall back to UTF-16, which the protocol requires).
- No `workspace/configuration`: settings go in `initializationOptions`, or in the
  configuration files of the server (`.clangd`, `pyproject.toml`, ...).
- lsp4j is a JVM library: it runs on Android, but check it on the oldest API
  level the app supports.

## License

The module's own code is under the Apache License 2.0, as the rest of X Editor.

It builds on [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j) (`org.eclipse.lsp4j`
and `org.eclipse.lsp4j.jsonrpc`), used as libraries: none of their source is copied here.
LSP4J is under the Eclipse Public License 2.0 **or** the Eclipse Distribution License 1.0
(a BSD-3-Clause licence), at the choice of whoever takes it; both texts are in
[LICENSE-lsp4j](LICENSE-lsp4j). An app that ships the module ships LSP4J's object code: keep
its licence with it (the APK keeps the notices of the jars, see [Integration](#integration)),
and say where its source is, the project above. LSP4J uses
[Gson](https://github.com/google/gson), under the Apache License 2.0.


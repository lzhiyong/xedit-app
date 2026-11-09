# lsp

English | [中文](README_CN.md)

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) client on [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) (package `x.github.module.lsp`), talking to clangd, pyright, rust-analyzer and other language servers. Its interface is plain Kotlin types: the editor never touches lsp4j, JSON-RPC or the protocol's `Either`.

The main code uses no Android API, so every test is a local JVM test.

**Supported**: completion (with resolve and snippets), diagnostics, hover, signature help, definitions, references, formatting (whole document and range), rename, code actions (with `workspace/executeCommand` and the server's `workspace/applyEdit`), document symbols.

## Adding it

```kotlin
dependencies { implementation(project(":lsp")) }

android {
    packaging {
        resources {
            // lsp4j and its JSON-RPC jar both carry these: keep one copy of the notices
            pickFirsts += setOf("about.html", "META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }
}
```

Connecting to a server on `127.0.0.1` needs the `INTERNET` permission. The module ships its R8 rules (lsp4j works through reflection and dynamic proxies).

## Use

```kotlin
val client = LspClient(
    connection = SocketConnection("127.0.0.1", 5007),     // or ProcessConnection, StreamConnection
    config = LspClientConfig(rootUri = "file:///storage/emulated/0/project"),
    listener = object : LspClientListener {
        override fun onShowMessage(kind: MessageKind, message: String) = toast(message)
    }
)
val features = client.start()                              // handshake; what the server supports

val doc = client.open("file:///storage/emulated/0/project/main.c", "c", text)
doc.change(TextChange(range, "42")) { buffer.toString() }  // incremental; the full text only if the server wants it

val result = doc.completion(TextPosition(line, ch), trigger = ".")
val shown = CompletionRanker.rank(result.items, CompletionRanker.prefixAt(lineText, ch))  // filter locally as typing goes on
val item = client.resolve(shown.first())                   // documentation when needed
val snippet = SnippetParser.parse(item.insertText)          // when item.isSnippet: text + tab stops

client.diagnostics.collect { byUri -> show(byUri[doc.uri].orEmpty()) }   // a StateFlow
doc.hover(pos); doc.definition(pos); doc.references(pos); doc.signatureHelp(pos)
doc.formatting(tabSize = 4, insertSpaces = true); doc.rename(pos, "newName")
doc.codeActions(range, diagnostics); doc.documentSymbols()

doc.save(); doc.close(); client.shutdown()
```

- Requests are `suspend` functions. Without an answer within `LspClientConfig.requestTimeoutMillis` they throw `LspException` and are cancelled on the server; cancelling the coroutine cancels them too.
- **Completion**: ask on a trigger character (`features.completionTriggers`), on a pause in typing, or when the user asks; filter with `CompletionRanker.rank` as typing goes on, and ask again only when `isIncomplete`. On picking an item, replace `item.edit.range` (else the word at the cursor) with `insertText`, then apply `additionalEdits` (such as an added `#include`).

## Wiring it to an editor

- **Lines and columns**: the piece table counts from 1, the protocol from 0; convert with `TextPosition.fromOneBased(line, col)` / `toOneBased()`. Positions are UTF-16 code units, the same as Kotlin's `String`, with no conversion.
- **Changes**: the editor's `EditorDocument.AppliedChange` (the range replaced and the inserted text) is exactly an incremental `didChange`. With several changes at once, the protocol wants each range against the text after the earlier ones; if your ranges are all against the text before, send them from the last one, or the full text with `TextChange.full(...)`.
- **URIs**: `file://` URIs the server can read by path. SAF `content://` URIs mean nothing to a server.

## Threads

- lsp4j reads the server's messages on the client's own thread pool, and `LspClientListener` is called there; switch to the main thread for UI.
- Notifications (`didOpen`, `didChange`, ...) go out in call order and before later requests: a completion asked for after a change sees the changed text.
- `LspDocument` is thread-safe. Collect `diagnostics` in a lifecycle scope.

## Running language servers on Android

An app may not execute another app's binaries, so `ProcessConnection` cannot start a server installed in Termux. Two ways work:

- **Run it in Termux, bridged over TCP** (`SocketConnection`): `pkg install clang socat`, then `socat TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork EXEC:clangd`. Each connection starts its own server. Keep the project on the shared storage (`termux-setup-storage` first) so both sides see the same files.
- **Start your own binary** (`ProcessConnection`): for instance named `lib<name>.so` in `jniLibs`, installed into the executable `nativeLibraryDir`. How X Editor does it: see [the app's toolchain](../app/TOOLCHAIN.md).

## Limits

- Not implemented yet: semantic tokens, inlay hints, workspace symbols, call hierarchy, folding ranges.
- File operations in workspace edits (create, rename, delete) are ignored; only changes to document content are applied.
- One `LspClient` per server, one server per project.
- UTF-16 positions only: a server that counts only in UTF-8 cannot be used (the protocol requires servers to fall back to UTF-16).
- No `workspace/configuration` and no dynamic registration: pass settings in `initializationOptions` or the server's own files (`.clangd`, `pyproject.toml`).
- lsp4j is a JVM library: check it on the lowest API level your app supports.

## Tests

`./gradlew :lsp:testDebugUnitTest`. `LspClientTest` connects the whole client through in-memory pipes to `FakeLanguageServer` (a complete lsp4j server that records what it gets and answers with fixed content), covering the handshake, document sync, diagnostics, completion, hover, shutdown and an unresponsive server; the other tests cover type conversion, completion ranking, snippet parsing and a real socket connection.

## License

This module's code is Apache License 2.0. [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j) is used as a library, with none of its source copied, under EPL-2.0 **or** EDL-1.0 (BSD-3-Clause); both texts are in [LICENSE-lsp4j](LICENSE-lsp4j). An app shipping this module ships LSP4J's binaries: include its license (the packaging above keeps the notices in the APK) and say where its source can be had. LSP4J depends on Gson, Apache License 2.0.

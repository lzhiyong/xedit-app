# lsp: Usage

> Part of the documentation of the [lsp](../lsp/README.md) module · [中文](lsp_usage_cn.md)

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

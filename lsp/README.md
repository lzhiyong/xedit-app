# Language Server Protocol Module

A client of the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
for the editor, built on [lsp4j](https://github.com/eclipse-lsp4j/lsp4j): it talks
to a language server (clangd, pyright, rust-analyzer, kotlin-language-server, ...)
and gives the editor **completion**, **diagnostics** and **hover**, in plain
Kotlin types. The editor never sees lsp4j, JSON-RPC or the `Either` types of the
protocol.

Package `x.github.module.lsp`. The main code uses no Android API, so every test is
a fast local JVM test.

## Documentation

The details are in the documents of this module:

- [Design and internals](../docs/lsp_architecture.md): Architecture, Threading
- [Usage](../docs/lsp_usage.md): Usage, Connecting the editor, Running a server on Android, Integration
- [Testing](../docs/lsp_testing.md): Testing

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
its licence with it (the APK keeps the notices of the jars, see [Integration](../docs/lsp_usage.md#integration)),
and say where its source is, the project above. LSP4J uses
[Gson](https://github.com/google/gson), under the Apache License 2.0.

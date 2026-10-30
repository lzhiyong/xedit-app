# app

The application module of X Editor: its screens, settings, syntax highlighting with
tree-sitter, language servers and completion, and the console. It is built on the library
modules: [`editor`](../editor), [`piecetable`](../piecetable/README.md),
[`treesitter`](../treesitter/README.md), [`document`](../document/README.md),
[`treeview`](../treeview/README.md), [`crash`](../crash/README.md) and
[`lsp`](../lsp/README.md).

中文说明见 [README_cn.md](README_CN.md).

## Documentation

The details are in the documents of this module:

- [Design and internals](../docs/app_architecture.md): Packages, How the parts work
- [Resource naming](../docs/app_resources.md): Resource naming
- [Testing](../docs/app_testing.md): Tests

## Files it reads and writes

| What | Where |
| --- | --- |
| Highlighting resources (grammars, queries, themes) | downloaded as [`assets/treesitter.json`](src/main/assets/treesitter.json) lists them, into the app's files |
| Language servers | `servers.json` in `xedit/lsp/` on the shared storage (with all-files access), else in `Android/data/x.editor.app/files/lsp/`; opened from the menu, *language servers* |
| Binaries of servers the app starts | `bin/` and `lib/` next to `servers.json`, copied into the app's own storage to run |
| The language servers' log | `lsp.log` next to `servers.json`, and the console |
| Compile and run commands | the app's preferences, per file extension (a long press on the run button) |

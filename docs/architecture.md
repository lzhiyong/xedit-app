# Architecture

> Part of the [X Editor](../README.md) documentation · [中文](architecture_cn.md)

## Project layout

```
xedit-app/
├── app/                        the application (x.editor.app)
│   └── src/main/java/x/editor/app/
│       ├── activity/           EditorActivity, SettingsActivity, CrashActivity
│       ├── controller/         file list, search panel, go to line
│       ├── highlight/          TreeSitterEngine, CaptureStore, StructureQuery,
│       │                       TreeSitterInjections, InjectionPrefilter, QueryPredicates
│       ├── service/            EditorService: reading and writing documents, sessions, downloads
│       ├── view/               CodeEditorView (EditorView with tree-sitter), drawers, insets
│       └── ...                 settings, downloads, database, search, sessions, utilities
├── editor/                     the editor view (x.github.module.editor)
│   └── src/main/java/.../editor/
│       ├── view/               EditorView, FloatingBar, CodeStructure
│       ├── render/             TextRenderer, RowCache, FrameBudget, Gutter
│       ├── layout/             unwrapped, word wrap and folded layouts
│       ├── input/              gestures, selection, IME, auto pairs
│       ├── structure/          folding, indent guides, brackets
│       └── model/              the document, edit history, search results
├── piecetable/                 the text buffer
├── treesitter/                 tree-sitter bindings (Kotlin + C++), get_sources.py
├── document/  treeview/  crash/  lsp/  bypass/
├── libs.versions.toml          versions of the plugins and libraries
└── settings.gradle.kts
```

## Documents and sessions

`EditorActivity` decides which document is open:

- One document is loaded at a time. Choosing another cancels the load in progress, and the
  new load starts only after the old one has stopped.
- A document is written back only if it was loaded completely, so the text of one file can
  never be written into another.
- Saves run one after another, and opening another document does not cancel them. A load
  waits for the pending save before it replaces the text in the editor.
- If writing the previous document back fails, it stays open, with its changes, and the
  error is shown.

The editing session of a document is stored with the SHA-256 of the file content it
belongs to. The text is always read from the file; the session is restored only if the
hash still matches.

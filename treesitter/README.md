# treesitter

Kotlin bindings of [tree-sitter](https://github.com/tree-sitter/tree-sitter) for Android,
over JNI. Predicates of queries are decided in native code where they can be, Lua patterns
(`#lua-match?`) included, before a match is handed to Kotlin.

中文说明见 [README_cn.md](README_cn.md).

## Origin and license

This module comes from [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter),
the Kotlin bindings of the tree-sitter project. Its Kotlin code is reworked from
kotlin-tree-sitter's, much of it rewritten; its JNI layer, written in C there, is rewritten
in C++. Every source file that comes from kotlin-tree-sitter says so in its header, after
its own licence header; `ts_query_filter.cpp` and `ts_query_filter.h` do not come from it.

Added in this module: the evaluation of query predicates in native code
(`ts_query_filter.cpp`, the Lua pattern matcher with it), handing a query cursor the text
(`TSQueryCursor.setText`), and marking the matches whose predicates were decided natively
(`TSQueryMatch.predicatesDecided`).

kotlin-tree-sitter is under the MIT License, Copyright (c) 2024 tree-sitter contributors:
its text is in [LICENSE-kotlin-tree-sitter](LICENSE-kotlin-tree-sitter), which has to stay
with the module and its derived files. tree-sitter itself,
whose sources the module builds, is under the MIT License too.

## What is in it

| Class | What it is |
| --- | --- |
| `TSLanguage` | A grammar, loaded from its library at run time: `TSLanguage(path, symbol)` (`libtree-sitter-c.so`, `tree_sitter_c`). No grammar is built into the module. |
| `TSParser` | Parses text into a `TSTree`, from a string or a callback that reads it in chunks; a tree from before makes it incremental. |
| `TSTree`, `TSNode`, `TSTreeCursor` | The syntax tree, its nodes, a cursor to walk it. `TSTree.edit` tells a tree of an edit before it is parsed again; `copy` gives another thread a tree of its own. |
| `TSQuery`, `TSQueryCursor` | A query, and its matches or captures over a node, in a byte range. `setText` gives the cursor the text, for the predicates to be decided natively. |
| `TSQueryPredicate` | The predicates decided in Kotlin: what native code leaves (`#match?`, Vim regexes) or decides without the text. |
| `TSLookaheadIterator` | The symbols valid in a parse state (for error recovery and completion). |
| `TSPoint`, `TSRange`, `TSInputEdit`, `TSInputEncoding`, `TSQueryError` | Plain types: positions, ranges, edits, the encoding of the text, what is wrong with a query. |

## Use

```kotlin
val language = TSLanguage("/path/to/libtree-sitter-c.so", "tree_sitter_c")
val parser = TSParser(language)
// the text in UTF-16LE, as Kotlin strings are: a byte offset is a char index × 2
val tree = parser.parse(null, source)
tree.textReader = TSTree.TextReader { start, end -> source.subSequence(start, end) }

val query = TSQuery(language, """((call_expression function: (identifier) @name) (#eq? @name "printf"))""")
val cursor = query(tree.rootNode)
cursor.byteRange = 0u..(source.length * 2).toUInt()
// the predicates decided natively, from the text
cursor.setText(0u, source)
for (match in cursor.matches()) {
    val call = match["name"].first()
}
cursor.close(); query.close(); tree.close(); parser.close()
```

After an edit: `tree.edit(TSInputEdit(...))`, then `parser.parse(tree, newSource)` parses
only what changed.

## Predicates in native code

`ts_query_filter.cpp` decides `eq?`, `any-of?`, `lua-match?`, `contains?`, `has-ancestor?`,
`has-parent?` and `kind-eq?`, with their `not-` and `any-` forms. A match whose predicates
all hold comes with the top bit of its pattern index set (`TSQueryMatch.predicatesDecided`),
and Kotlin does not evaluate them again.

`lua-match?` uses a port of LuaJIT's `string.find`: every Lua 5.1 pattern feature
(classes, sets, quantifiers, anchors, captures, back-references, `%b`, `%f`), on UTF-8 bytes.
It was compared with LuaJIT 2.1 on 123,760 cases: the same results, the same errors.

## Building

- The tree-sitter sources are cloned by [`get_sources.py`](get_sources.py) into
  `src/main/cpp/tree-sitter`: `python3 get_sources.py`. tree-sitter 0.24 or later is
  needed (`ts_node_child_with_descendant`).
- CMake builds `libandroid_tree_sitter.so`, for `arm64-v8a` only (`abiFilters` in
  `build.gradle.kts`).

## Threads and memory

Parsers, trees, queries and cursors are not thread-safe: use each on one thread, and give
another thread its own `tree.copy()`. They are `AutoCloseable`; what is not closed is freed
when it is collected (`RefCleaner`).

## Tests

`TreeSitterTypesTest`, on the JVM (`./gradlew :treesitter:testDebugUnitTest`): what the
native side hands back (a match's pattern index and whether its predicates were decided
natively), points and ranges, query errors, encodings, predicates of a match without its
captures.

Parsing and queries need the native library and a grammar, so they need a device: they are
not covered by these tests. The native predicates were tested on a desktop build of the
same code, with the Lua patterns compared against LuaJIT.

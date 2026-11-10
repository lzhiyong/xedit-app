# treesitter

English | [中文](README_CN.md)

Kotlin bindings (JNI) of [tree-sitter](https://github.com/tree-sitter/tree-sitter) for Android. The main difference from the official bindings: **query predicates are decided in native code wherever possible** (`#lua-match?` included) before matches are handed to Kotlin, so large numbers of matches do not cross JNI back and forth.

No grammar is built in; grammars are loaded from their library files at run time.

## Building

```sh
python3 treesitter/get_treesitter_source.py                    # clones tree-sitter (master) into src/main/cpp/tree-sitter
python3 treesitter/get_treesitter_source.py --branch v0.25.x   # another branch, or a tag (v0.25.3); 0.24 or later
```

The build runs it too, before every build (the `clone` task), with the branch of `treeSitterBranch` in the root `gradle.properties` (default `master`); `-PtreeSitterBranch=...` overrides it for one build. A checkout already of that branch (or tag) is left as it is, without going to the network, so offline builds work once it is there. One of another branch is cloned again, into a temporary directory first, so that a failed clone keeps the old one; one with local changes is never replaced unless `--force` is given. `--update` fetches the latest commit of the branch. The branch can also come from the `TREE_SITTER_BRANCH` environment variable when the script is run by hand. It exits with status 1 when the sources are not there afterwards, which fails the build.

CMake then builds `libandroid_tree_sitter.so`. Only `arm64-v8a` for now; add other ABIs to `abiFilters` in `build.gradle.kts`.

tree-sitter is linked into that one `.so` as a static library, with ThinLTO, so that the JNI functions can inline it; only `JNI_OnLoad` and `JNI_OnUnload` are exported (`exports.map`). A grammar therefore cannot depend on a `libtree_sitter.so` or on its symbols.

Every build type is compiled with `-O2`, ThinLTO and `NDEBUG` by default, because the APK CI publishes is the debug one. To step through the native code, add `-DTS_OPTIMIZE=OFF` to the cmake `arguments` in `build.gradle.kts`.

## Use

```kotlin
val language = TSLanguage("/path/to/libtree-sitter-c.so", "tree_sitter_c")   // library file + symbol name
val parser = TSParser(language)
val tree = parser.parse(null, source)            // read as UTF-16LE: byte offset = char index × 2
tree.textReader = TSTree.TextReader { start, end -> source.subSequence(start, end) }

val query = TSQuery(language, """((call_expression function: (identifier) @name) (#eq? @name "printf"))""")
val cursor = query(tree.rootNode)
cursor.byteRange = 0u..(source.length * 2).toUInt()
cursor.setText(0u, source)                       // with the text, predicates are decided natively
for (match in cursor.matches()) {
    val name = match["name"].first()
}
cursor.close(); query.close(); tree.close(); parser.close()
```

**Without objects** (the fast path, what the editor's highlighting uses): `forEachCapture` and `forEachMatch` hand out captures and matches as ints, in batches of a JNI call each, instead of a node, a capture, a list, a match and a pair for each one. Only a match whose predicates Kotlin has to check (`#match?`, or a predicate on text that was not given) still comes as a `TSQueryMatch`, for the predicate function. `parser.parseChunks(oldTree) { byteIndex -> ... }` reads the text as strings, which are UTF-16 already: no byte array per chunk.

```kotlin
cursor.forEachCapture { pattern, capture, startByte, endByte ->
    val name = query.captureNames[capture]
}
cursor.forEachMatch { match ->              // a view, valid during the call only
    for (i in 0 until match.captureCount) println("${match.captureIndex(i)} ${match.startByte(i)}")
}
cursor.forEachMatch(withNodes = true) { match ->
    val node = match.node(match.firstOf(query.captureIndexOf("name")))   // no native call; valid for as long as the tree
}
```

**Running a query again**: `cursor.reset(node)` runs the cursor's query again over another node (of the same tree or another), instead of a new cursor being made and closed. Byte and point ranges and limits stay as set; the text and whatever the last run had not handed out are dropped. The editor keeps one cursor per query for highlighting, run again for each block and each injected part.

**One text for several cursors**: `cursor.setText(startByte, string)` copies the text into the cursor. A `TSQueryText` is copied into native memory once and read in place by every cursor given it, and its buffer is used again by the next `set`:

```kotlin
val text = TSQueryText()                                 // one, kept: set again for each block
text.set(startByte, source, from, to)                    // the chars [from, to) of source, no substring
for ((query, root) in layers) {
    val cursor = cursors.getOrPut(query) { query(root) }.also { it.reset(root) }
    cursor.byteRange = range
    cursor.setText(text)                                 // nothing copied
    cursor.forEachCapture { pattern, capture, start, end -> /* ... */ }
}
```

A cursor holds on to the native text until it is run again or closed, so closing the `TSQueryText` never frees a text in use; but `set` changes it in place, so do not set it while a cursor given it is still being iterated, nor from another thread.

`node.type` and `node.grammarType` are cached by symbol in `TSLanguage`: the same String each time, instead of a new one.

**Incremental parsing**: after an edit, tell the tree with `tree.edit(TSInputEdit(...))`, then `parser.parse(tree, newSource)`; only what changed is parsed again. A large text can be given in chunks through a callback instead of one string.

| Class | What it is |
| --- | --- |
| `TSLanguage`, `TSParser` | Loading a grammar, parsing (a string or a chunk callback; an old tree makes it incremental) |
| `TSTree`, `TSNode`, `TSTreeCursor` | Syntax tree, nodes, a walking cursor; `tree.copy()` makes an independent tree for another thread |
| `TSQuery`, `TSQueryCursor` | A query and its matches or captures in a node and byte range; `reset` runs it again over another node |
| `TSQueryText` | A text for the predicates of several cursors, copied once |
| `TSQueryPredicate` | Predicates decided in Kotlin: what native code cannot do (Vim regex `#match?`), or when no text was given |
| `TSLookaheadIterator` | The symbols valid in a parse state, for error recovery and completion |

## Native predicates

Decided in C++: `eq?`, `any-of?`, `lua-match?`, `contains?`, `has-ancestor?`, `has-parent?`, `kind-eq?`, with their `not-` and `any-` forms. When all predicates of a match hold, its pattern index carries the top bit (`TSQueryMatch.predicatesDecided`) and Kotlin does not decide them again. Only `match?` (Vim regex) is still done in Kotlin.

`lua-match?` is ported from LuaJIT's `string.find`, with all of Lua 5.1 patterns (classes, sets, quantifiers, anchors, captures, back-references, `%b`, `%f`), matching UTF-8 bytes; tested against LuaJIT 2.1 on 123,760 cases, with identical results and errors.

## Threads and memory

- Parsers, trees, queries and cursors are **not thread-safe**: use each on one thread, and `tree.copy()` to cross threads.
- All are `AutoCloseable`: close them when done. Those left open are freed when garbage collected (`RefCleaner`), but do not rely on it.

## Limits

- `arm64-v8a` only.
- No built-in grammars: provide the library files yourself.
- `#match?` is decided in Kotlin, slower than the native predicates (see below).

## Future work: `#match?` and `#vim-match?` in native code

Not done yet, written down here so it can be picked up later. These two are Vim regular expressions, which the native filter does not implement (`compile` in `ts_query_filter.cpp` gives up on them). A match of a pattern with one comes to Kotlin as a `TSQueryMatch`, a String is made of the text of each node, and `QueryPredicates.vimToRegex` (a Java regex, cached) decides it. Every other predicate of such a match is still decided natively first, so only a match that none of them rejects comes over.

**Measure first.** The queries of nvim-treesitter mostly use `#lua-match?`, which is native already; how much this costs depends on the query files downloaded. Count them:

```sh
grep -rhoE '#(any-)?(not-)?(vim-)?match\?' path/to/queries | sort | uniq -c   # the downloaded query files
```

and time `findBlock` (or sample with simpleperf) on a file of a language whose highlights use them. If it is a few patterns that rarely match, leave it.

**Plan, if it is worth it.** Translate, when the query is compiled, the simple Vim regexes into Lua patterns, and decide them with the `lua_find` that is there and tested against LuaJIT; leave everything else to Kotlin as now:

- In `compile`, for `match?` / `vim-match?` and their `not-` / `any-` forms, try `vim_to_lua(pattern)`; on success make a `Kind::LUA_MATCH` filter with the translated pattern (same `positive` / `any`), else `return false` as now.
- Translatable: ASCII only (Lua matches bytes, Vim chars: `.`, `[^...]` and classes differ on anything else); literals, `.`, `^` / `$` at the ends, `[...]` sets, `*`, `\+` (`+` with `\v`), `\=` / `\?`, `\d \s \w \a \l \u \x \h` and their uppercase negations as `%d %s [%w_] %a %l %u %x [%a_]`.
- Not translatable, left to Kotlin: alternation `\|`, groups `\(...\)` and `\%(...\)` with a quantifier after them, `\{n,m}`, `\<` / `\>` word boundaries (or as `%f[%w_]` / `%f[^%w_]`, checked against Vim on the boundary cases), `\c` (no case-insensitive Lua patterns), back-references, any lookaround (`\@=` and others), `~`, and any escape not in the list above. When unsure, do not translate: a pattern left to Kotlin is only slower, a wrong translation styles text wrongly.
- Lua magic characters that are literal in Vim (`%`, `-`, `(`, `)`, ...) are escaped with `%` in the translation.
- Test it like `lua-match?` was: the translated pattern under `lua_find` against `vimToRegex` (Java) on generated texts, for every pattern of the downloaded queries and for generated ones; the two must agree on every text, or the pattern must not be translated.

## Tests

`./gradlew :treesitter:testDebugUnitTest`: `TreeSitterTypesTest` covers decoding what the native layer returns, points and ranges, query errors, encodings, and predicates when a capture is missing. Parsing and querying need the native library and a grammar, so they run only on a device and have no automated tests; the native predicates were tested on a desktop build of the same code, with the Lua patterns compared against LuaJIT.

## Origin and license

Derived from [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter) (MIT, Copyright (c) 2024 tree-sitter contributors); its license is in [LICENSE-kotlin-tree-sitter](LICENSE-kotlin-tree-sitter), which has to stay with this module and works derived from it. The Kotlin code was rewritten after the original, and its C JNI layer rewritten in C++; files derived from it say so in their headers. Added here: deciding predicates natively (`ts_query_filter.cpp/.h`, with the Lua pattern matcher), `TSQueryCursor.setText`, `TSQueryText` and `TSQueryMatch.predicatesDecided`, `TSQueryCursor.reset`, the batched `forEachCapture` / `forEachMatch` (`TSQueryMatchView`), `TSParser.parseChunks`, and the `@CriticalNative` scalars of `TSNode`. The tree-sitter sources it is built with are MIT as well.

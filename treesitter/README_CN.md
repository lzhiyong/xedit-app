# treesitter

[English](README.md) | 中文

[tree-sitter](https://github.com/tree-sitter/tree-sitter) 在 Android 上的 Kotlin 绑定（JNI）。与官方绑定相比，主要区别是**查询谓词尽可能在原生代码中判断**（包括 `#lua-match?`），判断完再把匹配交给 Kotlin，避免大量匹配在 JNI 两侧来回。

本模块不内置任何语法，语法库在运行时从文件加载。

## 构建

```sh
python3 treesitter/get_treesitter_source.py                    # 把 tree-sitter（master）克隆到 src/main/cpp/tree-sitter
python3 treesitter/get_treesitter_source.py --branch v0.25.x   # 其他分支，或者 tag（如 v0.25.3）；需要 0.24+
```

每次构建前 Gradle 也会自动执行它（`clone` 任务），分支取根目录 `gradle.properties` 中的 `treeSitterBranch`（默认 `master`），单次构建可以用 `-PtreeSitterBranch=...` 覆盖。

- 已有的源码就是这个分支（或 tag）时直接跳过，不联网，所以拉取过一次之后可以离线构建。
- 已有的是别的分支时重新克隆，先克隆到临时目录再替换，克隆失败时保留原来的源码。
- 有本地修改的源码不会被替换，除非加 `--force`。
- `--update` 拉取该分支的最新提交。
- 手动运行时也可以用环境变量 `TREE_SITTER_BRANCH` 指定分支。
- 结束时源码不存在就以状态 1 退出，Gradle 构建随之失败。

然后由 CMake 构建 `libandroid_tree_sitter.so`。目前只构建 `arm64-v8a`，其他架构在 `build.gradle.kts` 的 `abiFilters` 中添加。

tree-sitter 以静态库链接进这一个 `.so`，开启 ThinLTO，JNI 函数可以直接内联 tree-sitter 的代码，对外只导出 `JNI_OnLoad` 和 `JNI_OnUnload`（`exports.map`）。因此语法库不能依赖 `libtree_sitter.so` 或它的符号。

所有构建类型默认都用 `-O2`、ThinLTO 和 `NDEBUG` 编译，因为 CI 发布的是 debug APK。需要单步调试原生代码时，在 `build.gradle.kts` 的 cmake `arguments` 中加上 `-DTS_OPTIMIZE=OFF`。

## 使用

```kotlin
val language = TSLanguage("/path/to/libtree-sitter-c.so", "tree_sitter_c")   // 库文件 + 符号名
val parser = TSParser(language)
val tree = parser.parse(null, source)            // 按 UTF-16LE 读取：字节偏移 = 字符下标 × 2
tree.textReader = TSTree.TextReader { start, end -> source.subSequence(start, end) }

val query = TSQuery(language, """((call_expression function: (identifier) @name) (#eq? @name "printf"))""")
val cursor = query(tree.rootNode)
cursor.byteRange = 0u..(source.length * 2).toUInt()
cursor.setText(0u, source)                       // 提供文本，谓词才能在原生代码中判断
for (match in cursor.matches()) {
    val name = match["name"].first()
}
cursor.close(); query.close(); tree.close(); parser.close()
```

**不创建对象的快路径**（编辑器高亮用的就是这个）：`forEachCapture` 和 `forEachMatch` 把捕获和匹配以 int 形式分批返回，每批一次 JNI 调用，不再为每个捕获创建节点、捕获、列表、匹配和 Pair。只有谓词需要在 Kotlin 中判断的匹配（`#match?`，或没有提供文本时的文本谓词）仍以 `TSQueryMatch` 交给谓词函数。`parser.parseChunks(oldTree) { byteIndex -> ... }` 以字符串读取文本（本来就是 UTF-16），不再为每个分块创建字节数组。

```kotlin
cursor.forEachCapture { pattern, capture, startByte, endByte ->
    val name = query.captureNames[capture]
}
cursor.forEachMatch { match ->              // 视图对象，只在本次调用内有效
    for (i in 0 until match.captureCount) println("${match.captureIndex(i)} ${match.startByte(i)}")
}
cursor.forEachMatch(withNodes = true) { match ->
    val node = match.node(match.firstOf(query.captureIndexOf("name")))   // 不需要调用原生代码，节点在语法树存活期间一直有效
}
```

**重新执行查询**：`cursor.reset(node)` 让 cursor 对另一个节点（同一棵树或另一棵树）重新执行它的查询，不必新建再关闭一个 cursor。字节范围、位置范围和各项限制保持原样；文本，以及上一轮还没返回的结果，都会丢弃。编辑器高亮时每个查询只保留一个 cursor，每个 block、每个注入区域都复用它。

**多个 cursor 共用一份文本**：`cursor.setText(startByte, string)` 会把文本复制进这个 cursor。`TSQueryText` 只复制进原生内存一次，交给它的所有 cursor 都直接读这一份，下一次 `set` 还会复用它的缓冲区：

```kotlin
val text = TSQueryText()                                 // 只建一个，每个 block 重新 set
text.set(startByte, source, from, to)                    // source 的 [from, to) 字符，不经过 substring
for ((query, root) in layers) {
    val cursor = cursors.getOrPut(query) { query(root) }.also { it.reset(root) }
    cursor.byteRange = range
    cursor.setText(text)                                 // 不复制
    cursor.forEachCapture { pattern, capture, start, end -> /* ... */ }
}
```

cursor 会一直持有这份原生文本，直到重新执行或关闭，所以关闭 `TSQueryText` 不会释放正在使用的文本。但 `set` 是原地修改，不要在拿到它的 cursor 还在迭代时 `set`，也不要在别的线程上 `set`。

`node.type` 和 `node.grammarType` 按符号缓存在 `TSLanguage` 中，同一种类型每次返回同一个字符串，不再每次创建。

**增量解析**：编辑后先 `tree.edit(TSInputEdit(...))` 告知语法树发生了什么，再 `parser.parse(tree, newSource)`，只重新解析变化的部分。大文本可以通过回调分块提供，而不是传整个字符串。

| 类 | 作用 |
| --- | --- |
| `TSLanguage`、`TSParser` | 加载语法、解析（字符串或分块回调，传入旧树即增量解析） |
| `TSTree`、`TSNode`、`TSTreeCursor` | 语法树、节点、遍历游标；`tree.copy()` 为另一个线程复制独立的树 |
| `TSQuery`、`TSQueryCursor` | 查询，以及在某个节点、字节范围内的匹配或捕获；`reset` 对另一个节点重新执行 |
| `TSQueryText` | 供多个 cursor 判断谓词用的文本，只复制一次 |
| `TSQueryPredicate` | 原生代码处理不了的谓词（Vim 正则 `#match?`），或没有提供文本时，在 Kotlin 中判断 |
| `TSLookaheadIterator` | 某个解析状态下允许出现的符号，可用于错误恢复和补全 |

## 原生谓词

在 C++ 中判断：`eq?`、`any-of?`、`lua-match?`、`contains?`、`has-ancestor?`、`has-parent?`、`kind-eq?`，以及它们的 `not-`、`any-` 形式。一个匹配的谓词全部成立时，其模式编号带上最高位标记（`TSQueryMatch.predicatesDecided`），Kotlin 不再重复判断。只有 `match?`（Vim 正则）仍在 Kotlin 中处理。

`lua-match?` 移植自 LuaJIT 的 `string.find`，支持 Lua 5.1 模式的全部特性（字符类、字符集、量词、锚点、捕获、反向引用、`%b`、`%f`），按 UTF-8 字节匹配，与 LuaJIT 2.1 对比测试了 123,760 个用例，结果和报错完全一致。

## 线程与内存

- 解析器、语法树、查询、游标都**不是线程安全的**：每个对象只在一个线程上使用，需要跨线程时用 `tree.copy()`。
- 都实现了 `AutoCloseable`，用完请关闭；没关闭的对象在被垃圾回收时释放（`RefCleaner`），但不要依赖它。

## 限制

- 只构建 `arm64-v8a`。
- 不内置语法，需要自己提供语法库文件。
- `#match?` 在 Kotlin 中判断，比原生谓词慢（见下节）。

## 待改进：在原生代码中判断 `#match?` 和 `#vim-match?`

这一项还没有做，记在这里方便以后接着改。这两种谓词用的是 Vim 正则，原生过滤器没有实现（`ts_query_filter.cpp` 的 `compile` 遇到它们直接放弃）。带这类谓词的 pattern，其 match 会以 `TSQueryMatch` 对象交给 Kotlin，每个节点的文本都要建一个 String，再由 `QueryPredicates.vimToRegex`（转成 Java 正则并缓存）判断。这类 match 的其他谓词仍然先在原生判断，只有没被它们排除的 match 才会交给 Kotlin。

**先统计再决定。** nvim-treesitter 的查询以 `#lua-match?` 为主，这部分已经在原生判断；这一项的开销有多大，取决于下载的查询文件用了多少 `#match?`。先统计：

```sh
grep -rhoE '#(any-)?(not-)?(vim-)?match\?' path/to/queries | sort | uniq -c   # 下载的查询文件目录
```

再找一个高亮查询用到它们的语言，对 `findBlock` 计时，或者用 simpleperf 采样。如果只有少数几个 pattern，而且很少匹配，就不必做。

**值得做的话，计划如下。** 编译查询时把简单的 Vim 正则翻译成 Lua 模式，用现有的、已经和 LuaJIT 对比测试过的 `lua_find` 判断；翻译不了的仍像现在一样交给 Kotlin：

- 在 `compile` 中，对 `match?`、`vim-match?` 及其 `not-`、`any-` 形式尝试 `vim_to_lua(pattern)`。成功时生成一个 `Kind::LUA_MATCH` 过滤器，用翻译后的模式，`positive` 和 `any` 不变；失败时像现在一样 `return false`。
- **可以翻译的：** 只限 ASCII，因为 Lua 按字节匹配而 Vim 按字符匹配，遇到非 ASCII 字符时 `.`、`[^...]` 和字符类的结果会不同。具体包括：普通字符、`.`、开头的 `^` 和结尾的 `$`、`[...]` 字符集、`*`、`\+`（`\v` 模式下是 `+`）、`\=` 和 `\?`，以及 `\d \s \w \a \l \u \x \h` 和它们的大写取反形式，分别译成 `%d %s [%w_] %a %l %u %x [%a_]`。
- **不翻译、留给 Kotlin 的：** 分支 `\|`；后面跟量词的分组 `\(...\)` 和 `\%(...\)`；`\{n,m}`；单词边界 `\<`、`\>`（也可以译成 `%f[%w_]`、`%f[^%w_]`，但要先在边界情况上和 Vim 对比确认）；`\c`（Lua 模式不支持忽略大小写）；反向引用；所有环视（`\@=` 等）；`~`；以及上面没列出的任何转义。拿不准的一律不翻译：交给 Kotlin 只是慢一点，翻译错了却会导致着色错误。
- 在 Vim 里是普通字符、在 Lua 里有特殊含义的字符（`%`、`-`、`(`、`)` 等），翻译时要用 `%` 转义。
- 测试方法和当初测 `lua-match?` 一样：拿下载的查询文件里的每一个 pattern，加上随机生成的 pattern，在随机生成的文本上对比翻译结果的 `lua_find` 和 `vimToRegex`（Java）的结果。两者必须在所有文本上一致，否则这个 pattern 就不翻译。

## 测试

`./gradlew :treesitter:testDebugUnitTest`：`TreeSitterTypesTest` 覆盖原生层返回结果的解码、位置与范围、查询错误、文本编码、缺少捕获时的谓词。解析和查询需要原生库和语法库，只能在设备上运行，没有自动化测试；原生谓词曾在同一份代码的桌面构建上测试，Lua 模式匹配与 LuaJIT 做过对比。

## 来源与许可证

来源于 [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter)（MIT，Copyright (c) 2024 tree-sitter contributors），许可证全文见 [LICENSE-kotlin-tree-sitter](LICENSE-kotlin-tree-sitter)，需与本模块及其衍生文件一同保留。Kotlin 代码参考原项目改写，原版 C 语言的 JNI 层用 C++ 重写；来源于原项目的文件都在文件头注明。新增内容：原生谓词判断（`ts_query_filter.cpp/.h`，含 Lua 模式匹配器）、`TSQueryCursor.setText`、`TSQueryText` 和 `TSQueryMatch.predicatesDecided`、`TSQueryCursor.reset`、分批的 `forEachCapture` / `forEachMatch`（`TSQueryMatchView`）、`TSParser.parseChunks`，以及 `TSNode` 的 `@CriticalNative` 标量 getter。编译使用的 tree-sitter 源码同样为 MIT 许可证。

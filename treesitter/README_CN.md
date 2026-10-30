# treesitter

[tree-sitter](https://github.com/tree-sitter/tree-sitter) 在 Android 上的 Kotlin 绑定，通过 JNI 实现。查询中的谓词尽可能在原生代码中判断（包括 Lua 模式匹配 `#lua-match?`），判断完成后再把匹配结果交给 Kotlin。

English version: [README.md](README.md).

## 来源与许可证

本模块来源于 [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter)，即 tree-sitter 项目官方的 Kotlin 绑定。Kotlin 代码参考原项目改写，其中大量内容经过重写；原版的 JNI 层使用 C 语言编写，本模块用 C++ 重写了这一层。每个来源于 kotlin-tree-sitter 的源文件，都在自身的许可证文件头之后注明了来源；`ts_query_filter.cpp` 和 `ts_query_filter.h` 不来源于原项目。

本模块在原版基础上新增的内容：在原生代码中判断查询谓词（`ts_query_filter.cpp`，以及其中的 Lua 模式匹配器）、把文本交给查询游标（`TSQueryCursor.setText`），以及标记谓词已在原生层判断过的匹配（`TSQueryMatch.predicatesDecided`）。

kotlin-tree-sitter 采用 MIT 许可证，版权所有 (c) 2024 tree-sitter contributors，许可证全文见 [LICENSE-kotlin-tree-sitter](LICENSE-kotlin-tree-sitter)。该文件需要与本模块及其衍生文件一同保留。本模块编译时使用的 tree-sitter 源码同样采用 MIT 许可证。

## 内容

| 类 | 说明 |
| --- | --- |
| `TSLanguage` | 一种语法，运行时从它的库文件加载：`TSLanguage(路径, 符号名)`，例如 `libtree-sitter-c.so` 和 `tree_sitter_c`。本模块不内置任何语法。 |
| `TSParser` | 把文本解析为 `TSTree`。文本可以是字符串，也可以通过回调分块读取；传入之前的语法树时，进行增量解析。 |
| `TSTree`、`TSNode`、`TSTreeCursor` | 语法树、树中的节点，以及遍历用的游标。重新解析之前，用 `TSTree.edit` 告知语法树发生了哪些编辑；`copy` 为另一个线程复制一棵独立的语法树。 |
| `TSQuery`、`TSQueryCursor` | 查询，以及它在某个节点、某个字节范围内的匹配或捕获结果。`setText` 把文本交给游标，谓词才能在原生代码中判断。 |
| `TSQueryPredicate` | 在 Kotlin 中判断的谓词：原生代码处理不了的（`#match?` 这类 Vim 正则），或者没有提供文本时的判断。 |
| `TSLookaheadIterator` | 某个解析状态下允许出现的符号，可用于错误恢复和补全。 |
| `TSPoint`、`TSRange`、`TSInputEdit`、`TSInputEncoding`、`TSQueryError` | 基础类型：位置、范围、编辑、文本编码、查询错误。 |

## 用法

```kotlin
val language = TSLanguage("/path/to/libtree-sitter-c.so", "tree_sitter_c")
val parser = TSParser(language)
// 文本按 UTF-16LE 读取，与 Kotlin 字符串一致：字节偏移 = 字符下标 × 2
val tree = parser.parse(null, source)
tree.textReader = TSTree.TextReader { start, end -> source.subSequence(start, end) }

val query = TSQuery(language, """((call_expression function: (identifier) @name) (#eq? @name "printf"))""")
val cursor = query(tree.rootNode)
cursor.byteRange = 0u..(source.length * 2).toUInt()
// 提供文本后，谓词在原生代码中判断
cursor.setText(0u, source)
for (match in cursor.matches()) {
    val call = match["name"].first()
}
cursor.close(); query.close(); tree.close(); parser.close()
```

编辑之后：先调用 `tree.edit(TSInputEdit(...))`，再调用 `parser.parse(tree, newSource)`，只会重新解析变化的部分。

## 原生代码中的谓词

`ts_query_filter.cpp` 负责判断 `eq?`、`any-of?`、`lua-match?`、`contains?`、`has-ancestor?`、`has-parent?` 和 `kind-eq?`，以及它们的 `not-` 和 `any-` 形式。如果一个匹配的谓词全部成立，它的模式编号会带上最高位标记（`TSQueryMatch.predicatesDecided`），Kotlin 不会再重复判断。

`lua-match?` 使用移植自 LuaJIT `string.find` 的实现，支持 Lua 5.1 模式的全部特性：字符类、字符集、量词、锚点、捕获、反向引用、`%b`、`%f`；按 UTF-8 字节匹配。它与 LuaJIT 2.1 对比测试了 123,760 个用例，结果和报错完全一致。

## 构建

- tree-sitter 源码由 [`get_sources.py`](get_sources.py) 克隆到 `src/main/cpp/tree-sitter`：`python3 get_sources.py`。需要 tree-sitter 0.24 或更高版本（用到了 `ts_node_child_with_descendant`）。
- 用 CMake 构建 `libandroid_tree_sitter.so`，目前只构建 `arm64-v8a`（见 `build.gradle.kts` 中的 `abiFilters`）。

## 线程与内存

解析器、语法树、查询和游标都不是线程安全的：每个对象只在一个线程上使用；需要在另一个线程上使用时，用 `tree.copy()` 复制一份给它。它们都实现了 `AutoCloseable`；没有关闭的对象，在被垃圾回收时释放（`RefCleaner`）。

## 测试

`TreeSitterTypesTest`，在 JVM 上运行（`./gradlew :treesitter:testDebugUnitTest`）。覆盖内容：原生层返回的匹配结果的解码（模式编号，以及谓词是否已在原生层判断）、位置与范围、查询错误信息、文本编码、匹配中缺少捕获时的谓词结果。

解析和查询需要原生库和语法库，只能在设备上运行，这些测试没有覆盖。原生谓词曾在同一份代码的桌面构建上测试过，其中 Lua 模式匹配与 LuaJIT 做过对比。

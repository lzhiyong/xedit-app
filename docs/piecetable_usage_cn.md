# piecetable：使用指南

> [piecetable](../piecetable/README_CN.md) 模块文档 · [English](piecetable_usage.md)

## 快速开始

### 1. 接入

```kotlin
// settings.gradle.kts
include(":piecetable")

// app/build.gradle.kts
dependencies {
    implementation(project(":piecetable"))
}
```

模块本身只依赖 `kotlinx-serialization`，用于公开值类型（`Range`、`Position`、`TextChange` 等）上的
`@Serializable` 标注。缓冲区和树本身不可序列化：请持久化文本（以及编辑器状态），恢复时重新 build。

### 2. 构建缓冲区

```kotlin
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

// 流式读入，大文件不需要一次性构造 String
val builder = PieceTreeTextBufferBuilder()
file.bufferedReader().use { reader ->
    val chars = CharArray(64 * 1024)
    while (true) {
        val n = reader.read(chars)
        if (n <= 0) break
        builder.acceptChunk(String(chars, 0, n))
    }
}

val textBuffer: PieceTreeTextBuffer = builder.build(
    eol = EndOfLine.LF,
    normalizeEOL = true
)

textBuffer.getLineCount()      // 行数
textBuffer.getLineContent(1)   // 第 1 行，行号从 1 开始
textBuffer.getEOL()            // "\n" 或 "\r\n"，从内容嗅探得到
textBuffer.getBOM()            // 文件自带的 UTF-8 BOM
```

`acceptChunk` 会正确处理跨 chunk 的 `\r\n` 和 UTF-16 代理对，所以按任意长度切块都安全。
过大的 chunk 会被切成不超过 64K 字符的缓冲区，因此把整个文件作为一个 String 传入（包括
`PieceTreeTextBufferBuilder(text)` 构造函数）也没有问题。

### 3. 编辑

```kotlin
val result = textBuffer.applyEdits(
    listOf(
        SingleEditOperation(Range(1, 1, 1, 1), "// "),   // 第 1 行行首插入
        SingleEditOperation(Range(3, 1, 4, 1), "")       // 删除第 3 行
    ),
    recordTrimAutoWhitespace = false,
    computeUndoEdits = true                              // 需要撤销就传 true
)

result.changes        // ContentChange 列表，用来刷新 UI / 重新高亮
result.reverseEdits   // 反向编辑，压入撤销栈
```

内部按位置从后往前应用，多个操作之间的偏移量不会互相影响。**区间不允许重叠**（相邻接触可以），
重叠会抛 `IllegalArgumentException`；越界的区间会被钳制进文档，而不是击穿到底层数据结构。
插入文本的换行符会统一转换成缓冲区的 EOL，混用多种换行符的文本也一样。

### 4. 撤销

```kotlin
val undo = result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }
textBuffer.applyEdits(undo, false, false)   // 回到上一个状态
```

连续输入想合并成一步撤销时，用 `TextChange.compressConsecutiveTextChanges` 把多次编辑的
`textChange` 压缩成一组。

反向编辑用 String 保存被删除的文本，所以撤销一次大范围删除，要多保留一份完整副本。
`applyEditsWithSliceUndo` 返回的撤销批次是引用存储的片段，不复制文本，见[片段](#片段不复制大段内容的撤销)：

```kotlin
val result = textBuffer.applyEditsWithSliceUndo(ops)        // 编辑内容与 applyEdits 相同
val undone = textBuffer.applySliceEdits(result.reverseEdits) // 撤销，返回重做批次
textBuffer.applySliceEdits(undone.reverseEdits)              // 重做
```

### 5. 搜索

```kotlin
// 纯文本查找；搜索词含换行时跨行匹配，不区分 \n / \r\n / \r
buffer.find("TODO", searchRange, limitResultCount = 1000) { false }

// 正则，逐行匹配
buffer.find(Regex("""fun\s+\w+"""), searchRange, 1000) { isCancelled() }

// 跨行正则：带 RegexOption.MULTILINE，或模式中出现 \n、\r、\W
buffer.find(Regex("""\{\s*\n\s*\}"""), searchRange, 1000) { isCancelled() }
```

- 搜索区间会先钳制进文档，区间的起止列会被遵守。
- 两种引擎里 `^` 和 `$` 都表示**行**首和行尾，与 VSCode 的 `m` 标志一致。搜索区间的起止列不算行首、
  行尾；前后断言和 `\b` 能看到同一行里区间外的文本。
- 逐行匹配的结果不会跨越换行。可能匹配到换行的模式（字面换行、`\n`、`\r`、`\W`）会自动改走多行
  引擎：它把区间涉及的行用 `\n` 拼接，只在区间内匹配，所以 `\n` 能匹配任意一种换行符。
- 最后一个参数是取消回调，配合协程可以随时中断长搜索。

### 6. 保存

```kotlin
textBuffer.readPiecesContent { chunk ->
    outputStream.write(chunk.toByteArray())   // 分片写盘，不构造整份副本
}
```

`toString()` 会把整个文档拼成一个 String，大文件可能 OOM，落盘请用 `readPiecesContent`。

### 7. 在后台读取：快照

```kotlin
// 在编辑线程上（或持读锁）创建：很便宜，不复制文件内容
val snapshot = textBuffer.createSnapshot()

// 任意线程、任意时间读取，不加锁，用户继续输入也没关系
executor.execute {
    var offset = 0
    while (true) {
        val chunk = snapshot.getNearestChunk(offset)   // 增量解析器就是这样读的
        if (chunk.isEmpty()) break
        offset += chunk.length
    }
    // snapshot.versionId 是创建时的 textBuffer.getVersionId()：
    // 两者一比，就知道结果是否仍然对应当前内容
}
```

快照是文档在某一个版本的内容，之后无论缓冲区发生什么（编辑、`compact()`、`setEOL()`）都不会变。
后台高亮和保存都读快照，而不是持有锁，见[边输入边高亮](piecetable_architecture_cn.md#边输入边高亮)。

## API 速查

### 读

| 方法 | 说明 |
| --- | --- |
| `getLineCount()` | 行数，最少为 1 |
| `getLineContent(line)` | 行内容，**不含**行尾符，带缓存 |
| `getLineContentWithEOL(line)` | 行内容 + EOL |
| `getLinesContent()` | 全部行的列表 |
| `getLineLength(line)` / `getLineMaxColumn(line)` | 行长度 / 行长度 + 1，未归一化 EOL 的缓冲区也准确 |
| `getLineFirstNonWhitespaceColumn(line)` | 首个非空白列，全空白返回 0 |
| `getValueInRange(range, eol)` | 取区间文本（区间先钳制进文档），可指定输出行尾 |
| `getOffsetAt(line, column)` / `getPositionAt(offset)` | 行列 ↔ 偏移，均做钳制 |
| `getCharCode(offset)` / `getLineCharCode(line, index)` | UTF-16 code unit（`charCodeAt` 语义），文档末尾返回 0 |
| `getNearestChunk(offset)` | 从 offset 开始的一整段连续文本（最多约 64K 字符，到末尾返回 `""`），给 tree-sitter 之类增量解析器用 |
| `createSnapshot(preserveBOM = false)` | 当前内容的不可变副本，编辑期间任意线程都能读，见[快照](#快照) |
| `getVersionId()` | 内容每次变化（`applyEdits`、`setEOL`）都会改变，读操作和 `compact()` 不会 |
| `find(text 或 regex, range, limit, isCancelled)` | 搜索，见[搜索](#5-搜索) |
| `length` / `get(i)` / `subSequence(a, b)` | `CharSequence` 实现，越界下标抛 `IndexOutOfBoundsException` |
| `contentEquals(other)` | 比较 BOM、EOL 与全部内容 |

### 写

| 方法 | 说明 |
| --- | --- |
| `applyEdits(ops, recordTrimAutoWhitespace, computeUndoEdits)` | 所有编辑的统一入口 |
| `setEOL(eol)` | 切换行尾并重建全文 |
| `compact()` | 用当前文本重建存储，见[内存与压缩](piecetable_architecture_cn.md#内存与压缩) |
| `getStorageStats()` | 文档长度、实际存储的字符数、change buffer 大小、piece 数 |
| `applyEditsWithSliceUndo(ops)` | 与 `applyEdits` 相同的文本编辑，撤销批次以片段返回，见[片段](#片段不复制大段内容的撤销) |
| `applySliceEdits(edits)` | 用 `TextSlice` 替换区间（撤销 / 重做），返回变更和反向批次 |

**参数规则。** 按行查询的方法（`getLineContent`、`getLineContentWithEOL`、`getLineLength`、
`getLineMinColumn` / `getLineMaxColumn`、`getLineCharCode`、首尾非空白列）要求行号存在，即
`1..getLineCount()`，否则抛 `IndexOutOfBoundsException`。按位置或区间的方法（`getOffsetAt`、
`getValueInRange`、`getValueLengthInRange`、`getCharacterCountInRange`、`applyEdits`、`find`）则会钳制。

`PieceTreeBase.insert` / `delete` 是底层原语，现在会校验偏移量并抛 `IndexOutOfBoundsException`，
但不会对插入文本做 EOL 归一化，业务层请优先用 `applyEdits`。

### 快照

`createSnapshot()` 返回一个 `PieceTreeSnapshot`：创建那一刻的内容，之后永远不变。创建它是对缓冲区
的一次读操作，耗时 O(piece 数)，外加复制仍在文档中的输入文本，不复制文件内容。读快照不需要任何锁，
任意线程都可以读，缓冲区正在编辑、压缩或切换 EOL 时也一样。

| 成员 | 说明 |
| --- | --- |
| `length` / `get(i)` / `substring(a, b)` / `subSequence(a, b)` | 按文档偏移量的 `CharSequence`（不含 BOM）；`get` 是 O(log n) |
| `getNearestChunk(offset)` | 与缓冲区的同名方法约定相同：offset 所在 piece 的剩余部分，最多约 64K 字符，到末尾返回 `""` |
| `readPiecesContent { }` | 逐个 piece 交出全部内容，例如用来保存 |
| `toString()` | 全部内容拼成一个 String |
| `versionId` / `lineCount` / `eol` | 创建快照时缓冲区的状态 |
| `bom` / `read()` | VSCode 风格的顺序读取：先返回 `bom`（`preserveBOM = true` 时是 BOM，否则为 `""`），再逐个返回 piece，最后返回 `null`。`read()` 会记住读到哪里，同一时间只能有一个线程调用；其他成员都可以多线程同时使用 |

为什么不受后续编辑影响：创建时，每个 piece 都被换算成「文本、起点、长度」。原始 chunk 里的 piece
直接引用那个 chunk，树持有 chunk 之后再也不会写它；change buffer 每次编辑都会增长，其中的 piece
复制一份。之后不再经过树、缓冲区列表或 lineStarts 查找任何东西。代价是：快照存活期间，它引用的
chunk 不能被回收，即使 `compact()` / `setEOL()` 已经在缓冲区里把它们换掉，所以处理完就不要再持有快照。

### 片段：不复制大段内容的撤销

`applyEdits` 用 String 返回每个编辑删除的内容（`reverseEdits[i].text`），所以删除或替换一大段选区时，
撤销数据要再保留一份被删文本的完整副本。片段接口改用 `TextSlice` 返回：短内容是 String，长内容是
指向所在存储的引用，不复制。

```kotlin
// 编辑：文本编辑与 applyEdits 相同，撤销批次以片段返回
val result = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(selection, "")))
undoStack.add(result.reverseEdits)

// 撤销：应用反向批次，返回的是重做批次
val undone = buffer.applySliceEdits(undoStack.removeAt(undoStack.size - 1))
redoStack.add(undone.reverseEdits)

// 重做，依此类推
val redone = buffer.applySliceEdits(redoStack.removeAt(redoStack.size - 1))
undoStack.add(redone.reverseEdits)
```

| 成员 | 说明 |
| --- | --- |
| `exportSlice(range)` | 区间（先钳制）的内容，以片段返回；读操作 |
| `applySliceEdits(edits)` | 把每个 `SliceEdit.range` 替换为它的 `content`（`null` 表示删除）；返回 `changes` 和 `reverseEdits` |
| `applyEditsWithSliceUndo(ops)` | 与 `applyEdits` 相同的文本编辑，`reverseEdits` 为片段 |
| `TextSlice.of(text)` | 持有 String 的片段，可以插入任何缓冲区 |
| `TextSlice.length` / `eolCount` / `firstLineLength` / `lastLineLength` | 内容的长度和行结构，不需要读取内容 |
| `TextSlice.toText()` | 内容的 String 形式（持有引用时会复制一份） |

- **两种形式。** 短于 `TextSlice.TEXT_THRESHOLD`（16K 字符）的内容保存为 String，和 `reverseEdits`
  一样。更长的内容保存为若干段「缓冲区、起点、长度」；插回时生成指向这些段的 piece，既不复制，也不往
  change buffer 追加。期间被 `compact()` 或 `setEOL()` 换掉的存储，插回时会重新纳入缓冲区。删除 1MB
  再撤销，`getStorageStats()` 与删除前完全相同。
- **换行符。** 能原样插回时就原样插回：EOL 相同，并且导出时缓冲区已归一化，或者插入的缓冲区本身也
  未归一化。否则像 `applyEdits` 的文本一样归一化为缓冲区的 EOL。所以只要期间没有调用 `setEOL()`，
  撤销就能还原完全相同的文本，混合换行符也一样。
- **规则。** 与 `applyEdits` 相同：区间指批次执行前的文档并会被钳制，区间重叠抛
  `IllegalArgumentException`，从下往上应用，所有检查都在第一处修改之前完成。不支持：自动空白的修剪、
  `forceMoveMarkers`、1000 个以上编辑的合并。
- **变更。** `SliceEditResult.changes` 是 `SliceChange`（区间、偏移、长度，以及以片段表示的新内容），
  不是 `ContentChange`：更新装饰或驱动增量解析器时，用 `content.length` / `eolCount` /
  `lastLineLength`，而不是文本。`SliceChange.removed` 是这一处删掉的内容（与对应反向编辑的内容是同一个
  片段，没有删除时为 null），自己维护基于偏移量的撤销历史时不必再把 changes 和 reverseEdits 一一对应。
- **形式与大小。** `TextSlice.isText` 表示内容是否以 String 保存（可在任何线程读取、可脱离缓冲区保留），
  `segmentCount` 是引用的存储段数，可用来估算片段自身的占用。
- **持有引用的片段属于它的缓冲区。** 插入到其他缓冲区会抛 `IllegalArgumentException`（跨缓冲区移动
  内容请用 `TextSlice.of(slice.toText())`）。读取它（`toText()`、插回）是对该缓冲区的读操作，所以片段
  要放在撤销栈所在的地方，在缓冲区的锁下使用。它会让引用的存储无法回收，并且不可序列化：要持久化撤销
  历史，请保存 `toText()`。
- **合并连续编辑。** `TextChange.compressConsecutiveTextChanges` 只处理文本。使用片段时，每个批次
  单独作为一个撤销条目，或者只合并内容为文本的条目。

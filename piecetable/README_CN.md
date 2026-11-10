# piecetable

[English](README.md) | 中文 · [设计](DESIGN_CN.md)

Kotlin 实现的 **piece tree 文本缓冲区**（红黑树 + 片段表），移植自 [VS Code](https://github.com/microsoft/vscode) 的 `pieceTreeTextBuffer`，供 Android 上的代码编辑器使用。

## 为什么用它

和 `StringBuilder` 或 `List<String>` 相比：

- **打开大文件不复制内容**：原始文本按块原样持有，之后的输入只追加到一个缓冲区末尾。
- **插入、删除、行列与偏移量换算都是 O(log n)**（n 为片段数），与文件大小无关。
- **后台任务不阻塞输入**：快照创建很快，之后可以在任意线程读取，缓冲区同时继续编辑。
- **大段撤销不复制文本**：片段接口让撤销历史引用存储本身。

想了解它为什么能做到这些，见[设计](DESIGN_CN.md)。

## 接入

```kotlin
// settings.gradle.kts
include(":piecetable")
// build.gradle.kts
dependencies { implementation(project(":piecetable")) }
```

只依赖 `kotlinx-serialization`（用于 `Range`、`Position` 等值类型）。缓冲区本身不可序列化：持久化文本，恢复时重新构建。

## 使用

```kotlin
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

// 1. 构建：流式读入，任意切块都安全（会正确处理跨块的 \r\n 和代理对）
val builder = PieceTreeTextBufferBuilder()
file.bufferedReader().use { r ->
    val chars = CharArray(64 * 1024)
    while (true) { val n = r.read(chars); if (n <= 0) break; builder.acceptChunk(String(chars, 0, n)) }
}
val buffer = builder.build(eol = EndOfLine.LF, normalizeEOL = true)

// 2. 读：行号、列号从 1 开始
buffer.getLineCount(); buffer.getLineContent(1); buffer.getOffsetAt(3, 5)

// 3. 编辑：一批操作，区间都以编辑前的文本为准，不能重叠
val result = buffer.applyEdits(
    listOf(SingleEditOperation(Range(1, 1, 1, 1), "// "), SingleEditOperation(Range(3, 1, 4, 1), "")),
    recordTrimAutoWhitespace = false,
    computeUndoEdits = true
)
result.changes        // 用来刷新界面、更新语法树
result.reverseEdits   // 反向编辑，压入撤销栈

// 4. 撤销
buffer.applyEdits(result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }, false, false)

// 5. 搜索：^ $ 表示行首行尾；含换行的模式自动走多行匹配；最后一个参数用于取消
buffer.find(Regex("""fun\s+\w+"""), searchRange, limitResultCount = 1000) { isCancelled() }

// 6. 保存：分片写出，不要用 toString()（大文件可能 OOM）
buffer.readPiecesContent { chunk -> out.write(chunk) }

// 7. 后台读取：快照
val snapshot = buffer.createSnapshot()          // 在编辑线程上创建，很快
executor.execute { snapshot.getNearestChunk(0) } // 任意线程读，不需要锁
```

撤销大段删除时，`applyEdits` 的反向编辑会多保存一份被删文本。不想复制时改用片段接口：

```kotlin
val result = buffer.applyEditsWithSliceUndo(ops)          // 编辑效果与 applyEdits 相同
val undone = buffer.applySliceEdits(result.reverseEdits)  // 撤销，返回重做批次
buffer.applySliceEdits(undone.reverseEdits)               // 重做
```

16K 字符以上的内容以引用保存：删除 1 MB 再撤销，存储与删除前完全相同。

## 必须知道的三条规则

**1. 线程：同一个缓冲区可以多个线程同时读，或者一个线程写，但读写不能同时进行。**

不同缓冲区之间互不影响。最简单的做法是给每个缓冲区一把读写锁：

```kotlin
val lock = ReentrantReadWriteLock()                           // 每个缓冲区一把
lock.write { buffer.applyEdits(ops, false, true) }            // 编辑
lock.read { buffer.find(regex, range, 1000) { cancelled } }   // 读取，任意线程
```

耗时较长又不能阻塞输入的工作（高亮、保存、建索引）用快照，而不是长时间持有读锁。快照创建时是一次读操作，之后再也不碰缓冲区。比较 `snapshot.versionId == buffer.getVersionId()` 即可知道结果是否仍对应当前内容。

**2. 内存：缓冲区不会自动释放已删除的文本。**

编辑从不修改或释放已有文本，被删除的输入仍留在追加缓冲区里，长时间编辑还会产生大量小片段。内存大致等于「文件 + 输入过的全部文本」。在保存后或空闲时调用 `compact()` 重建存储：

```kotlin
lock.write {
    val s = buffer.getStorageStats()
    val garbage = s.storedLength - s.length
    if (garbage > maxOf(s.length.toLong(), 4L * 1024 * 1024) || s.pieceCount > 50_000) buffer.compact()
}
```

`compact()` 耗时 O(n)，需要写锁和额外约一份文档大小的内存，不要放在输入路径上。它不改变文本、偏移量和行号，所以光标和撤销数据仍然有效。

**3. `CharSequence` 实现很方便但很慢。**

`get(i)` 是 O(log n)，直接对缓冲区跑 `Regex` 会严重退化，请用 `find()`。

## 常用 API

| 方法 | 说明 |
| --- | --- |
| `getLineCount()`、`getLineContent(line)`、`getLineLength(line)` | 按行读；行号不存在时抛 `IndexOutOfBoundsException` |
| `getValueInRange(range)`、`getOffsetAt(line, col)`、`getPositionAt(offset)` | 按区间、位置读；超出范围时钳制进文档 |
| `getNearestChunk(offset)` | 从 offset 开始的一段连续文本（最多约 64K 字符），给 tree-sitter 等增量解析器用 |
| `getVersionId()` | 内容每次变化都会改变 |
| `applyEdits(ops, …)` | 所有编辑的统一入口；插入文本的换行符统一转换为缓冲区的 EOL |
| `applyEditsWithSliceUndo`、`applySliceEdits`、`exportSlice` | 不复制大段内容的编辑、撤销和导出 |
| `createSnapshot()` | 不可变快照，见上文 |
| `compact()`、`getStorageStats()` | 重建存储、查看存储占用 |
| `setEOL(eol)` | 切换换行符并重建全文 |
| `readPiecesContent { }` | 逐片输出全部内容，用于保存 |

`PieceTreeBase.insert` / `delete` 是底层原语，不做换行符归一化，业务代码请用 `applyEdits`。

## 限制

- 快照存活期间，它引用的存储块无法回收，即使 `compact()` 已经替换了它们，用完不要继续持有。快照的 `read()` 不能两个线程同时调用（其他成员可以）。
- 持有引用的片段只能插回它来自的缓冲区，且不可序列化；持久化撤销历史请保存 `toText()`。片段接口不支持修剪自动空白和 `forceMoveMarkers`。
- 多行搜索会复制搜索区间涉及的所有行；在 `normalizeEOL = false` 且换行符混杂的缓冲区上，返回的区间可能有偏差。
- 编辑区间的校验不会避开代理对（emoji 两半之间），需要调用方自己处理（见[设计](DESIGN_CN.md#编辑器集成)）。
- `getValueLengthInRange` 忽略 `eol` 参数。

## 测试

```bash
./gradlew :piecetable:testDebugUnitTest            # JVM
./gradlew :piecetable:connectedDebugAndroidTest    # 设备
```

全部用例在 `src/sharedTest` 中，JVM 和设备各跑一遍：包括 VS Code 的原始测试集、固定种子的随机测试、与朴素实现对拍的搜索测试，以及每个已修复缺陷一条的回归测试。在自己的项目中配置共享测试源集时的注意事项见[设计](DESIGN_CN.md#测试配置)。

## 许可证

Apache License 2.0。piece tree 移植自 [VS Code](https://github.com/microsoft/vscode)（MIT，Copyright (c) 2015 - present Microsoft Corporation），许可证全文见 [LICENSE-vscode](LICENSE-vscode)，需与本模块及其衍生文件一同保留。移植自 VS Code 的源文件都在文件头中注明了来源；`common/intList.kt`、`textSlice.kt` 和新增的测试是本模块自己的代码。

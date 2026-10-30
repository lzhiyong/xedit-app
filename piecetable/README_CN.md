# piecetable

Kotlin 实现的 **Piece Tree（红黑树 + 片段表）文本缓冲区**，移植自
[VSCode](https://github.com/microsoft/vscode) 的 `pieceTreeTextBuffer`，面向 Android 上的代码编辑器。

[English](README.md)

相比 `StringBuilder` 或行数组（`List<String>`）：

- **打开大文件不复制内容**：原始 chunk 原样持有，编辑只往一个 append-only 缓冲追加；
- **插入 / 删除是 O(log n)**（n 为 piece 数），与文档大小无关；
- **行列 ↔ 偏移量换算 O(log n)**，每个节点缓存左子树的字符数 `size_left` 与换行数 `lf_left`；
- **读操作可以并行**：没有编辑进行时，多个线程可以同时读（见[线程模型](../docs/piecetable_architecture_cn.md#线程模型)）。
- **后台任务不阻塞输入**：不可变快照可以在缓冲区继续编辑的同时被任意线程读取（见[快照](../docs/piecetable_usage_cn.md#快照)）。

## 文档

详细内容见本模块的文档：

- [使用指南](../docs/piecetable_usage_cn.md)：快速开始、API 速查
- [设计与原理](../docs/piecetable_architecture_cn.md)：实现原理、复杂度、线程模型、内存与压缩
- [编辑器集成](../docs/piecetable_integration_cn.md)：编辑器集成
- [测试](../docs/piecetable_testing_cn.md)：测试

## 仍然存在的限制

- **快照会让旧的 chunk 无法回收**：快照存活期间，它引用的 chunk 一直留在内存里，即使
  `compact()` / `setEOL()` 已经把它们换掉。快照的 `read()` 会记住读取位置，不能两个线程同时调用
  （其他成员可以）。
- **持有引用的片段只能用于它的缓冲区**，并会让引用的存储无法回收，也不可序列化。片段接口不修剪
  自动空白，没有 `forceMoveMarkers`，变更中也不带文本。
- **`CharSequence` 实现方便但慢**：`get(i)` 是 O(log n)，直接拿缓冲区跑 `Regex` 会严重退化，
  请改用 `find()`。
- **压缩需要手动触发**：已删除的文本和碎片化的 piece 只有 `compact()`（或 `setEOL()`）才会释放，
  缓冲区不会自动压缩，见[内存与压缩](../docs/piecetable_architecture_cn.md#内存与压缩)。
- **多行搜索会复制搜索区间涉及的所有行**（包括首尾两整行），在 CRLF 缓冲区上换算偏移时假设每个
  换行都是 `\r\n`。
  `normalizeEOL = false` 且行尾混杂的缓冲区上，返回的区间可能有偏差。
- **编辑可能劈开代理对**：区间校验不会把落在 emoji 两半之间的列挪开（VSCode 会）。
- **`getValueLengthInRange` 忽略 `eol` 参数。**

## 许可证

Apache License 2.0，与 X Editor 其余部分相同。piece tree 移植自 [Visual Studio Code](https://github.com/microsoft/vscode)，后者采用 MIT 许可证，版权所有 (c) 2015 - present Microsoft Corporation，许可证全文见 [LICENSE-vscode](LICENSE-vscode)。该文件需要与本模块及其衍生文件一同保留。算法与原始测试用例的版权归原作者所有。

每个移植自 VS Code 的源文件，都在自身的许可证文件头之后注明了来源，并写明对应的 VS Code 源文件。这些文件包括：piece tree 本身（`pieceTreeBase`、`pieceTreeSnapshot`、`pieceTreeTextBuffer`、`pieceTreeTextBufferBuilder`、`rbTreeBase`）、`common/` 下的 `charCode`、`strings`、`position`、`range`、`model`，以及测试 `PieceTreeBaseTest`、`PieceTreeEditOperationTest` 和 `TestSupport`。`common/intList.kt`、`textSlice.kt` 和其余测试是本模块自己的代码。

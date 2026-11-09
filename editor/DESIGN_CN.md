# editor：设计

[English](DESIGN.md) | 中文 · [说明](README_CN.md)

本文面向要修改编辑器或把它引入自己项目的开发者。

## 结构

`EditorView` 只是一层薄壳：公开 API、给子类的扩展点、View 生命周期。其余职责按包划分：

| 包 | 负责 |
| --- | --- |
| `model` | 文本缓冲区接口 `EditorBuffer`、编辑管线 `EditorDocument`、撤销历史 `UndoManager`、搜索结果、编辑会话 |
| `layout` | 显示行与缓冲区行的映射（不折行、折行、折叠）、字体度量、制表位，以及全量重排的线程与互斥（`LayoutCoordinator`） |
| `render` | 可见行循环与行缓存（`TextRenderer`、`RowCache`）、行背景与选区、行号栏、折叠外观 |
| `input` | 触摸、滚动、选区与手柄、硬件键盘、输入法、剪贴板、自动配对、缩进、无障碍 |
| `structure` | 括号匹配、缩进线、折叠的接口与按缩进的简单实现 |
| `view` | `EditorView` 本身，以及代码结构的询问与缓存（`CodeStructure`）、保存点（`SaveTracker`）、浮条 |

依赖是单向的：`view` 依赖其余各包，其余包不依赖 `view`，`model` 不依赖任何界面类。需要 View 配合的协作者各自声明一个窄接口 `Host`，由 `EditorView` 内部的私有适配器实现，所以内部回调不会出现在公开 API 中。

坐标有两套：触摸事件用 View 坐标（含内边距），其余一切用内容坐标（原点在文本左上角），`内容 = View + 滚动量 − 内边距`。

## 文本缓冲区

编辑器只面向 `EditorBuffer` 接口工作（行列从 1 起，偏移量从 0 起，范围含头不含尾，`applyEdits` 中各范围都以编辑前的文本为准）。自带实现 `PieceTreeBuffer` 包装 piece tree；要接入 rope、gap buffer 等结构，实现这个接口即可。

所有编辑都经过 `applySliceEdits`：内容以 `TextSlice` 传入，每处改动报告它删除的内容，撤销历史保存的就是这些。piece tree 上，16K 字符以上的内容以对存储的引用保存，所以全选删除几十 MB 再撤销，撤销栈里只有几个引用，插回时也不复制。其他缓冲区实现走基于字符串的默认实现，结果正确，只是大段内容会被复制。引用只对来源的 piece tree 有效，加载另一个文本时撤销历史清空。

## 布局与重排

| | 不折行 | 折行 |
| --- | --- | --- |
| 一个显示行 | 一个缓冲区行 | 缓冲区行的一段 |
| 存储 | 每行一个 `Int` 宽度 | 每个显示行一个对象 |
| 字号变化 | 按比例换算，立即生效 | 必须重排 |
| 单次编辑 | 只测量改动的行 | 只重排改动的行 |

- 有折叠时，`FoldedLayout` 包在两者之一外面，把被折叠的行从行序列里去掉。渲染、选区、行号、滚动、方向键都按行工作，因此自动跳过折叠行。
- 全量重排在新数据上排完再整体替换，被取消的重排不留痕迹。
- **编辑与重排经过同一把锁**：重排读全文时编辑进不来，编辑进行时其他线程上的重排等它结束。所有改动文本的入口都经过这道门，这是 README 中「排版期间编辑被拒绝」的来源，不靠调用方自觉。
- 一次编辑插入 2 万行以上（如撤销「删除半个文件」）时，新行先得到粗略布局，编辑完成后在后台按正常方式重排；更小的编辑仍在主线程上直接测量。

## 绘制

- 每个可见行被录制一次（`RowCache`），之后每帧回放。编辑后只重新录制被编辑的行；增删了行时，下方各行的录制内容整体移动而不是重录。
- 已经着色的行永远不会被纯文本替换，这保证了后台高亮期间不闪烁。
- 光标、手柄、括号加粗、缩进线都画在行缓存之外，光标移动不需要重录。
- 超过 4096 字符的行不进行录制，直接绘制。

## 撤销与保存点

- 连续输入（间隔不超过 500 毫秒、单次改动 16K 字符以内）合并为一步；大改动立即单独成步。
- `isModified` 跟随撤销历史而不是计数：撤销回保存点即为未修改；撤销到保存点之前又输入新内容，或保存点因超过上限被丢弃，或执行了不可撤销的编辑，之后一直算作已修改直到下次保存。
- `getTextVersion` 记下撤销历史的位置并结束当前的连续输入（保存点必须落在两步之间），所以写盘期间继续输入，`markSaved(version)` 仍能找到被写下的那份文本。

## 编辑会话

撤销步骤是固定位置上的编辑，用在别的文本上会把它改乱，所以 `restoreSession` 恢复前逐项核对：

| 核对项 | 由谁提供 | 能发现 |
| --- | --- | --- |
| `contentHash` | 应用计算 | 文件被外部修改 |
| `textLength`、`lineCount` | 编辑器在保存时推算 | 同样的字节被读成了不同的文本（编码、换行处理不同） |
| 加载后是否编辑过 | 编辑器的修改状态 | 当前文本已不是会话所属的文本 |

会话总是属于**磁盘上的文本**：保存点之后未保存的修改转进重做栈，下次打开时按重做找回。会话只保留离保存点最近的部分历史（重做一侧优先），内容复制为字符串，因为引用离开原来的 piece tree 就没有意义。

## 扩展点

多数定制不需要继承。需要时，`EditorView` 的这些 `protected open` 方法可以重写：

- **绘制**：`drawText`（录制时每行一次，直接绘制时每帧，不要假设频率或保存跨行状态）、`drawLineNumber`（只在关闭行号栏时调用）、`drawLineBackground`、`drawSelectionBackground`、`drawMatchesBackground`、`drawComposingUnderline`、`drawBracketHighlight` / `drawBracketText` / `drawBracketUnderline`、`drawIndentGuide`、`drawFoldMarker`、`drawFoldPlaceholder`。
- **文本变化**（需要调用 `super`）：`beforeTextChanged`（缓冲区还是旧内容）、`onTextChanged`（每处改动一次，适合更新语法树）、`afterTextChanged`（整次编辑完成，适合重新解析）。内容以 `TextSlice` 传递，需要时再取文本，避免大段撤销在回调中复制。
- **其他**：`onBackgroundRelayoutStarted` / `Finished`、`onCopyResult`、`onMergeStarted` / `onMergeCommitted`（刷新撤销按钮）、`onModifiedChanged`、`onFoldMarkerClick`、`onFoldChanged`、`onZoomBegin` / `onZoom` / `onZoomEnd`。

**手柄**：`TextHandleDrawable` 是抽象基类，`DrawnTextHandle`（自绘，开始手柄在行上方）和 `ResourceTextHandle`（图片，按 Android 惯例都在行下方）是两种外观。触摸范围、首行翻转等都从手柄自己描述的「把手区域」推算，所以两种外观的交互完全一致；做第三种外观只需继承基类。

**行号栏**不是独立的 View：旁边放 View 就要逐帧跟随滚动、缩放、折行和键盘遮挡，总会慢一帧；由编辑器在同一次绘制中按同一份布局画出就不会错位。行号宽度只增不减，避免行数跨位数边界时来回跳动。

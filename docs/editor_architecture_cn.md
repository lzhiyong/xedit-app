# editor：设计与原理

> [editor](../editor/README_CN.md) 模块文档 · [English](editor_architecture.md)

## 线程规则

编辑器的状态属于主线程，但有几个操作会重新排版全文，大文件上要几秒。排版不能在主线程上做，否则应用会失去响应（ANR）。所以这几个方法在哪个线程调用，决定了排版在哪里做：

- **在后台线程调用**：就在这个线程上排版，排完才返回。适合要等它完成的场合，比如 `withContext(Dispatchers.Default) { editor.setWordwrap(true) }`。
- **在主线程调用**（点击事件里、`lifecycleScope.launch { }` 里，后者默认也在主线程）：排版交给编辑器自己的排版线程，调用立即返回。主线程上的多次调用按调用顺序依次执行。完成时间通过 `onDone` 回调得知，它在主线程执行；期间和缩放一样会调用 `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished`。

```kotlin
// 主线程：立即返回，排版在后台
editor.setWordwrap(true) { /* 排版完成，主线程 */ }

// 后台线程：排完才返回
lifecycleScope.launch(Dispatchers.Default) {
    editor.setWordwrap(true)
}

editor.isWordwrap()        // 当前使用的布局是否折行
```

| 方法 | 线程 | 说明 |
|---|---|---|
| `setTextSize` `setTypeface` `setWordwrap` | 任意 | 后台线程上同步完成；主线程上交给排版线程，立即返回，可带 `onDone` |
| `setBuffer` | 任意 | 同上；缩放进行中会被拒绝并返回 `false` |
| `saveSession` `restoreSession` | 主线程 | 不排版；`restoreSession` 要在 `setBuffer` 完成之后调用（后台线程上它返回之后，或在主线程调用时的 `onDone` 里） |
| `isWordwrap` `markSaved` `isModified` `invalidateStructure` | 任意 | `markSaved` 在主线程生效 |
| `getTextVersion` | 任意，建议主线程 | 只有在主线程取的版本号，写盘期间继续编辑时 `markSaved` 才能对上 |
| `find` | 建议后台 | 只读，结果用 `setSearchResults` 交回主线程 |
| 其余所有方法 | 主线程 | |

这些方法内部会把需要操作界面的部分自行切回主线程，调用方不用处理。

排版进行期间编辑器处于"忙"状态：

```kotlin
editor.isLayoutBusy()      // 正在重排，或缩放尚未结束
editor.isZoomInProgress()  // 仅缩放
```

忙的时候，输入、粘贴、撤销、`setText` 等所有改动文本的操作都会被拒绝，滚动和阅读不受影响。

这不是靠调用方自觉遵守的约定。编辑和重排经过同一把锁：重排读取全文时编辑进不来，编辑进行时别的线程上的重排会等它结束。所有改动文本的入口都经过这道门，没有别的路径。

有两种重排由用户操作触发，编辑器自己放到后台执行：捏合缩放结束后，以及折行模式下控件宽度改变后（旋转屏幕、分屏）。可以指定它们在哪个线程池上跑：

```kotlin
editor.setLayoutExecutor(Dispatchers.Default.asExecutor())
```

## 文本缓冲区

编辑器不关心文本是怎么存储的。它需要的全部操作都在 `EditorBuffer` 接口里（行、偏移量、范围、查找、应用编辑），`EditorDocument` 只面向这个接口工作。自带的实现是 `PieceTreeBuffer`，即 VS Code 所用的 piece tree。

```kotlin
editor.setBuffer(pieceTree)          // 传 piece tree，内部包装成 PieceTreeBuffer
editor.setBuffer(myBuffer)           // 传任何实现了 EditorBuffer 的对象
editor.getBuffer()                   // 当前的 EditorBuffer
editor.getTextBuffer()               // 当前的 piece tree；缓冲区不是 piece tree 时抛异常
```

要接入另一种结构（rope、gap buffer、测试用的简单字符串等），实现 `EditorBuffer` 即可。约定见接口注释：行号和列号从 1 起，偏移量从 0 起，范围含头不含尾；`applyEdits` 里各个编辑的范围都以编辑前的文本为准。

编辑器的所有编辑都经过 `applySliceEdits`：内容以 `TextSlice` 传入，每处改动报告它删除的内容（`SliceChange.removed`），撤销历史保存的就是这些。接口提供了基于 `applyEdits` 和字符串的默认实现，其他结构不实现它也能正常工作，只是大段内容会被复制。`PieceTreeBuffer` 直接使用 piece tree 的 `applySliceEdits`，大段内容以引用保存。

编辑会话（`saveSession` / `restoreSession`）不包含文本，所以不管文本存在哪种缓冲区里都可以使用。

## 架构

`EditorView` 本身是一层薄壳，只保留三件事：公开 API、给子类的扩展点、Android View 的生命周期。其余每项职责各有一个归属：

| 职责 | 类 | 包 |
|---|---|---|
| 缓冲区、查询、编辑管线 | `EditorDocument` | `model` |
| 撤销重做栈，连续输入的合并，保存点 | `EditHistory`、`UndoManager`、`UndoStep`、`HistoryChange` | `model` |
| 一个文件的编辑会话（光标、撤销历史、折叠） | `EditSession` | `model` |
| 文本版本、保存点的标记、会话的生成和恢复 | `SaveTracker` | `view` |
| 括号匹配、缩进线、折叠的接口与简单实现，折叠状态 | `BracketMatcher`、`IndentGuideProvider`、`FoldingRangeProvider`、`FoldingModel` | `structure` |
| 去掉被折叠行的布局 | `FoldedLayout` | `layout` |
| 括号匹配、缩进线、折叠的询问、缓存、折叠和绘制 | `CodeStructure` | `view` |
| 折叠标记和占位块的外观 | `FoldStyle`、`DefaultFoldStyle`、`DrawableFoldStyle` | `render` |
| 缩进的编辑，回车、Tab、Shift+Tab 的处理 | `IndentEdits`、`Indenter` | `input` |
| 制表位：Tab 的宽度、按制表位绘制 | `TabStops` | `layout` |
| 拖动手柄松手时撤回最后一步 | `TouchUpFilter` | `input` |
| 新选区从手指处展开的动画 | `SelectionReveal` | `input` |
| 搜索结果及其随编辑的平移 | `SearchResults` | `model` |
| 字体度量（带缓存） | `TextMetrics` | `layout` |
| 显示行与缓冲区行的映射 | `TextLayout`、`UnwrappedLayout`、`WordwrapLayout` | `layout` |
| 全量重排、线程、编辑与重排的互斥 | `LayoutCoordinator` | `layout` |
| 可见行循环、行缓存 | `TextRenderer` | `render` |
| 行背景、选区、行号 | `LineDecorationPainter` | `render` |
| 平滑滚动、惯性、越界光晕 | `ScrollController` | `input` |
| 滚动条滑块 | `ScrollbarController` | `input` |
| 光标、选区、手柄 | `SelectionController` | `input` |
| 按键沿文本移动光标（字符、词、行首行尾、文首文末） | `CaretNavigator`、`CaretMove` | `input` |
| 手柄外观（抽象基类与两种实现） | `TextHandleDrawable`、`DrawnTextHandle`、`ResourceTextHandle` | `input` |
| 选择时的边缘自动滚动 | `EdgeAutoScroller` | `input` |
| 光标外观（自己画的竖线） | `DrawnCursorDrawable` | `input` |
| 固定的行号栏 | `Gutter` | `render` |
| 删除键按住时的加速 | `BackspaceRepeat` | `input` |
| 触摸分发 | `GestureController` | `input` |
| 硬件键盘 | `KeyboardController` | `input` |
| 输入法组字 | `InputComposer`、`InputMethodBridge`、`EditorInputConnection` | `input` |
| 屏幕阅读器 | `EditorAccessibility` | `input` |
| 带样式的文字绘制 | `LineStyler`、`StyledTextPainter` | `render` |
| 高亮引擎的接口 | `SyntaxHighlighter` | `highlight` |
| 文本存储的接口与默认实现 | `EditorBuffer`、`PieceTreeBuffer` | `model` |
| 软键盘显示与遮挡 | `ImeController` | `input` |
| 剪贴板 | `EditorClipboard` | `input` |

依赖是单向的：`view` 依赖其余各包，其余包不依赖 `view`；`model` 不依赖任何界面类。

需要 View 配合的协作者各自声明一个窄接口 `Host`，全部汇总在 `EditorEnvironment` 里，由 `EditorView` 内部的私有适配器实现。这样协作者之间互不认识，内部回调也不会出现在 `EditorView` 的公开方法里。

### 两套坐标

- **View 坐标**：触摸事件使用，原点在控件左上角，包含内边距。
- **内容坐标**：其余一切使用，原点在文本左上角。

换算关系是 `内容 = View + 滚动量 − 内边距`。光标、手柄、滚动条滑块都按内容坐标定位，并在绘制文本的同一个画布变换下绘制。

### 两种布局

| | 不折行 | 折行 |
|---|---|---|
| 一个显示行 | 就是一个缓冲区行 | 缓冲区行的一段 |
| 存储 | 每行一个 `Int` 宽度 | 每个显示行一个对象 |
| 字号变化 | 可按比例换算，立即生效 | 必须重排 |
| 单次编辑 | 只测量改动的行 | 只重排改动的行 |

两者的全量重排都是在新数据里排完再整体替换，被取消的重排不留痕迹。

有折叠时，编辑器使用的是包在两者之一外面的 `FoldedLayout`：被折叠的行没有显示行，其余都是里面那个布局的。它只记录被隐藏的显示行在哪里，在里面的布局或折叠变化之后（按 `TextLayout.version` 判断）才重新计算；编辑和重排仍然只发生在里面的布局上。

## 限制与默认值

| 项目 | 默认 | 修改方式 |
|---|---|---|
| 撤销步数上限 | 1000 步 | 子类里 `undoManager.maxSteps` |
| 撤销占用上限 | 400 万（字符串字符 + 每段引用 16 + 每个改动 32） | 子类里 `undoManager.maxChars` |
| 参与合并的改动大小 | 16K 字符以内 | `HistoryChange.MERGE_LIMIT` |
| 编辑会话保留的历史 | 200 步、100 万字符 | `setSessionLimits` |
| 括号匹配、缩进线、代码折叠 | 关闭 | `setBracketMatcher`、`setIndentGuideProvider`、`setFoldingRangeProvider` |
| 连续输入合并间隔 | 500 毫秒 | `EditHistory.DEFAULT_MERGE_DELAY_MS` |
| 单次复制上限 | 40 万字符 | `EditorClipboard.MAX_TEXT_LENGTH` |
| 进入行缓存的行长上限 | 4096 字符 | `TextRenderer` 内部常量 |
| 滚动条最小触摸尺寸 | 48dp | `ScrollbarController` 内部 |
| 滚动条跟随滚动滑入滑出 | 滚动 32dp 滑过滑块的整个厚度：本方向领先时滑入，另一方向领先时滑出，手指停下就停在原处 | `ScrollbarController` 内部 |
| 滚动条停止滚动后淡出 | 2 秒后，用 300 毫秒从当前位置滑出 | `ScrollbarController` 内部 |
| 选区手柄滚动摆动 | 开启，最大 12dp | `setHandleSwayEnabled`、`setHandleSwayAmplitude` |
| 边缘自动滚动速度 | 每秒 20 行 | `setAutoScrollSpeed` |
| 按住删除键的按词删除速度 | 每秒 10 个词，按住 0.9 秒后开始 | `setFastDeleteSpeed` |
| 手柄外观 | 编辑器自己画 | `setHandleDrawables`、`useSystemHandles` |
| 光标外观 | 编辑器自己画，宽 2dp | `setCursorDrawable`、`setCursorWidth`、`useSystemCursor` |
| 行号栏 | 关闭（行号画在文本行里） | `setGutterEnabled` |
| 行号 | 显示 | `setLineNumbersVisible` |
| 括号匹配的标示 | 高亮、加粗、下划线 | `setBracketMatchStyle` |
| 一级缩进 | 4 个空格 | `setIndentUnit` |
| 点击折叠占位块展开 | 开启 | `setFoldPlaceholderClickable` |
| 撤销历史引用的存储上限 | 6400 万字符 | 子类里 `undoManager.maxReferencedChars` |
| 自动缩进 | 开启 | `setAutoIndent` |
| 自动配对 | 无 | `setAutoPairs` |
| 制表位间隔 | 4 个空格宽 | `setTabSize` |
| 折叠的外观 | VS Code 风格（箭头、三个点） | `setFoldStyle` |
| 行号栏与文本的间距 | 8dp | `setGutterTextGap` |

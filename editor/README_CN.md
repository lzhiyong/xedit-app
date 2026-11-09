# editor

[English](README.md) | 中文 · [设计](DESIGN_CN.md)

面向代码的 Android 编辑控件 `EditorView`（包名 `x.github.module.editor`），基于 [piecetable](../piecetable/README_CN.md)，为几十 MB、上百万行的大文件设计。

- 大文件：行缓存绘制、增量布局、全量重排放在后台
- 自动折行（可随时切换）、代码折叠、缩进线、括号匹配、自动配对
- 撤销重做：连续输入合并为一步，大段内容以引用保存（删掉几十 MB 再撤销也不复制）；修改状态精确（撤销回保存点即为未修改）
- 编辑会话：光标和撤销历史可随文件保存，下次打开时恢复
- 语法高亮以接口接入，不需要继承
- 光标与选区手柄（自绘或图片）、放大镜、固定行号栏、搜索高亮、捏合缩放
- 输入法组字、硬件键盘快捷键、基础无障碍

代码最低支持 API 24；行缓存绘制需要 API 29，更低版本自动改用直接绘制。

## 快速开始

```xml
<x.github.module.editor.view.EditorView
    android:id="@+id/editor"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:textSize="14sp"
    android:fontFamily="monospace"
    app:wordwrap="false" />
```

布局属性：`textSize`、`textColor`、`textColorHighlight`（选区）、`fontFamily`、`typeface`、`textStyle`、`text`，以及 `app:editable`、`app:wordwrap`、`app:lineNumberColor`、`app:currentLineColor`、`app:searchMatchColor`、`app:currentMatchColor`。

**加载文件**：在后台线程按字符分块读入（不要按字节分块再解码，多字节字符会被切坏）：

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    val builder = PieceTreeTextBufferBuilder()
    InputStreamReader(input, Charsets.UTF_8).use { reader ->
        val chunk = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(chunk)
            if (n == -1) break
            builder.acceptChunk(String(chunk, 0, n))
        }
    }
    editor.setBuffer(builder.build())   // 后台线程上调用：返回时布局已完成
}
// 小段文本可以直接 editor.setText("...")
```

**编辑与搜索**：

```kotlin
editor.insert("text"); editor.delete(); editor.replace("new", range)
editor.undo(); editor.redo(); editor.canUndo()

val matches = editor.find("keyword")      // 耗时，放后台
editor.setSearchResults(matches)          // 高亮全部匹配，之后随编辑自动平移
editor.gotoNextMatch()
editor.replaceAll("new", editor.getSearchResults())
```

**保存与修改状态**：在主线程同时取文本和版本号，写完再标记：

```kotlin
val version = editor.getTextVersion()
val text = editor.getText()
lifecycleScope.launch(Dispatchers.IO) {
    writeFile(uri, text)
    editor.markSaved(version)              // 写盘期间用户继续输入也不会算错
}
editor.setOnModifiedChangeListener { modified -> updateTitle(modified) }
```

**编辑会话**（光标、撤销历史、折叠）：会话不含文本，由应用按自己的方式存取；恢复前会用应用提供的内容哈希核对文本是否还是原来那份。

```kotlin
val session = editor.saveSession(contentHash)          // 主线程；可能为 null（加载中、保存点已丢失）
// ... 序列化后保存（EditSession 支持 kotlinx.serialization）

// 下次打开：setBuffer 完成之后，在主线程
when (editor.restoreSession(session, contentHash)) {
    EditSession.RestoreResult.CONTENT_MISMATCH -> deleteSessionFile()   // 文件已被外部修改
    else -> {}   // RESTORED / TEXT_MODIFIED / BUSY（稍后再试）
}
```

屏幕旋转时控件自己保存字号、折行、光标、滚动位置和折叠（需要设置 `android:id`），文本仍由应用重新加载。

## 线程规则

编辑器状态属于主线程，但下面这几个方法会重排全文，大文件上要几秒，所以**调用线程决定排版在哪里做**：

| 方法 | 在后台线程调用 | 在主线程调用 |
| --- | --- | --- |
| `setBuffer` `setTextSize` `setTypeface` `setWordwrap` | 就地排版，排完才返回 | 交给编辑器的排版线程，立即返回；可带 `onDone` 回调（主线程） |

- 排版期间（`isLayoutBusy()`）所有改动文本的操作都会被拒绝，滚动和阅读不受影响。可以在 `onBackgroundRelayoutStarted` / `Finished` 中显示进度。
- `find` 建议在后台；`markSaved`、`isModified`、`invalidateStructure` 可在任意线程；`saveSession`、`restoreSession` 和其余方法只能在主线程。

## 语法高亮与代码结构

高亮引擎实现 `SyntaxHighlighter` 接口即可，不限定技术（tree-sitter、TextMate、正则、语言服务器）：

```kotlin
editor.setSyntaxHighlighter(object : SyntaxHighlighter {
    // 返回一行的样式（ForegroundColorSpan、BackgroundColorSpan、StyleSpan、UnderlineSpan）；null 按普通文本画
    override fun styleLine(text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Spanned? = ...
    // 可选：onAttached、onTextChanged（增量更新语法树）、onEditFinished（适合重新解析）、onTextReplaced
})
```

- 所有回调都在主线程，`styleLine` 在绘制过程中调用，必须很快。解析放到自己的线程，完成后调用 `host.invalidateStyles(startLine, endLine)`（任意线程）只重绘变化的行。
- 只需要着色时可以用更轻的 `setLineStyler { }`。

括号匹配、缩进线、折叠同样由接口提供，默认关闭：

```kotlin
editor.setBracketMatcher(PlainBracketMatcher())                // 不识别字符串和注释
editor.setIndentGuideProvider(IndentationGuideProvider(4))     // 按缩进
editor.setFoldingRangeProvider(IndentationFoldingProvider(4))  // 按缩进
editor.invalidateStructure()                                   // 后台解析完成后调用，任意线程
```

一个 tree-sitter 引擎可以同时实现 `SyntaxHighlighter` 和这三个接口，用同一棵语法树（app 模块的 `TreeSitterStructure` 就是这样做的）。折叠相关：`toggleFold(line)`、`foldAll()`、`unfoldAll()`、`setOnFoldChangeListener`；外观用 `setFoldStyle` 或 `setFoldMarkerDrawables` 替换。

## 常用配置

| 项目 | 默认 | 修改 |
| --- | --- | --- |
| 一级缩进 / Tab 键输入 | 4 个空格 | `setIndentUnit("\t")` 改为制表符 |
| 制表位宽度 | 4 | `setTabSize` |
| 自动缩进 | 开 | `setAutoIndent` |
| 自动配对 | 关 | `setAutoPairs(AutoPair.DEFAULTS)`，规则同 nvim-autopairs；`setAutoPairFilter` 排除字符串、注释 |
| 行号栏（固定，不随横向滚动） | 关 | `setGutterEnabled`；`setGutterDecoration` 加断点等标记，`setOnGutterClickListener` |
| 行号 | 显示 | `setLineNumbersVisible` |
| 括号标示 | 高亮 + 加粗 + 下划线 | `setBracketMatchStyle` |
| 手柄外观 | 自绘 | `setHandleDrawables`、`useSystemHandles`、`setHandleColor` |
| 光标外观 | 自绘，宽 2dp | `setCursorDrawable`、`setCursorWidth`、`useSystemCursor` |
| 滚动时手柄摆动 | 开，最大 12dp | `setHandleSwayEnabled`、`setHandleSwayAmplitude` |
| 选择时边缘自动滚动 | 每秒 20 行 | `setAutoScrollSpeed`，0 关闭 |
| 按住删除键时按词删除 | 0.9 秒后每秒 10 词 | `setFastDeleteSpeed`，0 关闭 |
| 编辑会话保留的历史 | 200 步、100 万字符 | `setSessionLimits` |
| 撤销步数 / 占用 / 引用的存储 | 1000 步 / 400 万 / 6400 万字符 | 子类中 `undoManager.maxSteps` / `maxChars` / `maxReferencedChars` |
| 连续输入合并间隔 | 500 毫秒 | `EditHistory.DEFAULT_MERGE_DELAY_MS` |
| 单次复制上限 | 40 万字符 | `EditorClipboard.MAX_TEXT_LENGTH` |

需要改变绘制方式或在编辑流程中插入逻辑时，可以继承 `EditorView` 重写 `protected open` 方法（`drawText`、`drawSelectionBackground`、`drawFoldMarker`、`afterTextChanged` 等），见[设计](DESIGN_CN.md#扩展点)。

## 限制

**性能**

- 一次插入不到 2 万行的编辑仍在主线程测量，接近 2 万行时可能停顿一两百毫秒（阈值 `LARGE_EDIT_LINES`）。
- 重排期间（改字号、换字体、切换折行、大块撤销之后）编辑被锁住，大文件上几秒内无法输入；应用应显示进度。
- 把 piece tree 当 `CharSequence` 逐字符访问很慢，不要直接对它跑 `Regex`，用 `find()` 或快照。
- piece tree 不会自动压缩，长时间编辑后需要应用调用 `compact()`。撤销历史引用的存储在步骤被丢弃前不会释放（上限约 128 MB）。
- 含 Tab 的不折行超长行每帧整行绘制；多行搜索会复制搜索区间涉及的所有行。

**功能**

- 不支持从右到左文字和复杂排版（阿拉伯语、希伯来语、部分印度文字）。
- 不支持多光标。
- 无障碍是基础级别：只提供屏幕上可见的文字（最多 4000 字符），不能按词、按行浏览。
- 折行后，后续显示行中的 Tab 按显示行起点计算制表位，与不折行时对不齐。
- `PlainBracketMatcher` 不认识字符串和注释。
- 文本的保存和加载由应用负责，实例状态和编辑会话都不含文本。

## 测试

```bash
./gradlew :editor:testDebugUnitTest          # JVM：模型、撤销、布局、折叠、输入法、自动配对等
./gradlew :editor:connectedDebugAndroidTest  # 设备：EditorViewTest
```

两个布局测试各做一万两千次随机编辑，把增量更新与全量重排逐行对比。触摸手势、滚动、软键盘遮挡没有自动化测试，依赖手动验证。

## 许可

Apache License 2.0

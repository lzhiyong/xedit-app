# editor：使用指南

> [editor](../editor/README_CN.md) 模块文档 · [English](editor_usage.md)

## 快速开始

### 布局

```xml
<x.github.module.editor.view.EditorView
    android:id="@+id/editor"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="8dp"
    android:textSize="14sp"
    android:textColor="#D4D4D4"
    android:fontFamily="monospace"
    app:lineNumberColor="#858585"
    app:wordwrap="false" />
```

支持的属性：

| 属性 | 作用 |
|---|---|
| `android:textSize` | 字号 |
| `android:textColor` | 文字颜色 |
| `android:textColorHighlight` | 选区背景 |
| `android:fontFamily` | 字体名或 `@font/` 资源 |
| `android:typeface` | `sans` / `serif` / `monospace`，未设置 `fontFamily` 时生效 |
| `android:textStyle` | `bold` / `italic` |
| `android:text` | 初始文本 |
| `app:editable` | `false` 为只读 |
| `app:wordwrap` | 自动折行 |
| `app:lineNumberColor` | 行号颜色 |
| `app:currentLineColor` | 光标所在行背景 |
| `app:searchMatchColor` | 搜索匹配背景 |
| `app:currentMatchColor` | 当前匹配项背景 |

### 加载文本

```kotlin
// 小段文本，主线程即可
editor.setText("fun main() {}")

// 文件：在后台线程分块读入、构建缓冲区并加载
lifecycleScope.launch(Dispatchers.IO) {
    val builder = PieceTreeTextBufferBuilder()
    InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
        val chunk = CharArray(64 * 1024)
        while (true) {
            val count = reader.read(chunk)
            if (count == -1) break
            builder.acceptChunk(String(chunk, 0, count))
        }
    }
    editor.setBuffer(builder.build())    // 返回时布局已完成
}
```

分块读取时要用 `Reader` 按字符读，不要按字节读了再逐块转成字符串：一个多字节字符（比如汉字）可能正好被切在两块之间，按字节分块解码会把它变成乱码。

`setBuffer` 换上缓冲区后会立即显示新文本，布局在调用线程上继续进行。布局完成前可以滚动阅读，不能编辑。

### 编辑

```kotlin
editor.insert("text")                  // 在光标处插入，或替换选区
editor.delete()                        // 退格，或删除选区
editor.replace("new", range)
editor.undo()
editor.redo()
editor.canUndo()                       // 包含刚输入、尚未合并入栈的内容

editor.setIndentUnit("    ")             // 一级缩进：4 个空格（默认）、其他数量的空格，或 "\t"
editor.setAutoIndent(true)               // 回车沿用当前行的缩进（默认开启）
editor.setTabSize(4)                     // 制表位间隔几个空格宽（默认 4），会重新排版
editor.indentLines()                     // 选中的行各加一级缩进（Tab 键）
editor.outdentLines()                    // 选中的行各减一级缩进（Shift+Tab）
```

- **Tab 输入空格**：Tab 键（键盘的，以及输入法以 `"\t"` 提交的）输入一级缩进，默认是 4 个空格，而不是制表符；退格一次删一个空格。文本里原有的制表符保持不变，按制表位显示。要输入制表符，用 `setIndentUnit("\t")`。
- **缩进**：Tab 在选区跨多行时缩进这些行，否则输入一级缩进；Shift+Tab 减少一级缩进。行缩进和减少缩进各算一个撤销步骤，选区保持在原来的文字上。回车时，新行沿用当前行在光标之前的那部分缩进。
- **不拆开字符**：编辑区间的一端落在代理对（emoji 和部分汉字由两个 char 组成）中间时，区间会扩到整个字符；在这种位置插入，文字插到字符前面。撤销和重做按原区间重放，不受影响。
- **制表位**：Tab 一直延伸到下一个制表位，制表位每隔 `setTabSize` 个空格宽一个，从显示行的起点算起。测量、绘制、光标、选区、点击定位、折行都按同样的制表位计算。
- **死键**：先按重音符号再按字母，会组合成带重音的字母（´ 加 e 得到 é）；重音符号后按空格，得到重音符号本身。

### 自动配对

```kotlin
editor.setAutoPairs(AutoPair.DEFAULTS)            // () [] {} "" '' 和反引号；空列表表示关闭（默认）
editor.setAutoPairs(AutoPair.parse("() [] <>"))   // 用文本写配对，每对两个字符
editor.setAutoPairFilter { pair, offset -> ... }  // 在某些位置不配对（字符串、注释里）
editor.setAutoPairWrapSelection(true)             // 选中文字后输入左符号，用这一对把它包起来（默认开启）
editor.typeText("(")                              // 和键盘输入一样，可能补全配对
```

规则和 nvim-autopairs 一致：输入左符号时同时输入右符号，光标在中间；下一个字符是字母、数字或 `%'["`.$` 之一时不补全；引号紧跟在字母后面时不补全（`don't`）；这一行的右括号已经比左括号多时不补全（`|a)`）。右符号的下一个字符正好是同一个符号时，直接跳过它；在空的一对中间退格，两个一起删掉；在一对括号中间回车（`{|}`），右括号单独放到一行，光标在中间缩进好的一行上。硬件键盘、输入法在非组合状态下提交的单个字符和 `typeText` 都会经过这套规则；`insert` 原样插入，不经过。

### 撤销与重做

连续输入（间隔不超过 500 毫秒）合并为一步。只有小的改动参与合并，粘贴一大段、删除一整块这类大改动立即单独成为一步。

**大块撤销不卡主线程**：一次编辑插入 2 万行以上（例如撤销"删除半个大文件"、粘贴整个文件）时，插入的行先得到粗略的布局：不折行时按字符数估计宽度，折行时每行先占一个显示行。编辑完成后，在后台按正常方式重新排版，和缩放时一样：期间编辑被拒绝，应用会收到 `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished`，可以显示进度条。不到 2 万行的编辑仍在主线程上直接测量。折行模式下的增量更新也改成了一次性替换受影响的显示行，大块删除和插入的耗时与行数成线性关系，不再是平方关系。

撤销历史里的内容是 `TextSlice`（piecetable 模块）：短的内容存为字符串，16K 字符以上的内容存为对 piece tree 存储的引用，不复制文本。所以在几十 MB 的文件上全选删除，撤销栈里只有几个引用；撤销时这些引用直接插回去，同样不复制。大段粘贴进来的字符串在第一次撤销后也会换成引用。

需要注意：

- 引用只对它来自的那个 piece tree 有效。加载另一个文本时撤销历史会被清空，所以正常使用碰不到这个问题。
- 引用会让它指向的存储一直保留，`compact()` 之后旧的存储块也是如此，直到相关的撤销步骤被丢弃。
- 撤销栈的占用上限按"字符串的字符数 + 每段引用 16 + 每个改动 32"计算。全部替换 10 万处这类操作，对象数量也会计入。
- 编辑器之外的 `EditorBuffer` 实现没有引用，默认实现会照常复制文本，结果仍然正确。

### 搜索

```kotlin
val matches = editor.find("keyword")   // 耗时，建议在后台线程
editor.setSearchResults(matches)       // 高亮全部匹配
editor.gotoNextMatch()                 // 跳到下一个并高亮为当前项
editor.gotoPreviousMatch()
val index = editor.getMatchResultIndex()   // 当前是第几个，从 0 起
editor.replace("new", editor.getSearchResults()[index])
editor.replaceAll("new", editor.getSearchResults())
editor.clearSearchResults()
```

匹配结果由编辑器保管一份拷贝。文本被编辑后，位置在编辑之后的匹配会随文本平移，被编辑碰到的匹配自动移除。

### 修改状态与保存点

```kotlin
// 写盘：在主线程取文本的同时取版本号，写完再标记
val version = editor.getTextVersion()
val text = editor.getText()
lifecycleScope.launch(Dispatchers.IO) {
    val hash = writeFileAndHash(uri, text)     // 应用自己写盘，顺带算哈希
    editor.markSaved(version)
}

editor.isModified()    // 当前文本是否和保存的文本不同
editor.setOnModifiedChangeListener { modified -> updateTitle(modified) }
```

`setBuffer` 和 `setText` 加载的文本算作已保存。

`isModified` 跟随撤销历史，而不是按编辑次数计数：

- 撤销回到保存时的内容，为未修改；撤销到保存点之前、再重做回来，也是未修改。
- 在一次连续输入里打一个字再删掉，也是未修改。
- 撤销到保存点之前后又输入了新内容，保存的文本就再也回不去了，之后一直算作已修改，直到下次保存。
- 最旧的步骤因为超过上限被丢弃、保存点随之丢失时，同样如此。
- 不可撤销的编辑（`applyEdits(..., computeUndoEdits = false)`）之后，编辑器无法再知道保存的文本在哪里，也一直算作已修改，直到下次保存。

`getTextVersion` 要在主线程调用，和取文本放在一起。它会记下当时撤销历史的位置，并结束当前的连续输入（保存点必须落在两步之间）。这样写盘期间用户继续输入，`markSaved(version)` 仍能找到被写下的那份文本。在其他线程取的版本号，只有在写盘期间没有编辑的情况下才有效；否则保守处理，算作已修改。

`markSaved` 可以在任何线程调用，在主线程生效。修改状态变化时，`setOnModifiedChangeListener` 设置的监听器会在主线程收到通知，子类也可以重写 `onModifiedChanged`。

### 编辑会话

关闭一个文件时可以把它的编辑会话存下来，下次打开同一个文件时放回去：光标位置、撤销和重做历史。

**会话总是属于保存的文本，也就是磁盘上的那份。** 有未保存的修改时也可以保存会话：保存点之后的修改会转进会话的重做栈。下次打开文件时，文本是磁盘上的版本，按重做可以把没保存的修改一步步找回来。反过来，如果当时撤销到了保存点之前，那些被撤销的步骤会放回撤销栈。

```kotlin
// 保存：随时可以，比如应用切到后台时；主线程取会话，后台线程序列化
val session = editor.saveSession(contentHash)          // contentHash：磁盘上文件的哈希
if (session != null) {
    lifecycleScope.launch(Dispatchers.IO) {
        sessionFile(uri).writeText(Json.encodeToString(session))
    }
}

// 恢复：后台加载文本，回到主线程放回会话
lifecycleScope.launch(Dispatchers.IO) {
    editor.setBuffer(buffer)                           // 返回时布局已完成
    val session = runCatching {
        Json.decodeFromString<EditSession>(sessionFile(uri).readText())
    }.getOrNull() ?: return@launch
    withContext(Dispatchers.Main) {
        when (editor.restoreSession(session, contentHash)) {
            EditSession.RestoreResult.CONTENT_MISMATCH -> sessionFile(uri).delete()   // 永远不会匹配了
            else -> {}
        }
    }
}
```

`EditSession` 不包含文本，也不包含它属于哪个文件。文本可能有几十 MB，本来就在磁盘上，由应用重新加载；文件怎么标识（uri、路径）是应用的事，会话按应用自己的方式存取即可，比如以 uri 的哈希作文件名。

撤销步骤是固定位置上的编辑，用在别的文本上会把它改乱。所以会话带着认出自己那段文本的信息，`restoreSession` 恢复前会逐项核对：

| 核对项 | 由谁提供 | 能发现 |
|---|---|---|
| `contentHash` | 应用计算，编辑器只保存和比较 | 文件被外部改过 |
| `textLength`、`lineCount` | 编辑器在保存时根据保存的文本推算 | 同样的字节被读成了不同的文本（换了编码、换行符处理不同），或者哈希算错了对象 |
| 加载后是否编辑过 | 编辑器的修改状态 | 当前文本已经不是会话所属的文本 |

`restoreSession` 返回的结果：

| 结果 | 含义 | 应用怎么做 |
|---|---|---|
| `RESTORED` | 历史和光标已恢复 | 无 |
| `CONTENT_MISMATCH` | 会话属于别的文本，以后也不会匹配 | 可以删掉会话文件 |
| `TEXT_MODIFIED` | 文本加载后被编辑过 | 跳过，会话文件保留 |
| `BUSY` | 正在加载文本、排版或缩放，什么都没核对 | 稍后再试 |

- 会话只保留离保存点最近的一部分历史：默认最多 200 步、100 万字符（新旧内容合计），重做一侧（未保存的修改）优先。某一步放不下时，这一侧到此为止，因为更远的步骤要经过它才能到达。用 `setSessionLimits(maxSteps, maxChars)` 调整。
- 会话里的内容是字符串：引用离开它所在的 piece tree 就没有意义。保留的步骤在主线程上复制成字符串，上面的限额保证这一步很快。
- 以下情况 `saveSession` 返回 `null`：正在加载文本；保存点已经丢失（见上一节）。这时先写盘，再取会话。
- 光标在会话里以偏移量保存，指向保存的文本：有未保存的修改时，是第一处修改的位置。恢复后光标限制在文本范围内，并滚动到屏幕中央。
- `saveSession` 和 `restoreSession` 都只能在主线程调用，否则抛出异常。`restoreSession` 要在 `setBuffer` 完成之后调用；在那之前调用会得到 `BUSY`。
- 哈希算法由应用决定（比如 SHA-256），保存和恢复时用同一种即可。在写盘或读盘时顺带计算，不需要额外读一遍文件。
- `EditSession` 可序列化（kotlinx.serialization），持有两个栈的拷贝，之后的编辑不会影响它。

### 屏幕旋转

控件自己会保存和恢复属于它的状态（需要给控件设置 `android:id`）：捏合缩放后的字号、是否折行、光标和滚动位置。文本本身不在其中，仍由应用重新加载。重新加载的如果是同一份文本，光标和滚动位置会自动回到原处；加载了别的文本则忽略。

## 语法高亮

高亮引擎通过 `SyntaxHighlighter` 接口接入，不需要继承 `EditorView`，也不限定用哪种技术（tree-sitter、TextMate 规则、正则、语言服务器都可以）：

```kotlin
class MyHighlighter : SyntaxHighlighter {
    // 必须实现：返回一行的样式，位置以 text 内的偏移量表示；返回 null 表示按普通文本画
    override fun styleLine(text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Spanned? = ...

    // 以下按需实现，默认什么都不做
    override fun onAttached(host: SyntaxHighlighter.Host) {}          // 接入编辑器；host.buffer 是当前文本
    override fun onTextReplaced(buffer: EditorBuffer) {}                // 整个文本被替换（加载了另一个文件）
    override fun onTextChanged(change: EditorDocument.AppliedChange) {}   // 一处改动已写入
    override fun onEditFinished() {}                                  // 一次编辑完成，适合重新解析
    override fun onDetached() {}
}

editor.setSyntaxHighlighter(MyHighlighter())
```

`styleLine` 返回 `android.text.Spanned`，支持 `ForegroundColorSpan`、`BackgroundColorSpan`、`StyleSpan`（粗体、斜体）和 `UnderlineSpan`。

如果要给整行加背景（比如 Markdown 的代码块），可以重写 `lineBackground(bufferLine): Int?`。背景从文本起点一直画到视图右边缘，位于当前行、选区和搜索匹配的下面。每一帧都会为每个可见行调用它，所以只能做很快的查表。

所有方法都在主线程调用，`styleLine` 发生在绘制过程中，必须快。耗时的工作（解析大文件）放到自己的线程上做，期间用已有的结果作答，完成后调用 `host.invalidateStyles()` 重画，这个方法可以在任何线程调用。如果知道是哪些行变了（比如增量解析后 tree-sitter 的 changed ranges），改用 `host.invalidateStyles(startLine, endLine)`：只有这些行重新着色，其他行继续用已经录制好的内容。编辑之后，编辑器自己也只重新录制被编辑的行；增删了行时，从第一处编辑的行往下全部重新录制。

`onTextChanged` 收到的 `AppliedChange` 只带位置和形状（插入了几行、多长），插入的内容在 `content` 里，是一个 `TextSlice`。需要文本时调用 `insertedText()`：对普通输入不需要复制；对大段撤销或重做会复制出整段内容，没有必要时不要调用。tree-sitter 的增量解析只需要位置，用不到它。

同一棵语法树往往也能回答括号匹配、缩进线和折叠的问题，见下一节。

只需要着色、不需要跟踪文本变化时，可以用更轻的 `editor.setLineStyler { ... }`。另外任何对象都可以用 `editor.addEditCallback(...)` 监听编辑的三个阶段。

## 代码结构：括号匹配、缩进线、折叠

哪些括号成对、缩进线画在哪里、哪里可以折叠，取决于语言，由接口的实现来回答：tree-sitter、语言服务器，或者 `structure` 包里只看文本的简单实现。编辑器负责询问、缓存、绘制，以及真正把代码折叠起来。

```kotlin
editor.setBracketMatcher(PlainBracketMatcher())                // 括号匹配，默认关闭
editor.setIndentGuideProvider(IndentationGuideProvider(4))     // 缩进线，默认关闭
editor.setFoldingRangeProvider(IndentationFoldingProvider(4))  // 代码折叠，默认关闭

editor.jumpToMatchingBracket()              // 光标跳到配对的括号
editor.getBracketPair()                     // 光标处的一对括号
editor.invalidateStructure()                // 引擎解析完成后调用，任意线程
```

| 接口 | 回答什么 | 何时被询问 | 自带的实现 |
|---|---|---|---|
| `BracketMatcher` | 光标前后的括号和它的配对 | 光标移动或文本变化后 | `PlainBracketMatcher`：逐行扫描，不认识字符串和注释，可重写 `isBracket` 排除它们 |
| `IndentGuideProvider` | 屏幕附近各行的缩进线 | 文本变化、或屏幕移到另一个 64 行的区块时 | `IndentationGuideProvider`：按缩进计算，空行取上下相邻非空行的较小值 |
| `FoldingRangeProvider` | 从屏幕附近各行开始的可折叠范围 | 同上 | `IndentationFoldingProvider`：下一行缩进更深的行开始一个范围 |

三个接口都在主线程调用，结果由编辑器缓存：文本、光标、所问的行都没变时不会重复询问。使用后台解析的引擎先用已有的结果作答，解析完成后调用 `invalidateStructure()`（或高亮引擎里的 `host.invalidateStructure()`）。一个 tree-sitter 引擎可以同时实现 `SyntaxHighlighter` 和这三个接口，用同一棵语法树服务全部。

### 括号匹配

光标前后的括号和它的配对会被标出来，默认三种方式都用：

- **高亮**：背景填上 `setBracketMatchColor` 的颜色。
- **加粗**：在文字上面把这个括号用它原来的颜色（来自高亮器）再画一遍粗体。粗体是对同一个字形加粗，宽度不变，正好盖住原来的字形，所以不需要重录行缓存。
- **下划线**：括号下面一条线，比普通下划线稍粗，颜色用 `setBracketUnderlineColor`（null 取文字颜色）。

```kotlin
editor.setBracketMatchStyle(highlight = true, bold = true, underline = false)   // 只要高亮和加粗
editor.setBracketUnderlineColor(Color.RED)
```

三部分分别由 `drawBracketHighlight`（背景）、`drawBracketText`（文字）、`drawBracketUnderline`（下划线）绘制，都可以在子类里重写。

### 缩进线

缩进线由 `IndentGuide(startLine, endLine, column, anchorLine)` 描述：它从 `startLine` 画到 `endLine`，横坐标取 `anchorLine` 上第 `column` 列开始的位置，整条线笔直。引擎知道代码块时，可以把它锚定在开启代码块的那一行、第一个字符的位置。光标所在的最内层代码块的那条线会加亮。缩进线画在行缓存之外，光标移动、加亮的线变了，不需要重录行缓存。

### 代码折叠

设置了 `FoldingRangeProvider` 后，每个可折叠范围的起始行旁边会出现折叠标记：开着时箭头向下，折叠后向右。行号栏开着时，标记在行号栏右侧单独的一列；关着时，标记画在文本行里的行号右边（行号隐藏时也是这个位置）。点击标记折叠或展开；折叠后起始行末尾会出现一个 "⋯" 占位块，点击它也会展开。

点击标记的反馈和 Material 3 的图标按钮一样，用的是编辑器已经依赖的 Material Components 库：

- **水波纹**：从标记处扩散再淡出的圆形水波纹（无边界的 `RippleDrawable`），颜色取主题的 `colorOnSurfaceVariant`，按 MD3 按下状态的不透明度（10%）叠加，由 `MaterialColors` 解析；主题不是 Material 主题时退回行号颜色。
- **箭头转动**：箭头转到新的方向，时长和缓动取主题的 `motionDurationMedium2` 和 `motionEasingStandardInterpolator`；主题没有定义时用 MD3 的值（300 毫秒，cubic-bezier(0.2, 0, 0, 1)）。点击占位块展开时，箭头同样会转回来。系统关闭了动画时，箭头直接到位。

重写 `drawFoldMarker` 的子类自己决定画什么；水波纹不受影响。

**外观**：默认的 `DefaultFoldStyle` 按 VS Code 的样子绘制，标记是箭头，占位块是浅色圆角底上的三个点。要换外观，有三种方式：

```kotlin
// 用 drawable：展开和折叠两个标记（转动时交叉淡入淡出），默认按标记颜色着色
editor.setFoldMarkerDrawables(expandedIcon, foldedIcon)
editor.setFoldPlaceholderDrawable(ellipsisIcon)     // 占位块；null 回到三个点

// 一次给全，可以不着色、保留 drawable 自己的颜色
editor.setFoldStyle(DrawableFoldStyle(expandedIcon, foldedIcon, ellipsisIcon, tint = false))

// 完全自己画：实现 FoldStyle 的 drawMarker、placeholderWidth、drawPlaceholder
editor.setFoldStyle(object : FoldStyle { ... })
```

标记 drawable 画在标记位置的正方形里，居中；占位块 drawable 的高度等于行高，宽度按它的宽高比计算。子类也可以重写 `drawFoldMarker` 和 `drawFoldPlaceholder`。

```kotlin
editor.toggleFold(line)                     // 折叠或展开从这一行开始的范围
editor.fold(FoldingRange(3, 10))            // 折叠：第 4 到 10 行隐藏，第 3 行留着
editor.unfold(3)
editor.foldAll()                            // 整个文本里提供者知道的所有范围（会询问全部行）
editor.unfoldAll()
editor.isFolded(3)                          // 从第 3 行开始的范围是否折叠着
editor.isLineHidden(5)                      // 第 5 行是否藏在折叠里
editor.getFoldedRanges()
editor.setOnFoldChangeListener { folded -> /* 折叠或展开之后，包括编辑引起的 */ }
editor.setOnFoldMarkerClickListener { range -> false }   // 先于默认处理；返回 true 表示已处理
```

折叠在布局层实现：`FoldedLayout` 包在当前布局外面，把被折叠的行从行序列里去掉。渲染、选区、行号栏、滚动、上下方向键都按行来工作，所以都自动跳过被折叠的行，不折行和折行模式都适用。

- **编辑**：折叠下方的编辑不影响它；上方的编辑让它跟着行移动；只改起始行本身（不增减行）时保留；伸进被折叠的行的编辑（撤销、全部替换）会把它展开。
- **光标**：折叠时，在被折叠行里的光标或选区端点会移到起始行末尾。光标因为搜索、跳转、方向键或撤销进入被折叠的行时，对应的折叠在光标移动的同时展开，光标滚到可见处。
- **占位块是否可点击**：默认点击占位块会展开。`setFoldPlaceholderClickable(false)` 之后和 VS Code 一样，点击占位块等于点击文字：光标放到起始行末尾。想把光标放到折叠行最后一个字符后面时，这样不容易误触展开。折叠标记不受影响，仍然可以折叠和展开。
- 折叠可以嵌套。加载另一个文本、或者把提供者设为 `null` 时，所有折叠都会展开。
- **保存**：折叠随实例状态保存，屏幕旋转后重新加载同一段文本时恢复。也随编辑会话保存（`EditSession.foldedLines`），但只在会话里的文本就是屏幕上的文本时；有未保存的修改时，那些行号属于另一段文本，就不保存。
- 标记和占位块的颜色用 `setFoldMarkerColor` 设置。

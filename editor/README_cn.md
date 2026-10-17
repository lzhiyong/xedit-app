# editor

[English](README.md) | 中文

一个面向代码的 Android 文本编辑控件，基于 piece tree 文本缓冲区，为大文件（几十 MB、上百万行）设计。

包名 `x.github.module.editor`，入口类是 `EditorView`。

## 功能

- 大文件编辑：行缓存绘制、增量布局、后台重排
- 自动折行，可随时切换
- 撤销和重做，连续输入自动合并为一步；大段内容以引用保存，删掉几十 MB 再撤销也不复制文本
- 精确的修改状态：撤销回到保存时的内容即为未修改
- 编辑会话可随文件保存，下次打开时恢复，没保存的修改可以用重做找回
- 括号匹配（高亮、加粗、下划线）、缩进线、代码折叠，解析由 tree-sitter 等引擎实现
- 捏合缩放字号
- 光标和选区手柄可以由编辑器自己画，也可以用图片；带弹出、回弹和滚动摆动动画，以及放大镜（见下）
- 可选的固定行号栏（Gutter），文本横向滚动时不动，可以加标记、响应点击；行号在比例字体下也对齐
- 操作栏
- 搜索结果高亮，高亮随编辑自动平移
- 输入法组字（拼音、滑行输入等）
- 硬件键盘：方向键、按词移动、Shift 选择、常用快捷键
- edge-to-edge：软键盘弹出时光标和滚动条保持在可见区域
- 可在布局文件里设置字号、颜色、字体等属性
- 语法高亮以插件方式接入，不需要继承
- 屏幕旋转后保留字号、折行设置和阅读位置
- 基础的无障碍支持（屏幕阅读器）

最低支持 Android 7.0（API 24）。行缓存绘制需要 Android 10，低版本自动改用直接绘制。

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

## 继承与扩展

多数定制不需要继承：高亮用 `SyntaxHighlighter`，跟随编辑用 `addEditCallback`。需要改变绘制方式或在编辑流程中插入逻辑时，可以继承 `EditorView` 并重写 `protected open` 方法。

### 绘制

| 方法 | 画什么 |
|---|---|
| `drawText` | 一行的文字；默认实现会使用 `LineStyler` |
| `drawLineNumber` | 行号；只在关闭行号栏（`setGutterEnabled(false)`）、行号画在文本行里时调用 |
| `drawLineBackground` | 光标所在行的背景 |
| `drawSelectionBackground` | 选区 |
| `drawMatchesBackground` | 搜索匹配 |
| `drawComposingUnderline` | 输入法组字的下划线 |
| `drawBracketHighlight` | 光标处的括号及其配对的背景 |
| `drawBracketText` | 光标处的括号及其配对本身，加粗画在文字上面 |
| `drawBracketUnderline` | 光标处的括号及其配对下面的线 |
| `drawIndentGuide` | 一条缩进线；`active` 表示光标所在代码块的那条 |
| `drawFoldMarker` | 折叠标记；`folded` 表示是否折叠着 |
| `drawFoldPlaceholder` | 折叠后起始行末尾的占位块 |
| `drawRegionHighlight` | 决定一行画哪些背景，一般不需要重写 |

`drawText` 会在两种情况下被调用：录制行缓存时（每行一次，之后回放）和直接绘制时（每帧）。不要假设调用频率，也不要在里面保存跨行的状态。

### 文本变化

| 方法 | 时机 |
|---|---|
| `beforeTextChanged(edits: List<SliceEdit>)` | 编辑即将应用，缓冲区还是旧内容 |
| `onTextChanged` | 一处改动已写入缓冲区；一次编辑有几处改动就调用几次 |
| `afterTextChanged(changes: List<AppliedChange>, ...)` | 整次编辑完成 |

重写时需要调用 `super`。增量语法解析通常在 `onTextChanged` 里更新语法树，在 `afterTextChanged` 里重新解析。内容以 `TextSlice` 传递，需要文本时再取（见"语法高亮"一节），这样大段撤销不会在回调里复制出整段文本。

### 其他

| 方法 | 用途 |
|---|---|
| `onBackgroundRelayoutStarted` / `onBackgroundRelayoutFinished` | 后台重排开始和结束，成对出现：缩放结束后、折行模式下宽度改变后，以及在主线程调用 `setTextSize` / `setTypeface` / `setWordwrap` 时；可用来显示进度 |
| `onCopyResult` | 复制或剪切的结果（成功、无选区、内容过大、系统拒绝），由应用决定如何提示 |
| `onMergeStarted` / `onMergeCommitted` | 连续输入开始合并、合并后入栈，可用来刷新撤销按钮 |
| `onModifiedChanged` | 修改状态变化，见 `isModified` |
| `onFoldMarkerClick` | 点击折叠标记；默认先交给 `setOnFoldMarkerClickListener`，没被处理就折叠或展开 |
| `onFoldChanged` | 折叠或展开之后；默认交给 `setOnFoldChangeListener` |
| `onZoomBegin` / `onZoom` / `onZoomEnd` | 捏合缩放的各阶段 |

## 光标与手柄

手柄默认由编辑器自己画：一根立在文字上的细杆加一个圆形把手。选区开始处的把手在行的上方，结束处的在行的下方，两者不会叠在一起；插入手柄是光标下方的一个圆。也可以换成图片，见下面的"手柄外观"。

| 行为 | 说明 |
|---|---|
| 逐字选择 | 手指在文字上方时，手柄立在它所标记的字符上，随选区一个字符一个字符地移动 |
| 行外跟手 | 手指移到没有文字的地方（行尾之后）时，手柄和光标横向跟着手指走，但不会超出屏幕可见范围，也不会进入固定的行号栏下方；纵向始终落在某一行上，不会停在两行之间 |
| 松手回弹 | 在行外松手后，手柄弹回它在文字上的位置，略微过头再回正。如果那个位置已经不在屏幕上（比如拖到短行末尾之后、最后一行下方的空白处，视图又在边缘继续滚动了一段），视图会跟着滚回去，让手柄落点可见，和点击后光标的处理一样 |
| 换行防抖 | 手指要越过行界一段距离（半个行高，限制在 8dp 到 45dp 之间）才换到相邻行，停在两行交界处不会来回跳 |
| 出现与消失 | 手柄出现时由小变大并略微过冲，消失时缩小淡出 |
| 长按选词 | 选区从手指按下的位置向两边展开；手指按着时不显示手柄，松手后手柄弹出。如果按在屏幕边缘（被键盘挡住一半、靠近左右边缘），松手后像点击放置光标那样把选区滚到可见范围 |
| 长按后继续滑动 | 不松手继续移动，选区从被选中的词向手指所在位置逐字符扩展，换行规则与拖动手柄相同。手指本身不拖动内容，但移到边缘时视图会自动滚动，和拖动手柄一样 |
| 边缘自动滚动 | 拖动手柄或长按后扩选时，手指停在可见区域边缘附近（上下左右，底边在键盘上方），视图就一帧一帧地持续滚动，选区跟着扩展，手指不需要再动。越靠近边缘越快，到边缘或越过边缘时达到设定速度（默认每秒 20 行）；开始时会在 0.3 秒内逐渐加速，手指只是路过边缘不会把文本甩出去。手指离开边缘、松手或文本到头时停止。只朝有文字的方向滚动：当前行的文字已经全部露出来时不再向右滚，最后一行已经可见时不再向下滚，手指可以在空白处随意移动而文本不会跑掉 |
| 跳回当前行 | 扩选或拖动手柄进入一个较短的行，而视图之前为了长行横向滚过去了，这一行的文字全在屏幕左边看不到时，视图会立即横向跳过去，让这一行的行尾大约停在可见宽度的三分之二处，然后选择在手指下继续 |
| 手柄不重合，可互换 | 两个手柄不会停在同一位置，至少保留一个字符；一个手柄拖过另一个后两者互换，手指继续拖着选区的那一端。互换时原先剩下的那个字符保持选中，没被拖动的手柄不会移动 |
| 手柄重叠时 | 选区很短、两个手柄的触摸范围重叠时，触摸点离哪个把手近就抓哪个 |
| 滚动时摆动 | 横向或纵向滚动文本时，选区的两个手柄略微滞后于文字，幅度取决于滚动速度：越快滞后越多，慢慢拖动时几乎不动，最多 12dp；滚动缓下来后摆回原位并轻微过冲。插入手柄不摆动，始终跟着光标。可以关闭或调整幅度，见下 |
| 拖动时不漂移 | 边缘自动滚动时，手柄仍然停在手指下方 |
| 触摸范围 | 把手四周留有余量，并向文字方向再延伸一个行高，杆也可以抓；但在延伸出的这段上点击或长按，仍然算作点击或长按文字 |
| 首行的开始手柄 | 上方没有空间时，把手改到行的下方 |
| 插入手柄 | 每次点击都在落点处弹出；4 秒无操作后消失，按住时不会消失 |
| 光标闪烁 | 显示 500 毫秒、隐藏 500 毫秒；每次移动或输入后立即显示并重新计时。有选区时光标隐藏，切到后台再回来、离开再回到界面时也保持隐藏 |
| 抬手过滤 | 手指离开屏幕瞬间的抖动不会让位置多走一格 |
| 放大镜 | 对准手柄所在的那一行，沿行内跟随手指平滑移动，换行时滑过去 |

手柄颜色默认取主题的强调色，可以修改。滚动时的摆动默认开启：

```kotlin
editor.setHandleColor(color)
editor.setHandleSwayEnabled(false)                          // 关闭摆动
editor.setHandleSwayAmplitude(8 * resources.displayMetrics.density)  // 最大幅度，单位 px；0 等于关闭
editor.setAutoScrollSpeed(6f)                               // 边缘自动滚动的速度，每秒行数；0 等于关闭
```

幅度改变的不只是上限，而是整体：幅度减半，任何速度下的摆动都减半。默认值是 `SelectionController.DEFAULT_HANDLE_SWAY_AMPLITUDE_DP`（12dp）。

### 手柄外观

```kotlin
editor.useSystemHandles()                                   // 用主题的手柄，和系统文本框一样
editor.setHandleDrawables(R.drawable.start, R.drawable.end, R.drawable.middle)  // 自己的图片
editor.setHandleDrawables(null, null, null)                 // 回到编辑器自己画的手柄
```

两种外观在一点上不同：**手柄在行的哪一侧**。自己画的开始手柄立在行的上方；而按照 Android 的惯例，手柄图片三个都挂在行的下方。图片版的两个手柄靠方向错开：开始手柄从尖端向左伸出，结束手柄向右伸出，所以选区再短也不会互相盖住。尖端在图片宽度上的位置也按惯例：开始手柄在 3/4 处，结束手柄在 1/4 处，插入手柄在正中。

编辑器其余部分不再假定手柄在哪一侧，而是问手柄本身：触摸范围、把手与文字的分界、首行时是否翻到下方，都从手柄自己描述的"把手区域"推算。所以两种外观的触摸、拖动、动画、摆动行为完全一致。实现上，`TextHandleDrawable` 是抽象基类，`DrawnTextHandle` 和 `ResourceTextHandle` 是两种外观；要做第三种外观，继承基类，说明把手区域在哪、怎么画即可。

- 图片保留自己的颜色，`setHandleColor` 只影响自己画的手柄。主题的手柄本身就跟随 `colorControlActivated`。
- 传进来的图片会复制一份再用，同一个资源可以放心传给多处。
- 图片版的开始手柄在屏幕最左边时会有一部分伸出屏幕外，目前不会像系统那样左右镜像。

### 光标外观

和手柄一样，光标可以由编辑器自己画，也可以用图片：

```kotlin
editor.setCursorDrawable(null)                 // 编辑器自己画的竖线（默认），颜色跟随 setHandleColor
editor.setCursorWidth(3 * density.toInt())     // 自己画的竖线宽度，单位 px
editor.setCursorDrawable(R.drawable.my_cursor) // 图片，拉伸到一行高，宽度取图片自身宽度（没有则 2dp）
editor.useSystemCursor()                       // 系统文本框的光标（主题 editTextStyle 里的 textCursorDrawable）
```

## 行号栏（Gutter）

默认情况下行号画在文本行里，随文本一起横向滚动。开启行号栏后，行号画在文本左侧一个固定的栏里，文本横向滚动时它不动，滚到它下面的文字被遮住，就像滚出右边缘的文字一样。

它不是一个独立的 View。旁边放一个 View 就得逐帧跟随编辑器的滚动、缩放时的字号、折行时的行、内边距和键盘遮挡，总会慢一帧；由编辑器在同一次绘制里、按同一份布局画出来，就不会错位。

```kotlin
editor.setGutterEnabled(true)                  // 开启；默认关闭
editor.setLineNumbersVisible(false)            // 隐藏行号；默认显示
editor.setGutterTextGap(12f.dp)                // 行号栏与文本之间的间距，默认 8dp
editor.setGutterBackgroundColor(color)         // 背景，null 取编辑器背景
editor.setGutterDividerColor(color)            // 与文本之间的分隔线，null 取行号颜色的淡色
editor.setCurrentLineNumberColor(color)        // 光标所在行的行号，null 取文字颜色

// 为每一行加标记（断点、修改标记），画在行号下面
editor.setGutterDecoration { canvas, line, left, top, right, bottom ->
    if (line in breakpoints) canvas.drawCircle(left + 6f.dp, (top + bottom) / 2, 4f.dp, paint)
}

// 点击行号栏；返回 false 时按点击这一行的文本处理
editor.setOnGutterClickListener { line -> toggleBreakpoint(line); true }
```

- **行号的宽度只增不减**：编辑时行数跨过 999/1000 这类位数边界，行号位置只会变宽、不会来回变化；折行模式下每多一位就在后台重排一次。加载另一个文本时重新计算。
- **行号对齐**：行号按等宽的格子绘制，格子宽度取 0 到 9 中最宽的数字，每个数字居中在自己的格子里。所以在比例字体下，位数相同的行号同样宽（20 和 21 一样），个位、十位上下对齐。行号栏开着和关着都是如此。
- **间距**：行号栏和文本之间有一段间距（`setGutterTextGap`，默认 8dp），行号栏的底色只画到间距之前。这样行首的手柄有地方伸出来。
- **光标和手柄**：光标只画在文字可见的区域。手柄指向的文字可见时，整个手柄都画出来，可以伸到行号栏上方；文字滚到行号栏下面时，手柄整个不画。
- 折行模式下行号只画在一行的第一个显示行上。
- `getGutterWidth()` 是文本开始的位置：行号和它的边距、折叠标记列，以及行号栏开着时的间距。标记要和行号共享这块地方。宽度变化时，折行模式会重排。
- 行号栏关着时，`drawLineNumber` 钩子生效，行号画在文本行里。
- **隐藏行号**（`setLineNumbersVisible(false)`）：行号和它的位置都不要了，文本左移，只留一小段边距、折叠标记列（开启折叠时）和间距（开启行号栏时）。行号栏开着时仍然保留，用来放装饰和折叠标记。行号栏关着时，`drawLineNumber` 不再被调用。

## 无障碍

屏幕阅读器把编辑器识别为多行文本框，可以读出屏幕上的文字、跟随光标、读出输入的内容，并执行复制、剪切、粘贴和移动光标。

为了不在每次光标移动时交出整个大文件，交给屏幕阅读器的只是屏幕上可见的那几行（最多 4000 个字符）。用屏幕阅读器的手势按词、按行浏览文本目前不支持。

## 输入

```kotlin
// 默认：多行、无联想、无自动更正，适合代码
editor.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

// 底部有一条跟随键盘的符号栏，并且它会盖在编辑器上
editor.setBottomOverlay(symbolBar)
```

硬件键盘支持的按键：

| 按键 | 作用 |
|---|---|
| 方向键、Home、End、Page Up/Down | 移动光标 |
| Ctrl + 左/右 | 按词移动 |
| Ctrl + Home/End | 文本开头/结尾 |
| Shift + 以上任意 | 移动的同时选择 |
| Ctrl + A / C / X / V | 全选、复制、剪切、粘贴 |
| Ctrl + Z，Ctrl + Y 或 Ctrl + Shift + Z | 撤销、重做 |
| Delete、Backspace、Enter、Tab、Esc | 编辑和取消选区 |

按住删除键（硬件键盘或软键盘都一样）时，先逐字符删除，持续按住约 0.9 秒后改为按词删除，默认每秒 10 个词；松开后重新从逐字符开始。按键的重复速度大约是每秒 20 次，每次都删一个词会快到来不及松手，所以按词阶段两个词之间的重复什么也不做。速度可以调整：

```kotlin
editor.setFastDeleteSpeed(3f)    // 每秒删除的词数；0 表示一直逐字符删除
```

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

## 测试

```bash
./gradlew :editor:testDebugUnitTest          # 电脑上运行
./gradlew :editor:connectedDebugAndroidTest  # 需要设备或模拟器
```

构建脚本需要 `useJUnitPlatform()` 和 `junit-platform-launcher`，详见 `tests/README.md`。

| 范围 | 测试类 |
|---|---|
| 模型 | `EditorDocumentTest` `EditHistoryTest` `UndoManagerTest` `SearchResultsTest` |
| 布局 | `UnwrappedLayoutTest` `WordwrapLayoutTest` `FoldedLayoutTest` `TabStopsTest` `LineBreaksTest` `LayoutCoordinatorTest` |
| 代码结构 | `StructureProvidersTest` `FoldingModelTest` |
| 输入 | `InputComposerTest` `InputMethodBridgeTest` `IndentEditsTest` `BackspaceRepeatTest` `CaretNavigatorTest` `TouchUpFilterTest` |
| 工具 | `RangeSearchTest` `TextBoundariesTest` |
| 整体（设备） | `EditorViewTest` |

两个布局测试各包含一万两千次随机编辑，把增量更新的结果与全量重排逐行对比。

设备测试里会把编辑器画到位图上，检查普通文本、折行文本和带样式的文本确实画了出来。除此之外，触摸手势、滚动、内边距换算、软键盘遮挡没有自动化测试，依赖手动验证；无障碍只验证了交给屏幕阅读器的信息，没有用真实的屏幕阅读器测试过。

## 遗留问题与影响

下面是已知但还没有处理的问题，按"影响"列方便以后排优先级。"位置"一列指出问题所在的代码，供后续优化时查找。

### 性能

| 问题 | 影响 | 位置 / 思路 |
|---|---|---|
| 一次插入不到 2 万行的编辑，仍在主线程上测量插入的行 | 接近 2 万行时可能有一两百毫秒的停顿；更大的编辑已改为后台重排（见"撤销与重做"一节） | `EditorView` 的 `LARGE_EDIT_LINES`；可以按设备性能调低 |
| 重排期间编辑被锁住：改字号、换字体、切换折行、改制表位宽度、折行模式下行号多一位、大块撤销之后 | 大文件上几秒内无法输入，输入法提交的文字被拒绝 | **已接受**：期间应用显示进度条（`onBackgroundRelayoutStarted` / `Finished`）。要并行需要让编辑和重排结果合并 |
| piecetable 当作 `CharSequence` 逐字符访问时，每个字符一次 O(log n) 查找 | 应用直接对缓冲区跑 `Regex` 会非常慢 | `PieceTreeTextBuffer.get`；改用它自带的 `find()`，或用 `createSnapshot()` 得到的快照 |
| piecetable 不会自动压缩 | 长时间编辑大文件后 piece 碎片增多，查询变慢；已删除的文本仍占内存 | 需要应用调用 `compact()`；自动压缩前要先确认它和撤销历史里的片段引用如何共存 |
| 撤销历史里的片段引用会让它们指向的存储一直留在内存（`compact()` 后的旧存储块也是） | 删除大段文本后内存不会立刻下降；被引用的存储总量有上限（`maxReferencedChars`，默认 6400 万字符，约 128 MB），超出时丢弃最旧的撤销步骤，所以最多占用到这个量 | `UndoManager`；上限可以按设备内存调整 |
| 含制表符的不折行行，每帧整行绘制（不做横向裁剪） | 极长（几万字符）且含 Tab 的单行上滚动可能变慢 | `TextRenderer.drawRowDirect`；裁剪时可从行首累计制表位位置 |
| 多行搜索会复制搜索区间涉及的所有行 | 在大文件上做跨行搜索时内存占用高 | piecetable 的搜索实现 |

### 功能

| 问题 | 影响 | 位置 / 思路 |
|---|---|---|
| 不支持从右到左的文字和复杂排版 | 阿拉伯语、希伯来语、部分印度文字的显示、光标位置和选区都不对 | **暂不考虑**。测量和绘制按 UTF-16 字符逐个处理，没有用 `Layout` / BiDi |
| 不支持多光标 | 无法同时在多处输入 | `SelectionController` 只有一个光标和一个选区 |
| 无障碍支持是基础级别的 | 屏幕阅读器只能读到可见的文本，不能按词、按行浏览 | `EditorAccessibility` |
| 制表位按显示行的起点计算 | 折行后，后续显示行里的 Tab 和不折行时对不齐（每个显示行重新计数） | `TabStops`；要按整行计算需要给测量加上起点偏移 |
| `PlainBracketMatcher` 不认识字符串和注释 | 字符串、注释里的括号也参与匹配 | 用 tree-sitter 等引擎实现 `isBracket`，app 端的 `TreeSitterStructure` 已经这样做 |
| 实例状态和编辑会话都不包含文本 | 文本的保存和重新加载由应用负责 | 设计如此 |

## 许可

Apache License 2.0

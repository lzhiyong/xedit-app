# editor：定制与扩展

> [editor](../editor/README_CN.md) 模块文档 · [English](editor_customization.md)

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

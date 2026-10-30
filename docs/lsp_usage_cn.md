# lsp：使用指南

> [lsp](../lsp/README_CN.md) 模块文档 · [English](lsp_usage.md)

## 使用

```kotlin
val client = LspClient(
    connection = SocketConnection("127.0.0.1", 5007),
    config = LspClientConfig(rootUri = "file:///storage/emulated/0/project"),
    listener = object : LspClientListener {
        override fun onShowMessage(kind: MessageKind, message: String) = showToast(message)
    }
)

// 连接并介绍客户端；返回服务器支持的功能
val features = client.start()

// 打开文档；之后服务器会收到它的每次修改
val document = client.open("file:///storage/emulated/0/project/main.c", "c", text)

// 一次修改：替换一个范围（从 0 计数）；只有服务器要求全量同步时才读取全文
document.change(TextChange(range, "42")) { buffer.toString() }

// 光标处的补全；用户继续输入时在客户端过滤
val result = document.completion(TextPosition(line, character), trigger = ".")
val shown = CompletionRanker.rank(result.items, CompletionRanker.prefixAt(lineText, character))

// 需要时才获取的文档说明
val item = client.resolve(shown.first())

// 展开代码片段：得到文本，以及光标的位置
val snippet = SnippetParser.parse(item.insertText)   // 当 item.isSnippet 时

// 服务器发布的诊断
client.diagnostics.collect { byUri -> show(byUri[document.uri].orEmpty()) }

// 悬停提示
val hover = document.hover(TextPosition(line, character))

document.save()
document.close()
client.shutdown()
```

`start`、`completion`、`resolve`、`hover`、`shutdown` 都是 `suspend` 函数。
请求在 `LspClientConfig.requestTimeoutMillis` 内没有得到回应时，会抛出 `LspException`，
并在服务器端取消该请求。取消协程同样会取消请求。

### 补全的完整流程

1. 在以下时机请求补全：用户输入了服务器的触发字符
   （`ServerFeatures.completionTriggers`，如 `.`、`->`、`::`），
   或输入字母后稍作停顿，或用户主动触发。
2. 用户继续输入时，用 `CompletionRanker.rank` 过滤列表；
   只有结果的 `isIncomplete` 为 true 时才重新请求。
3. 用户选中一项后：如果缺少文档说明，调用 `client.resolve(item)`；
   用 `item.insertText` 替换 `item.edit.range`（没有时替换光标处的单词），
   如果 `item.isSnippet` 则先用 `SnippetParser` 展开；
   然后应用 `item.additionalEdits`（例如自动添加的 `#include`、`import`）。

## 与编辑器对接

piece table 的行列从 1 开始计数，协议从 0 开始：

```kotlin
val position = TextPosition.fromOneBased(lineNumber, column)
val (lineNumber, column) = position.toOneBased()
```

编辑器的修改（`EditorDocument.AppliedChange`）正好符合增量 `didChange` 的要求：
被替换的范围（以修改前的文本为准）和插入的文本。

```kotlin
override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, ...) {
    document.change(
        changes.map { change ->
            val r = change.range
            TextChange(
                TextRange(
                    TextPosition.fromOneBased(r.startLine, r.startColumn),
                    TextPosition.fromOneBased(r.endLine, r.endColumn)
                ),
                change.insertedText()
            )
        }
    ) { getTextBuffer().toString() }
}
```

一次有多处修改时，协议要求每个范围都以「前面的修改已经应用后」的文本为准。
如果编辑器给出的范围都以第一处修改之前的文本为准，就要从最后一处开始逐个发送，
或者用 `TextChange.full(...)` 发送全文。

文档用**服务器**能读取的 `file://` URI 命名：必须是应用和服务器都能访问的存储路径。
存储访问框架（SAF）的 `content://` URI 对服务器没有意义。

## 在 Android 上运行语言服务器

应用不能执行其他应用的二进制文件，所以编辑器无法用 `ProcessConnection` 启动安装在 Termux 里的服务器。
可行的方式有两种：

- **在 Termux 中运行服务器，通过 TCP 桥接**（`SocketConnection`），以 clangd 为例：

  ```sh
  pkg install clang socat
  socat TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork EXEC:clangd
  ```

  每个连接都会启动一个独立的 clangd。把项目放在共享存储上（先执行 `termux-setup-storage`），
  这样两边看到的是同一份文件。

- **随应用一起打包服务器**（`ProcessConnection`），例如把二进制文件命名为
  `lib<name>.so` 放进 `jniLibs`。Android 会把它解压到 `applicationInfo.nativeLibraryDir`，
  这个目录里的文件允许执行。

## 集成

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":lsp"))
}

android {
    packaging {
        resources {
            // lsp4j 和它的 JSON-RPC jar 都带有这些文件
            // one copy of the licences and notices kept in the APK; Maven's dependency list left out
            pickFirsts += setOf("about.html", "META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }
}
```

模块自带 R8 规则（`consumer-rules.pro`）：lsp4j 通过反射用 Gson 读写消息，
并通过 `java.lang.reflect.Proxy` 调用服务接口。

连接 `127.0.0.1` 上的服务器需要 `INTERNET` 权限。

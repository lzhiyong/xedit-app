# lsp

[English](README.md) | 中文

基于 [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) 的 [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) 客户端（包名 `x.github.module.lsp`），与 clangd、pyright、rust-analyzer 等语言服务器通信。接口全部是普通 Kotlin 类型：编辑器不需要接触 lsp4j、JSON-RPC 或协议中的 `Either`。

主代码不依赖 Android API，所有测试都是本地 JVM 测试。

**支持**：补全（含 resolve 和代码片段）、诊断、悬停、签名提示、定义、引用、格式化（全文与选区）、重命名、代码操作（含 `workspace/executeCommand` 和服务器发来的 `workspace/applyEdit`）、文档符号。

## 接入

```kotlin
dependencies { implementation(project(":lsp")) }

android {
    packaging {
        resources {
            // lsp4j 和它的 JSON-RPC jar 都带有这些文件：保留一份许可声明
            pickFirsts += setOf("about.html", "META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }
}
```

连接 `127.0.0.1` 上的服务器需要 `INTERNET` 权限。模块自带 R8 规则（lsp4j 通过反射和动态代理工作）。

## 使用

```kotlin
val client = LspClient(
    connection = SocketConnection("127.0.0.1", 5007),     // 或 ProcessConnection、StreamConnection
    config = LspClientConfig(rootUri = "file:///storage/emulated/0/project"),
    listener = object : LspClientListener {
        override fun onShowMessage(kind: MessageKind, message: String) = toast(message)
    }
)
val features = client.start()                              // 握手，返回服务器支持的功能

val doc = client.open("file:///storage/emulated/0/project/main.c", "c", text)
doc.change(TextChange(range, "42")) { buffer.toString() }  // 增量修改；服务器要求全量时才读全文

val result = doc.completion(TextPosition(line, ch), trigger = ".")
val shown = CompletionRanker.rank(result.items, CompletionRanker.prefixAt(lineText, ch))  // 继续输入时本地过滤
val item = client.resolve(shown.first())                   // 需要时再取文档说明
val snippet = SnippetParser.parse(item.insertText)          // item.isSnippet 时：文本 + 跳转点

client.diagnostics.collect { byUri -> show(byUri[doc.uri].orEmpty()) }   // StateFlow
doc.hover(pos); doc.definition(pos); doc.references(pos); doc.signatureHelp(pos)
doc.formatting(tabSize = 4, insertSpaces = true); doc.rename(pos, "newName")
doc.codeActions(range, diagnostics); doc.documentSymbols()

doc.save(); doc.close(); client.shutdown()
```

- 请求都是 `suspend` 函数，超过 `LspClientConfig.requestTimeoutMillis` 未回应时抛 `LspException`，并在服务器端取消；取消协程同样会取消请求。
- **补全流程**：在触发字符（`features.completionTriggers`）、输入停顿或用户主动触发时请求；继续输入时用 `CompletionRanker.rank` 过滤，只有 `isIncomplete` 为真才重新请求；选中后用 `insertText` 替换 `item.edit.range`（没有则替换光标处单词），再应用 `additionalEdits`（如自动添加的 `#include`）。

## 与编辑器对接

- **行列**：piece table 从 1 开始，协议从 0 开始，用 `TextPosition.fromOneBased(line, col)` / `toOneBased()` 换算。位置按 UTF-16 码元计数，与 Kotlin `String` 一致，无需转换。
- **修改**：编辑器的 `EditorDocument.AppliedChange`（被替换的范围 + 插入的文本）正好对应增量 `didChange`。一次有多处修改时，协议要求每个范围以「前面的修改已应用后」的文本为准；如果你的范围都以修改前为准，就从最后一处开始发送，或用 `TextChange.full(...)` 发全文。
- **URI**：用服务器能按路径读取的 `file://` URI。SAF 的 `content://` 对服务器没有意义。

## 线程

- lsp4j 在客户端自己的线程池中读消息，`LspClientListener` 也在这些线程上调用；更新界面请切到主线程。
- 通知（`didOpen`、`didChange` 等）按调用顺序发出，且先于之后发起的请求：修改之后请求的补全，看到的是修改后的文本。
- `LspDocument` 是线程安全的。`diagnostics` 请在生命周期作用域中收集。

## 在 Android 上运行语言服务器

应用不能执行其他应用的二进制文件，所以不能用 `ProcessConnection` 启动 Termux 里的服务器。两种可行做法：

- **在 Termux 中运行，通过 TCP 桥接**（`SocketConnection`）：`pkg install clang socat`，然后 `socat TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork EXEC:clangd`。每个连接启动一个独立的服务器。项目放在共享存储上（先 `termux-setup-storage`），两边看到同一份文件。
- **由应用启动自己的二进制**（`ProcessConnection`）：比如命名为 `lib<名字>.so` 放进 `jniLibs`，安装后位于可执行的 `nativeLibraryDir` 中。X Editor 的做法见 [app 的工具链](../app/TOOLCHAIN_CN.md)。

## 限制

- 尚未实现：语义高亮、内联提示、工作区符号、调用层次、折叠范围。
- 工作区编辑中的创建、重命名、删除文件操作被忽略，只应用文档内容的修改。
- 一个 `LspClient` 对应一个服务器、一个项目。
- 位置只支持 UTF-16：只支持 UTF-8 计数的服务器无法使用（协议要求服务器支持回退到 UTF-16）。
- 不支持 `workspace/configuration` 和动态注册：设置请用 `initializationOptions` 或服务器自己的配置文件（`.clangd`、`pyproject.toml`）。
- lsp4j 是 JVM 库，请在应用支持的最低 API 上验证。

## 测试

`./gradlew :lsp:testDebugUnitTest`。`LspClientTest` 让整个客户端通过内存管道与 `FakeLanguageServer`（一个完整的 lsp4j 服务器，记录收到的消息并固定作答）对接，覆盖握手、文档同步、诊断、补全、悬停、关闭和服务器无响应；其余测试覆盖类型转换、补全排序、代码片段解析和真实 socket 连接。

## 许可证

本模块代码为 Apache License 2.0。依赖的 [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j) 以库的形式使用，未复制源码，采用 EPL-2.0 **或** EDL-1.0（BSD-3-Clause）双重许可，全文见 [LICENSE-lsp4j](LICENSE-lsp4j)。分发包含本模块的应用时需附带其许可证（上面的打包配置已在 APK 中保留），并说明可以从该项目获取源码。LSP4J 依赖的 Gson 为 Apache License 2.0。

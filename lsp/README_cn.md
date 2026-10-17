# Language Server Protocol 模块

基于 [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) 的 [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
客户端：与语言服务器（clangd、pyright、rust-analyzer、kotlin-language-server 等）通信，
为编辑器提供**自动补全**、**语法诊断**和**悬停提示**，接口全部是普通的 Kotlin 类型。
编辑器不需要接触 lsp4j、JSON-RPC，也不需要处理协议中的 `Either` 类型。

包名 `x.github.module.lsp`。主代码不依赖任何 Android API，因此所有测试都是快速的本地 JVM 测试。

## 目录

1. [架构](#架构)
2. [使用](#使用)
3. [与编辑器对接](#与编辑器对接)
4. [在 Android 上运行语言服务器](#在-android-上运行语言服务器)
5. [线程](#线程)
6. [集成](#集成)
7. [测试](#测试)
8. [已知限制](#已知限制)

---

## 架构

| 文件 | 职责 |
|---|---|
| `LspClient.kt` | 一个服务器的客户端：启动（`initialize` / `initialized`）、关闭（`shutdown` / `exit`）、声明的客户端能力、以 `StateFlow` 提供的诊断、带超时的请求 |
| `LspDocument.kt` | 一个已打开的文档：`didOpen`、`didChange`（按服务器要求增量或全量）、`didSave`、`didClose` 及版本号；在指定位置请求补全和悬停提示 |
| `LanguageServerConnection.kt` | 服务器在哪里：`ProcessConnection`、`SocketConnection`、`StreamConnection` |
| `LspClientConfig.kt` | `LspClientConfig`（客户端如何介绍自己）、`LspClientListener`（收到的通知）、`ServerFeatures`（服务器支持的功能） |
| `LspTypes.kt` | 编辑器使用的类型：`TextPosition`、`TextRange`、`TextChange`、`LspDiagnostic`、`LspCompletionItem`、`HoverInfo` 等 |
| `LspConverters.kt` | lsp4j 类型与上述类型之间的转换（内部） |
| `LspFeatureTypes.kt` | 其他功能的类型：`LspLocation`、`SignatureHelpInfo`、`LspWorkspaceEdit`、`LspCodeAction`、`LspCommand`、`LspSymbol` |
| `LspFeatureConverters.kt` | 在 lsp4j 与上述类型之间转换（internal） |
| `CompletionRanker.kt` | 用户继续输入时，在客户端过滤并排序补全项 |
| `SnippetParser.kt` | 把代码片段（`printf(${1:fmt})$0`）转换为文本和跳转点 |

```
 编辑器 ── LspDocument ── LspClient ── lsp4j (JSON-RPC) ── LanguageServerConnection ── 服务器
                │              │
                │              └── diagnostics: StateFlow<Map<uri, List<LspDiagnostic>>>
                └── completion / hover（suspend）
```

### 客户端声明的能力

客户端只声明自己能处理的能力，这样服务器就不会发来得不到回应的请求：

- 文本同步，包括 `didSave`；补全支持代码片段（可配置）、Markdown 文档、
  `completionItem/resolve` 和上下文（触发字符）；
- 悬停提示，支持 Markdown 和纯文本；
- 带版本号的诊断；
- 工作区文件夹；
- 位置以 **UTF-16** 码元计数，与 Kotlin `String` 的 char 一致，无需转换。

未声明的能力：动态注册、`workspace/configuration`、进度通知、工作区编辑。即使服务器发来这类请求，也会得到礼貌的回应。

---

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

---

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

---

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

---

## 线程

- lsp4j 在客户端自己的线程池中读取服务器的消息，`LspClientListener` 也在这些线程上被调用。
  需要更新界面时，请切换到主线程。
- 通知（`didOpen`、`didChange` 等）按调用顺序发出，并且先于之后调用的请求：
  在一次修改之后请求的补全，看到的是修改后的文本。
- `LspDocument` 是线程安全的，它的修改按顺序逐个发出。
- `diagnostics` 是 `StateFlow`，请在生命周期作用域中收集。

---

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

---

## 测试

```sh
./gradlew :lsp:testDebugUnitTest
```

| 测试 | 覆盖内容 |
|---|---|
| `LspClientTest` | 整个客户端与 `FakeLanguageServer` 通过内存管道对接：握手和声明的能力；`didOpen` / 增量和全量 `didChange` / `didSave` / `didClose` 及版本号；诊断的发布与清除；补全（触发字符）、resolve、悬停提示；关闭；服务器无响应；启动前发起请求 |
| `LspConvertersTest` | 诊断、补全项（编辑、代码片段、文档、标签、插入或替换编辑）、补全列表、悬停提示、消息类型 |
| `CompletionRankerTest` | 光标前的单词、匹配分数、排序 |
| `SnippetParserTest` | 跳转点、占位符（含嵌套）、镜像、选项、变量、转义、格式错误的片段 |
| `LspTypesTest` | 与从 1 开始的行列互相转换、位置比较、范围、补全类型 |
| `SocketConnectionTest` | 用真实 socket 测试 `SocketConnection`：连接到指定端口；端口无人监听时报错 |

`FakeLanguageServer` 是供测试使用的完整 lsp4j 服务器：它记录收到的消息，并用固定的内容作答。
管道使用 `java.nio` 的 Pipe 而不是 `PipedInputStream`，因为后者在最后一个写入线程结束后会失效，
而 lsp4j 会从多个线程写入。

---

## 已知限制

- 已实现：补全、诊断、悬停、签名提示、定义、引用、格式化（全文与选区）、重命名、代码操作（含 `workspace/executeCommand`，服务器发来的 `workspace/applyEdit` 交给 `LspClientListener.applyEdit`）、文档符号。尚未实现：语义高亮、内联提示、工作区符号、调用层次、折叠范围。
- 工作区编辑中的创建、重命名、删除文件操作会被忽略，只应用对文档内容的修改。

- 一个 `LspClient` 对应一个服务器，一个服务器对应一个项目。
- 目前支持补全、诊断和悬停提示；还不支持跳转到定义、查找引用、格式化、重命名、
  代码操作、函数签名提示和语义标记。每一项都和 `hover` 一样，是一个请求，
  再在 `clientCapabilities()` 中声明相应能力即可。
- 位置只支持 UTF-16 码元：只支持 UTF-8 计数的服务器无法使用
  （协议要求服务器必须支持回退到 UTF-16）。
- 不支持 `workspace/configuration`：设置请通过 `initializationOptions` 传入，
  或写在服务器自己的配置文件中（`.clangd`、`pyproject.toml` 等）。
- lsp4j 是 JVM 库，可以在 Android 上运行，但请在应用支持的最低 API 版本上验证。

## 许可证

本模块自己的代码采用 Apache License 2.0，与 X Editor 其余部分相同。

本模块依赖 [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j)（`org.eclipse.lsp4j` 和 `org.eclipse.lsp4j.jsonrpc`），以库的形式使用，没有复制它们的任何源码。LSP4J 采用 Eclipse Public License 2.0 **或** Eclipse Distribution License 1.0（一种 BSD-3-Clause 许可证）双重许可，使用者可以任选其一；两份许可证全文都在 [LICENSE-lsp4j](LICENSE-lsp4j) 中。包含本模块的应用会一并分发 LSP4J 的二进制代码：需要随附它的许可证（APK 中保留了这些 jar 包自带的许可声明，见[集成](#集成)），并说明可以从上述项目获取其源码。LSP4J 依赖 [Gson](https://github.com/google/gson)，后者采用 Apache License 2.0。


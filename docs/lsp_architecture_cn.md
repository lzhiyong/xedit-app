# lsp：设计与原理

> [lsp](../lsp/README_CN.md) 模块文档 · [English](lsp_architecture.md)

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

## 线程

- lsp4j 在客户端自己的线程池中读取服务器的消息，`LspClientListener` 也在这些线程上被调用。
  需要更新界面时，请切换到主线程。
- 通知（`didOpen`、`didChange` 等）按调用顺序发出，并且先于之后调用的请求：
  在一次修改之后请求的补全，看到的是修改后的文本。
- `LspDocument` 是线程安全的，它的修改按顺序逐个发出。
- `diagnostics` 是 `StateFlow`，请在生命周期作用域中收集。

# lsp：测试

> [lsp](../lsp/README_CN.md) 模块文档 · [English](lsp_testing.md)

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

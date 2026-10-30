# Language Server Protocol 模块

基于 [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) 的 [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
客户端：与语言服务器（clangd、pyright、rust-analyzer、kotlin-language-server 等）通信，
为编辑器提供**自动补全**、**语法诊断**和**悬停提示**，接口全部是普通的 Kotlin 类型。
编辑器不需要接触 lsp4j、JSON-RPC，也不需要处理协议中的 `Either` 类型。

包名 `x.github.module.lsp`。主代码不依赖任何 Android API，因此所有测试都是快速的本地 JVM 测试。

## 文档

详细内容见本模块的文档：

- [设计与原理](../docs/lsp_architecture_cn.md)：架构、线程
- [使用指南](../docs/lsp_usage_cn.md)：使用、与编辑器对接、在 Android 上运行语言服务器、集成
- [测试](../docs/lsp_testing_cn.md)：测试

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

本模块依赖 [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j)（`org.eclipse.lsp4j` 和 `org.eclipse.lsp4j.jsonrpc`），以库的形式使用，没有复制它们的任何源码。LSP4J 采用 Eclipse Public License 2.0 **或** Eclipse Distribution License 1.0（一种 BSD-3-Clause 许可证）双重许可，使用者可以任选其一；两份许可证全文都在 [LICENSE-lsp4j](LICENSE-lsp4j) 中。包含本模块的应用会一并分发 LSP4J 的二进制代码：需要随附它的许可证（APK 中保留了这些 jar 包自带的许可声明，见[集成](../docs/lsp_usage_cn.md#集成)），并说明可以从上述项目获取其源码。LSP4J 依赖 [Gson](https://github.com/google/gson)，后者采用 Apache License 2.0。

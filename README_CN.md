# X Editor

一个 Android 代码编辑器，面向大文件优化：8.8 MB、23.9 万行的 `sqlite3.c` 也能流畅地滚动、折叠和编辑。

English version: [README.md](README.md).

## 功能

- **大文件流畅**：文本存放在 piece tree 中（与 VS Code 的文本缓冲区相同的数据结构），tree-sitter 语法高亮
  配合 nvim-treesitter 的查询文件，全部在主线程之外计算。
- **编辑**：跨会话保留的撤销与重做、自动折行、代码折叠、缩进线、括号匹配与自动配对、支持正则的查找替换、
  跳转到行、注释切换、浮动选择栏、双指缩放和键盘上方的符号栏。
- **语言服务器**（由你自行配置，如 clangd、pyright、lua-language-server）：补全、诊断、悬停、签名提示、
  跳转定义、查找引用、重命名、代码操作、格式化和大纲；支持 friendly-snippets 和自定义代码片段。
- **文件**：多标签页、文件树，通过存储访问框架或文件路径打开和保存；文件被其他应用修改或删除时会提示，
  由你选择重新加载还是保留当前内容。
- **控制台与工具链**：在 Termux 或应用自带的工具链中编译运行，并从输出中识别 gcc、clang 和 Python 的错误。
- **外观**：浅色、深色、纯黑和动态取色主题，高亮主题，字体。
- **稳定性**：捕获 Java 崩溃、native 崩溃和 ANR，并在独立进程中展示。

完整列表见[功能](docs/features_cn.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [构建](docs/building_cn.md) | 环境要求、构建步骤、使用 R8 的 release 构建 |
| [配置](docs/config_cn.md) | 高亮资源、`servers.json`、格式化规则、自定义代码片段 |
| [示例：在 Termux 中运行语言服务器](docs/example_cn.md) | 通过 socket 连接 clangd 和 pyright，连接不上时的排查 |
| [使用说明](docs/usage_cn.md) | 代码补全、代码片段、路径补全、大纲、注释 |
| [工具链](docs/toolchain_cn.md) | `packages.json`、应用自己的 usr 目录、Termux、运行命令 |
| [功能](docs/features_cn.md) | 编辑器的全部功能 |
| [性能设计](docs/performance_cn.md) | 后台高亮、无闪烁、原生谓词 |
| [架构](docs/architecture_cn.md) | 目录结构、文档与编辑会话 |
| [测试](docs/testing_cn.md) | 各模块的测试及运行方式 |

每个模块都有自己的 README，模块的详细文档同样放在 [`docs`](docs) 目录下（如 `lsp_usage_cn.md`、
`editor_architecture_cn.md`）。

## 模块

| 模块 | 说明 | 是否被 app 使用 |
| --- | --- | --- |
| [`app`](app) | 应用本身：各个界面、设置、tree-sitter 语法高亮、高亮资源的下载 | — |
| [`editor`](editor) | `EditorView`：排版（折行与不折行、折叠）、渲染、输入与输入法、选择、浮条、代码折叠、缩进线、撤销 | 是 |
| [`piecetable`](piecetable/README_CN.md) | piece tree 文本缓冲区，移植自 VS Code | 是 |
| [`treesitter`](treesitter) | tree-sitter 的 Kotlin 绑定（通过 JNI），包含原生谓词判断 | 是 |
| [`document`](document/README_CN.md) | AndroidX `DocumentFile` 的重写版，更快更安全，同时支持 SAF 文档和普通文件 | 是 |
| [`treeview`](treeview/README_CN.md) | 基于单个 `RecyclerView` 的树形视图，用于文件树 | 是 |
| [`crash`](crash/README_CN.md) | 崩溃捕获：Java、native、ANR | 是 |
| [`lsp`](lsp/README_CN.md) | 基于 lsp4j 的语言服务器协议（LSP）客户端：补全、诊断、悬停、签名提示、定义、引用、重命名、代码操作、格式化、符号 | 是 |
| [`bypass`](bypass) | 通过 `ContentProvider` 和 JNI 访问 Android 隐藏 API | 尚未接入 |

## 已知限制

- native 代码（`treesitter`、`crash`）只为 `arm64-v8a` 构建；其他设备或模拟器需要在对应模块的
  `build.gradle.kts` 中添加 ABI。
- 高亮资源在首次启动时下载，下载完成前文件不显示语法高亮。
- 应用不附带任何语言服务器。没有文件路径的提供方（如云盘）中的文档不会发送给语言服务器。
- 文件按 UTF-8 读写（保留字节顺序标记）。
- 没有"所有文件访问权限"时，路径补全和项目样式文件只能通过文件树中打开的文件夹使用；在共享存储上用应用自带的
  工具链构建项目也需要该权限。
- 应用自带工具链中的程序必须为 Android（bionic）构建且动态链接；Termux 的软件包请在 Termux 中运行。
- 只检查当前显示文档的外部修改，依据的是提供方给出的修改时间和大小；两者都不提供的提供方无法监听。
- 各模块的限制见各自的 README。

## 第三方代码

- [tree-sitter](https://github.com/tree-sitter/tree-sitter)（MIT），由 `get_sources.py` 拉取。
- [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter)（MIT），`treesitter` 模块的来源：Kotlin 代码参考其改写，JNI 层由 C 重写为 C++；见[许可证全文](treesitter/LICENSE-kotlin-tree-sitter)。
- [VS Code](https://github.com/microsoft/vscode) 的 piece tree（MIT），移植到了 `piecetable` 模块；见[许可证全文](piecetable/LICENSE-vscode)。
- [nvim-treesitter](https://github.com/nvim-treesitter/nvim-treesitter) 的查询文件，包含在下载的资源中。
- [friendly-snippets](https://github.com/rafamadriz/friendly-snippets)（MIT），补全中的代码片段，包含在下载的资源中。
- [lsp4j](https://github.com/eclipse-lsp4j/lsp4j)（EPL-2.0 或 EDL-1.0），`lsp` 模块依赖的库；见[许可证全文](lsp/LICENSE-lsp4j)。

各项目的许可证请以其仓库中的说明为准。

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

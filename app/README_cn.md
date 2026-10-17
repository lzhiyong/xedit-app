# app

X Editor 的应用模块：各个界面、设置、tree-sitter 语法高亮、语言服务器与代码补全，以及控制台。它建立在以下库模块之上：[`editor`](../editor)、[`piecetable`](../piecetable/README_cn.md)、[`treesitter`](../treesitter/README_cn.md)、[`document`](../document/README_cn.md)、[`treeview`](../treeview/README_cn.md)、[`crash`](../crash/README_cn.md) 和 [`lsp`](../lsp/README_cn.md)。

English version: [README.md](README.md).

## 包结构

都位于 `x.editor.app` 之下：

| 包 | 内容 |
| --- | --- |
| `activity` | `EditorActivity`，即编辑界面：文档与标签页、选项菜单、浮动菜单的菜单项、快捷符号栏、双击返回退出。另有 `SettingsActivity`、`CrashActivity`。 |
| `controller` | 编辑界面的各个部分：文件树、标签页、搜索面板、下载面板、跳转到行（`GotoLineDialog`），以及语言服务器（`LspController`）。 |
| `highlight` | tree-sitter 语法高亮：`TreeSitterEngine`（在工作线程上执行查询，结果按块保存在 `CaptureStore` 中）、`StructureQuery`（折叠与缩进）、`TreeSitterInjections` 及 `InjectionPrefilter`（不可能产生高亮的注释不解析）、`QueryPredicates`，以及高亮主题。 |
| `lsp` | 语言服务器：`LanguageServers`（`servers.json` 与 `register`）、`LanguageServerSpec`、两种连接方式（通过 socket 连接 Termux 中的服务器、启动用户安装的二进制）、`LocalFiles`、`CompletionTyping`、`LspLog`、`HoverMarkdown` 与 `HoverSpans`。 |
| `completion` | `BufferWords`：基于文本中单词的补全，类似 blink.cmp 的 buffer 源。 |
| `console` | 控制台：`ConsoleSheet`（输出、问题、构建）、`ConsoleLog`、`BuildRunner`、`ProblemMatcher`，以及编译运行用到的 `RunCommands` 和 `RunSettingsDialog`。 |
| `service` | `EditorService`：文档读写、编辑会话、下载；`DocumentSaves`。 |
| `view` | `CodeEditorView`：接入 tree-sitter 的 `EditorView`，以及复制、剪切的提示；侧边抽屉；窗口边距处理。 |
| `filetree` | 文件树的条目及其加载，`DocumentPaths`（文档在目录中的 URI）。 |
| `settings`、`preference` | 设置界面及其设置项；`EditorSettings`。 |
| `download`、`treesitter` | 下载高亮资源并保持更新。 |
| `crash`、`database`、`session`、`search`、`viewmodel`、`adapter`、`recyclerview`、`util` | 崩溃报告、下载记录数据库、编辑会话、查找、视图模型、列表适配器、工具类。 |

## 读写的文件

| 内容 | 位置 |
| --- | --- |
| 高亮资源（语法库、查询文件、主题） | 按 [`assets/treesitter.json`](src/main/assets/treesitter.json) 中的地址下载，保存在应用的私有目录中 |
| 语言服务器配置 | `servers.json`：有"所有文件访问权限"时在共享存储的 `xedit/lsp/` 中，否则在 `Android/data/x.editor.app/files/lsp/` 中；可从菜单"语言服务器配置"打开 |
| 由应用启动的服务器的二进制 | `servers.json` 旁边的 `bin/` 和 `lib/`，运行前复制到应用的私有存储中 |
| 语言服务器日志 | `servers.json` 旁边的 `lsp.log`，以及控制台 |
| 编译运行命令 | 应用的设置中，按文件扩展名分别保存（长按工具栏上的运行按钮设置） |

## 各部分如何工作

- **文档与编辑会话**：见项目 README 中的[文档与编辑会话](../README_cn.md#文档与编辑会话)。
- **语法高亮**：见[性能设计](../README_cn.md#性能设计)。
- **语言服务器与补全**：见[代码补全](../README_cn.md#代码补全语言服务器)。`LspController` 为每个服务器和项目根目录各维护一个客户端；每次编辑都按顺序在同一个线程上发给服务器；输入时按 `CompletionTyping` 的判断请求补全；服务器没有结果时，改用文本中的单词补全。
- **控制台**：显示各频道的输出、已打开文档的问题和最近一次构建的问题；构建命令通过 Termux 的 `RUN_COMMAND` 在 Termux 中运行，并接收它回传的结果（`BuildRunner`）。

## 测试

纯 Kotlin 测试，在 JVM 上运行：`./gradlew :app:testDebugUnitTest`。

| 测试 | 覆盖内容 |
| --- | --- |
| `CompletionTypingTest` | 输入时何时请求补全：输入单个字符、输入法组字、触发字符、单词结束、删除 |
| `BufferWordsTest` | 文本中的单词及出现次数、模糊匹配、排序、补全项中需要高亮的字符 |
| `ProblemMatcherTest` | 从构建输出中识别问题：gcc 与 clang、Python 的 traceback 与语法错误 |
| `RunCommandsTest` | 运行命令中的变量替换、默认命令 |
| `HoverMarkdownTest` | 悬停信息的 Markdown 转为文本与样式标记：标题、代码、列表、代码块及其语法着色 |
| `GotoLineTargetTest` | 跳转到行的输入：行号、`行号:列号`、文件首尾的边界 |
| `LanguageServerSpecTest` | 解析 `servers.json`、文档 ID 换算为文件路径、项目根目录的查找 |

依赖 Android 的部分（界面、视图、Termux）不在这些测试的范围内。

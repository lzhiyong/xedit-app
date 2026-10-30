# app：设计与原理

> [app](../app/README_CN.md) 模块文档 · [English](app_architecture.md)

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

## 各部分如何工作

- **文档与编辑会话**：见项目 README 中的[文档与编辑会话](architecture_cn.md#文档与编辑会话)。
- **语法高亮**：见[性能设计](performance_cn.md)。
- **语言服务器与补全**：见[代码补全](config_cn.md#语言服务器serversjson)。`LspController` 为每个服务器和项目根目录各维护一个客户端；每次编辑都按顺序在同一个线程上发给服务器；输入时按 `CompletionTyping` 的判断请求补全；服务器没有结果时，改用文本中的单词补全。
- **控制台**：显示各频道的输出、已打开文档的问题和最近一次构建的问题；构建命令通过 Termux 的 `RUN_COMMAND` 在 Termux 中运行，并接收它回传的结果（`BuildRunner`）。

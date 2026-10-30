# 测试

> [X Editor](../README_cn.md) 文档 · [English](testing.md)

| 模块 | 测试类数量 | 覆盖内容 |
| --- | --- | --- |
| `editor` | 28 | 编辑历史与撤销、文档模型、查找、代码折叠、结构提供者、文本边界、行录制（`RowCache`，含"不闪烁"的随机属性测试）、各种排版（不折行、折行、折叠、换行计算、制表位）、光标移动、输入与输入法、自动配对、缩进编辑、浮条溢出菜单高度（`FloatingBarTest`）、结构平移、光标旁弹窗的位置（`PopupPlacementTest`）、片段跳转点（`SnippetSessionTest`）、注释与取消注释（`CommentToggleTest`）、补全时的闭合字符（`ClosingCharsTest`） |
| `lsp` | 5 | 类型转换、代码片段、补全排序、客户端 |
| `document` | 3 | 文档文件与查询 |
| `app` | 16 | 输入时何时请求补全、文本单词补全、构建输出中的问题、运行命令、悬停信息的 Markdown、跳转到行、`servers.json`、自定义代码片段的文件与排序、格式化规则、路径补全、带参数占位的函数调用、`.editorconfig` 与 `.clang-format`、注释符号、软件包解压、来源与清单、命令运行位置（`ToolchainRulesTest`）、项目根目录（`ProjectRootTest`） |
| `piecetable` | 16 | VS Code 的测试集及更多用例，在 JVM 和设备上各运行一遍（`src/sharedTest`） |
| `treesitter` | 1 | 原生层返回结果的解码、位置、范围、查询错误、缺少捕获时的谓词 |
| `treeview` | 1 | 树结构：子节点、父节点、深度、祖先、后代 |
| `crash` | 2 | 崩溃签名、崩溃文件及计数 |
| `bypass` | 1 | JVM 上的反射；解除限制本身需要在设备上运行 |

```sh
./gradlew test                                  # 全部 JVM 单元测试
./gradlew :editor:testDebugUnitTest             # 单个模块
./gradlew connectedDebugAndroidTest             # 设备测试，需要真机或模拟器
```

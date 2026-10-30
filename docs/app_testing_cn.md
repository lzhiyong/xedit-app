# app：测试

> [app](../app/README_CN.md) 模块文档 · [English](app_testing.md)

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

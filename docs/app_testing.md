# app: Testing

> Part of the documentation of the [app](../app/README.md) module · [中文](app_testing_cn.md)

Plain Kotlin, on the JVM: `./gradlew :app:testDebugUnitTest`.

| Test | Covers |
| --- | --- |
| `CompletionTypingTest` | When typing asks for completion: one char, a word composed by the keyboard, trigger characters, what ends a word, deleting |
| `BufferWordsTest` | The words of a text and how often, fuzzy matching, ranking, what of a label to highlight |
| `ProblemMatcherTest` | Problems in a build's output: gcc and clang, Python tracebacks and syntax errors |
| `RunCommandsTest` | The variables of a run command, the defaults |
| `HoverMarkdownTest` | Hover Markdown as text and marks: headings, code, lists, code blocks and their tokens |
| `GotoLineTargetTest` | What is typed in go to line: lines, `line:column`, the ends of the file |
| `LanguageServerSpecTest` | `servers.json` read into specs, document IDs to files, the project root |

What needs Android (the screens, the views, Termux) is not covered by these.

# X Editor

[English](README.md) | 中文

面向大文件的 Android 代码编辑器：8.8 MB、23.9 万行的 `sqlite3.c` 也能流畅地滚动、折叠和编辑。

## 功能

- **大文件流畅**：文本存放在 piece tree（VS Code 文本缓冲区的数据结构）中，tree-sitter 高亮、解析、折叠都在主线程之外计算。
- **编辑**：跨会话保留的撤销重做、自动折行、代码折叠、缩进线、括号匹配与自动配对、正则查找替换、跳转到行、注释切换、选择浮条、双指缩放、键盘上方的符号栏。
- **语言服务器**（clangd、pyright 等，需自行配置）：补全、诊断、悬停、签名提示、定义、引用、重命名、代码操作、格式化、大纲；支持 friendly-snippets 和自定义代码片段。
- **文件**：多标签页、文件树；通过 SAF 或文件路径打开和保存；文件被其他应用修改或删除时会提示。
- **控制台与工具链**：在 Termux 或应用自带的工具链中编译运行，并识别 gcc、clang、Python 的报错。
- **外观**：浅色、深色、纯黑、动态取色主题；可导入高亮主题和字体。
- **稳定性**：捕获 Java 崩溃、native 崩溃和 ANR，在独立进程中展示。

使用方法见 [app 模块](app/README_CN.md)。

## 构建

环境：JDK 21；Android SDK platform 36、build tools 37.0.0；NDK 29.0.14206865 与 CMake 4.1.2+；Python 3 与 git；Gradle 9+。具体版本见 [`libs.versions.toml`](libs.versions.toml)。

```sh
python3 treesitter/get_treesitter_source.py         # 拉取 tree-sitter 源码（构建时也会自动执行）
gradle wrapper                                     # 仓库不带 wrapper，也可以直接用 Android Studio
./gradlew :app:assembleDebug
```

- **tree-sitter 分支**：`gradle.properties` 中的 `treeSitterBranch`（默认 `master`），或者单次构建时加 `-PtreeSitterBranch=v0.25.x`，分支名和 tag 都可以。详见 [treesitter 模块](treesitter/README_CN.md#构建)。
- **只构建 `arm64-v8a`**：在模拟器或其他架构上运行，需要在 `treesitter`、`crash`、`bypass` 的 `build.gradle.kts` 中给 `abiFilters` 加上对应 ABI。
- **Release**：`./gradlew :app:assembleRelease`，由 R8 压缩、混淆并移除无用资源。
  - 通过反射或 JNI 访问的类由 `app/proguard-rules.pro` 和各模块的 `consumer-rules.pro` 保留；新增这类代码时，在所在模块的 `consumer-rules.pro` 中加规则。
  - 每次发布都保存 `app/build/outputs/mapping/release/mapping.txt`，用它还原崩溃堆栈（`retrace mapping.txt stacktrace.txt`）。
  - 仓库中没有签名配置，请用自己的密钥签名。CI 构建的是 debug APK。

## 测试

```sh
./gradlew test                          # 所有模块的 JVM 单元测试
./gradlew :editor:testDebugUnitTest     # 单个模块
./gradlew connectedDebugAndroidTest     # 设备测试，需要真机或模拟器
```

各模块测试覆盖的范围见模块 README。

## 模块

| 模块 | 作用 | 文档 |
| --- | --- | --- |
| [`app`](app) | 应用本身：界面、设置、高亮、语言服务器、控制台、工具链 | [说明](app/README_CN.md) · [配置](app/CONFIG_CN.md) · [工具链](app/TOOLCHAIN_CN.md) |
| [`editor`](editor) | 代码编辑控件 `EditorView` | [说明](editor/README_CN.md) · [设计](editor/DESIGN_CN.md) |
| [`piecetable`](piecetable) | piece tree 文本缓冲区，移植自 VS Code | [说明](piecetable/README_CN.md) · [设计](piecetable/DESIGN_CN.md) |
| [`treesitter`](treesitter) | tree-sitter 的 Kotlin/JNI 绑定，谓词在原生层判断 | [说明](treesitter/README_CN.md) |
| [`lsp`](lsp) | 基于 lsp4j 的 LSP 客户端，接口为普通 Kotlin 类型 | [说明](lsp/README_CN.md) |
| [`document`](document) | 更快更安全的 `DocumentFile`，同时支持 SAF 和普通文件 | [说明](document/README_CN.md) |
| [`treeview`](treeview) | 基于单个 `RecyclerView` 的树形视图（文件树） | [说明](treeview/README_CN.md) · [设计](treeview/DESIGN_CN.md) |
| [`crash`](crash) | 捕获 Java 崩溃、native 崩溃和 ANR | [说明](crash/README_CN.md) · [设计](crash/DESIGN_CN.md) |
| [`bypass`](bypass) | 解除隐藏 API 限制（**app 尚未使用**） | [说明](bypass/README_CN.md) |

「说明」讲模块做什么、怎么用、怎么配置、有哪些限制；「设计」讲实现原理，只在把模块引入自己的项目或修改它时才需要看。

## 已知限制

- native 代码只构建 `arm64-v8a`（见上文）。
- 高亮资源在首次启动时下载，下载完成前没有语法高亮。
- 应用不附带任何语言服务器；没有文件路径的文档（如网盘中的文件）不会发送给语言服务器。
- 文件按 UTF-8 读写（保留 BOM）。
- 没有「所有文件访问」权限时，路径补全和项目样式文件只能通过文件树中打开的文件夹使用。
- 各模块自己的限制见其说明。

## 第三方代码

| 项目 | 许可证 | 用途 |
| --- | --- | --- |
| [tree-sitter](https://github.com/tree-sitter/tree-sitter) | MIT | 由 `treesitter/get_treesitter_source.py` 拉取 |
| [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter) | MIT | `treesitter` 模块的来源，见 [LICENSE](treesitter/LICENSE-kotlin-tree-sitter) |
| [VS Code](https://github.com/microsoft/vscode) 的 piece tree | MIT | 移植为 `piecetable` 模块，见 [LICENSE](piecetable/LICENSE-vscode) |
| [lsp4j](https://github.com/eclipse-lsp4j/lsp4j) | EPL-2.0 或 EDL-1.0 | `lsp` 模块的依赖，见 [LICENSE](lsp/LICENSE-lsp4j) |
| [nvim-treesitter](https://github.com/nvim-treesitter/nvim-treesitter) 的查询文件 | Apache-2.0 | 包含在下载的高亮资源中 |
| [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) | MIT | 包含在下载的高亮资源中 |

各项目的许可证以其仓库中的说明为准。

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

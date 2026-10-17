# X Editor

一个 Android 代码编辑器，面向大文件优化：8.8 MB、23.9 万行的 `sqlite3.c` 也能流畅地滚动、折叠和编辑。

- **文本**：存放在 piece tree 中，与 VS Code 的文本缓冲区是同一种数据结构。
- **语法高亮**：由 tree-sitter 配合 nvim-treesitter 的查询文件完成，计算全部在主线程之外进行。
- **文件**：通过存储访问框架（SAF）打开和保存。

English version: [README.md](README.md).

## 功能

**编辑**

- 撤销与重做。重新打开文件时，如果文件内容没有变化，会恢复编辑会话（光标位置和历史记录）。
- 自动折行、行号（可固定行号栏）、缩进线、代码块背景、颜色值预览。
- 代码折叠：根据 tree-sitter 的 `folds` 查询，或者按缩进。
- 括号匹配；符号自动配对，配对规则可以自定义。
- 查找与替换，支持正则表达式。
- **语言服务器（LSP）**：服务器由用户自行配置，例如 clangd、pyright、lua-language-server 等。支持补全、诊断（波浪线）、悬停信息、签名提示、转到定义、查找引用、重命名、代码操作、格式化、文档大纲。没有服务器时，参照 blink.cmp 的做法，对文本中的单词做模糊补全。见[代码补全](#代码补全语言服务器)。
- **跳转到行**：输入 `行号` 或 `行号:列号`。预览卡片显示目标行的内容，滑块覆盖整个文件，快捷按钮可以直接跳到首行、当前行或末行。跳转后该行会短暂高亮，方便一眼找到落点。
- **选择浮条**：
  - 主栏：剪切、复制、粘贴、全选、搜索；
  - 溢出菜单：分享，以及所有能处理 `PROCESS_TEXT` 的应用（翻译、朗读、各类助手等）；
  - 溢出菜单高度与系统工具条一致（最多显示 4 行半），其余项滚动显示。
- **控制台**：类似 VS Code 的面板。"输出"页按频道显示日志（X Editor、语言服务器、各服务器自己的日志、构建）；"错误"页汇总已打开文档的诊断和最近一次构建的错误，点击即可跳转；"构建"页在 Termux 中运行命令（如 `clang rbtree.c -o $PREFIX/tmp/rbtree && $PREFIX/tmp/rbtree`），并从输出中识别 gcc、clang 和 Python 的报错。拖动面板顶部（标题和标签页）才能拉下关闭面板，在内容区滚动不会把它关掉。
- 双指缩放；拖动选择手柄时显示放大镜；键盘上方有一行快捷键。
- 只读模式与编辑模式。可在标签页中同时打开多个文件，侧边抽屉中有文件树。

**外观**

- 浅色、深色、纯黑主题，支持系统动态取色。
- 可以设置高亮主题（内置 astrodark 和 astrolight）、编辑器字体（内置 JetBrains Mono 和 Source Code Pro，也可以导入自己的字体）、字号和 Tab 宽度。

**稳定性**

- Java 崩溃、native 崩溃和 ANR 都会被捕获，先写入磁盘，再在独立的 `:crash` 进程中展示，可以复制、导出或上报。

## 性能设计

主线程上从不执行 tree-sitter 查询。相关实现主要在 `app/.../highlight` 的 `TreeSitterEngine`、`CaptureStore`，以及 `editor/.../render` 的 `RowCache`。

- **高亮在后台计算**：
  - 文本按 2048 个字符一块，在工作线程上计算高亮。计算时使用语法树的副本和文本快照。
  - 主线程只负责把已经算好的块拼成 span。块还没算好的行保持原来的显示；刚滚动进屏幕的行可能先显示一帧纯文本。
- **编辑不必等查询**：
  - 编辑时，高亮块随文本一起平移。在标识符末尾输入字母，新字符立即沿用它的颜色。
  - 下一次解析完成后，只有被报告为有变化的块才会重新查询。
- **不会闪烁**：每个已录制的行都记录着自己当前显示的内容（`RowCache`）。已经着色的行，永远不会被纯文本替换。回车或合并行时，下方各行的录制内容直接整体移动，不再重新录制。
- **解析不占用主线程**：
  - 解析在专用线程上运行，新旧语法树的比较、旧树的释放也在那里完成。
  - 缩进线和折叠标记在工作线程上计算。结果出来之前，编辑器继续显示上一次的结果，并让它们跟着编辑一起移动。
- **谓词在原生代码中判断**：
  - `eq?`、`any-of?`、`lua-match?`、`contains?`、`has-ancestor?`、`has-parent?`、`kind-eq?` 都在 C++ 中求值，不必把匹配交回 Kotlin。
  - 其中 `lua-match?` 移植自 LuaJIT 的模式匹配，与 LuaJIT 对比测试了 12 万多个用例。
  - 只有 Vim 正则（`match?`）仍在 Kotlin 中处理。
- **跳过不可能着色的注释**：只有可能产生高亮的注释才会交给注释语法解析，比如含有 TODO/NOTE 等关键词、`#数字` 或 URL 的注释。这些条件从已安装的查询文件中推导出来。在 `sqlite3.c` 中，92% 的注释因此被跳过。

在开发设备上滚动和编辑 `sqlite3.c`：卡顿帧比例从 31% 降到 4%，帧耗时 90% 分位从 89 ms 降到 18 ms。

## 模块

| 模块 | 说明 | 是否被 app 使用 |
| --- | --- | --- |
| [`app`](app) | 应用本身：各个界面、设置、tree-sitter 语法高亮、高亮资源的下载 | — |
| [`editor`](editor) | `EditorView`：排版（折行与不折行、折叠）、渲染、输入与输入法、选择、浮条、代码折叠、缩进线、撤销 | 是 |
| [`piecetable`](piecetable/README_cn.md) | piece tree 文本缓冲区，移植自 VS Code | 是 |
| [`treesitter`](treesitter) | tree-sitter 的 Kotlin 绑定（通过 JNI），包含原生谓词判断 | 是 |
| [`document`](document/README_cn.md) | AndroidX `DocumentFile` 的重写版，更快更安全，同时支持 SAF 文档和普通文件 | 是 |
| [`treeview`](treeview/README_cn.md) | 基于单个 `RecyclerView` 的树形视图，用于文件树 | 是 |
| [`crash`](crash/README_cn.md) | 崩溃捕获：Java、native、ANR | 是 |
| [`lsp`](lsp/README_cn.md) | 基于 lsp4j 的语言服务器协议（LSP）客户端：补全、诊断、悬停、签名提示、定义、引用、重命名、代码操作、格式化、符号 | 是 |
| [`bypass`](bypass) | 通过 `ContentProvider` 和 JNI 访问 Android 隐藏 API | 尚未接入 |

## 构建

环境要求：

- JDK 21
- Android SDK：platform 36，build tools 37.0.0
- NDK 29.0.14206865，以及 CMake 4.1.2 或更高版本，用于构建 `treesitter` 和 `crash` 模块的原生代码
- Python 3 和 git，用于拉取 tree-sitter 源码
- Gradle 9 或更高版本（Android Gradle 插件为 9.2.0，Kotlin 为 2.3.21）

各项版本见 [`libs.versions.toml`](libs.versions.toml)。

构建步骤：

```sh
# 1. 拉取 tree-sitter 源码，克隆到 treesitter/src/main/cpp/tree-sitter
cd treesitter && python3 get_sources.py && cd ..

# 2. 生成 Gradle wrapper（仓库中没有附带），也可以直接用 Android Studio 构建
gradle wrapper

# 3. 构建应用
./gradlew :app:assembleDebug
```

原生代码只构建 `arm64-v8a`（见 `treesitter/build.gradle.kts` 中的 `abiFilters`）。如果需要在其他设备或模拟器上运行，在这里添加对应的 ABI。

### 高亮资源

语法库、查询文件和主题不打包在 APK 中。首次启动时，应用会从 [lzhiyong/tree-sitter-parser](https://github.com/lzhiyong/tree-sitter-parser/releases) 的发布页下载三个资源包，地址配置在 [`app/src/main/assets/treesitter.json`](app/src/main/assets/treesitter.json)：

| 资源包 | 内容 |
| --- | --- |
| `tree-sitter-configs.zip` | 文件类型映射（`config/filetypes.json`）和高亮主题（`themes/`） |
| `tree-sitter-languages-{arch}.zip` | 对应设备架构的语法库 |
| `tree-sitter-queries.zip` | 查询文件：`highlights`、`injections`、`folds`、`indents`、`locals` |

下载支持断点续传；只有当资源包的 ETag 变化时才会重新下载。在**设置 → 语法高亮资源**中可以查看它们的状态。

## 代码补全（语言服务器）

应用不附带任何语言服务器，因为那样会让 APK 远超体积限制。服务器在 `servers.json` 中配置：

- 应用获得"所有文件访问权限"时，文件位于 `/storage/emulated/0/xedit/lsp/`；
- 否则位于 `Android/data/x.editor.app/files/lsp/`。

首次使用时，应用会在该目录写入一个示例文件，下面两种方式各有一个示例，默认都处于关闭状态（`"enabled": false`）。每个服务器配置两项：它负责哪些扩展名的文件，以及如何连接它。

**方式一：在 Termux 中运行，通过 socket 连接**（`"type": "socket"`）。对于 Termux 已经打包好的服务器，这是最简单的方式。

```sh
pkg install clang socat          # clangd，以及把 TCP 转接到其标准输入输出的 socat
termux-setup-storage             # 让 Termux 能访问你编辑的文件
echo "allow-external-apps = true" >> ~/.termux/termux.properties
```

```json
{
  "id": "clangd",
  "extensions": ["c", "h", "cc", "cpp", "hpp"],
  "connection": {
    "type": "socket", "host": "127.0.0.1", "port": 5007,
    "termux": {
      "command": "socat",
      "arguments": ["TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork", "EXEC:clangd --background-index"]
    }
  }
}
```

如果端口上还没有服务在监听，应用会通过 Termux 的 `RUN_COMMAND` 服务执行 `termux` 中配置的命令，然后再连接。系统弹出授权请求时，请允许 X Editor "在 Termux 中运行命令"。如果没有配置 `termux` 项，就需要你自己在 Termux 中先启动转接。

Python 的 pyright 也用同样的方式，换一个端口：

```sh
pkg install nodejs && npm install -g pyright
```

```json
{
  "id": "pyright",
  "extensions": ["py", "pyi"],
  "initializeTimeout": 120,
  "connection": {
    "type": "socket", "host": "127.0.0.1", "port": 5008,
    "termux": {
      "command": "socat",
      "arguments": [
        "-d", "-d", "-lf", "/data/data/com.termux/files/home/socat-pyright.log",
        "TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork",
        "EXEC:/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/lib/node_modules/pyright/langserver.index.js --stdio"
      ]
    }
  }
}
```

这份配置和 clangd 有三处不同：

- **用 node 的绝对路径启动 pyright，而不是 `EXEC:pyright-langserver --stdio`。** `pyright-langserver` 是一个以 `#!/usr/bin/env node` 开头的脚本。在 Termux 的终端里，这个 shebang 会被改写成 Termux 自己的路径，所以在前台运行一切正常；但应用通过 `RUN_COMMAND` 在后台启动 socat 时，这层改写不一定生效，pyright 一启动就会失败。表现是连接成功，却等不到 `initialize` 的回应，直到超时。clangd 是原生程序，不受影响。
- **`-lf` 把 socat 的日志写进 `~/socat-pyright.log`。** 后台运行时看不到输出，出了问题就看这个文件。
- **`initializeTimeout` 设为 120 秒。** pyright 首次启动要加载大量 JavaScript，在手机上可能比较慢。

改完配置之前，先确认入口文件存在：

```sh
ls $PREFIX/lib/node_modules/pyright/langserver.index.js
```

其他通过 npm 安装的 Node.js 服务器（如 `typescript-language-server`、`bash-language-server`）也是脚本，同样建议用 `node` 加入口文件的绝对路径启动。

### 连接不上时

1. **先关掉端口上残留的 socat。** 应用只有在端口空闲时才会让 Termux 用新配置启动 socat；只要端口上还有旧的 socat，应用就会直接连上它，修改的配置不会生效。日志里如果没有 "asking Termux to run socat" 这一行，就说明连上的是已经在运行的那个。

   ```sh
   pgrep -af socat                    # 查看正在运行的 socat 及其参数
   pkill -f 'TCP-LISTEN:5008'         # 只关掉 5008 端口的，不影响其他服务器
   ```

2. **在前台运行 socat，观察输出。** 让它停在 Termux 里，再回到 X Editor 打开文件。应用会连上这个前台的 socat，服务器的报错和子进程的退出码都会显示在 Termux 中：

   ```sh
   socat -d -d TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork 'EXEC:pyright-langserver --stdio'
   ```

   前台能用、后台不行，通常就是上面说的 shebang 问题，改用 node 的绝对路径即可。

3. **查看后台日志**：`cat ~/socat-pyright.log`，重点看 `child ... exited with status ...` 这样的行。

4. **防止 Termux 在后台被系统杀掉**：在系统设置中把 Termux 设为"不限制电池优化"；如果服务器用着用着断开，可以在 Termux 中运行一次 `termux-wake-lock`。

另外，`pyright-langserver --version` 会报错 "Connection input stream is not set"，这是正常的：它不支持 `--version`，只说明需要指定 `--stdio` 等通信方式。要查看版本，请用 `pyright --version`。

**方式二：由应用启动进程**（`"type": "process"`）：

```json
{
  "id": "lua",
  "extensions": ["lua"],
  "connection": { "type": "process", "command": ["lua-language-server"], "env": {} }
}
```

- 服务器由你自行下载：可执行文件放进 `xedit/lsp/bin/`，它依赖的库放进 `xedit/lsp/lib/`。
- 文件有变化时，应用会把它们复制到自己的私有存储中，再通过系统动态链接器（`/system/bin/linker64`）启动。这是因为 Android 10 及以上版本不允许应用直接执行存储中的文件。
- 因此，服务器必须是为 Android（bionic）编译的动态链接程序。Termux 自己的软件包依赖 Termux 的库，请改用方式一运行。

可选配置项：

- `languageIds`：按扩展名指定 LSP 语言 ID，例如 `{ "h": "cpp" }`。
- `root`：项目根目录。不设置时，取文件上方最近一个含有标记文件（如 `.git`、`compile_commands.json`）的目录。
- `rootMarkers`：要查找的标记文件。
- `initializationOptions`：原样传给服务器。
- `initializeTimeout`：服务器启动时间的上限，单位为秒，默认 60。pyright 这类 Node.js 服务器在手机上首次启动可能比较慢。

补全的触发时机：输入服务器的触发字符（如 `.`、`->`、`::`）后立即请求；输入单词时，在短暂停顿后请求。继续输入时，列表在本地过滤。选中一项后，编辑器会：

- 替换当前单词；
- 展开代码片段，光标停在第一个跳转点并选中其中的占位内容；
- 执行附加编辑，例如插入 `#include`。

以上操作合并为一次可撤销的编辑。使用实体键盘时，方向键在列表中移动，Enter 或 Tab 选中，Esc 关闭。

也可以在代码中用 `LanguageServers.register(spec)` 注册服务器（位于 `app/.../lsp`）。注意文档必须位于服务器能按路径读取的位置：从共享存储打开的文件可以；来自其他文档提供者的文档（如网盘）不会发送给服务器。

## 测试

| 模块 | 测试类数量 | 覆盖内容 |
| --- | --- | --- |
| `editor` | 24 | 编辑历史与撤销、文档模型、查找、代码折叠、结构提供者、文本边界、行录制（`RowCache`，含"不闪烁"的随机属性测试）、各种排版（不折行、折行、折叠、换行计算、制表位）、光标移动、输入与输入法、自动配对、缩进编辑、浮条溢出菜单高度（`FloatingBarTest`）、结构平移 |
| `lsp` | 5 | 类型转换、代码片段、补全排序、客户端 |
| `document` | 3 | 文档文件与查询 |
| `app` | 7 | 输入时何时请求补全、文本单词补全、构建输出中的问题、运行命令、悬停信息的 Markdown、跳转到行、`servers.json` |
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

## 目录结构

```
xedit-app/
├── app/                        应用（x.editor.app）
│   └── src/main/java/x/editor/app/
│       ├── activity/           EditorActivity、SettingsActivity、CrashActivity
│       ├── controller/         文件列表、搜索面板、跳转到行
│       ├── highlight/          TreeSitterEngine、CaptureStore、StructureQuery、
│       │                       TreeSitterInjections、InjectionPrefilter、QueryPredicates
│       ├── service/            EditorService：文档读写、编辑会话、下载
│       ├── view/               CodeEditorView（接入 tree-sitter 的 EditorView）、抽屉、insets 处理
│       └── ...                 设置、下载、数据库、查找、会话、工具类
├── editor/                     编辑器视图（x.github.module.editor）
│   └── src/main/java/.../editor/
│       ├── view/               EditorView、FloatingBar、CodeStructure
│       ├── render/             TextRenderer、RowCache、FrameBudget、Gutter
│       ├── layout/             不折行、折行、折叠三种排版
│       ├── input/              手势、选择、输入法、自动配对
│       ├── structure/          代码折叠、缩进线、括号
│       └── model/              文档、编辑历史、查找结果
├── piecetable/                 文本缓冲区
├── treesitter/                 tree-sitter 绑定（Kotlin + C++）、get_sources.py
├── document/  treeview/  crash/  lsp/  bypass/
├── libs.versions.toml          插件和依赖库的版本
└── settings.gradle.kts
```

## 文档与编辑会话

当前打开哪个文档，由 `EditorActivity` 决定：

- 同一时间只加载一个文档。选择另一个文档时，会先取消正在进行的加载，等它完全停止后再开始新的加载。
- 只有完整加载过的文档才会被写回，因此一个文件的内容不可能被写进另一个文件。
- 保存操作依次执行，打开其他文档也不会取消它们。加载新文档前，会先等待尚未完成的保存。
- 如果写回上一个文档失败，该文档保持打开，修改内容不会丢失，同时显示错误信息。

每个文档的编辑会话，都和它所对应文件内容的 SHA-256 一起保存。打开文档时，文本总是从文件读取；只有哈希仍然一致时，才会恢复编辑会话。

## 第三方代码

- [tree-sitter](https://github.com/tree-sitter/tree-sitter)（MIT），由 `get_sources.py` 拉取。
- [kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter)（MIT），`treesitter` 模块的来源：Kotlin 代码参考其改写，JNI 层由 C 重写为 C++；见[许可证全文](treesitter/LICENSE-kotlin-tree-sitter)。
- [VS Code](https://github.com/microsoft/vscode) 的 piece tree（MIT），移植到了 `piecetable` 模块；见[许可证全文](piecetable/LICENSE-vscode)。
- [nvim-treesitter](https://github.com/nvim-treesitter/nvim-treesitter) 的查询文件，包含在下载的资源中。
- [lsp4j](https://github.com/eclipse-lsp4j/lsp4j)（EPL-2.0 或 EDL-1.0），`lsp` 模块依赖的库；见[许可证全文](lsp/LICENSE-lsp4j)。

各项目的许可证请以其仓库中的说明为准。

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

# 配置

> [X Editor](../README_cn.md) 文档 · [English](config.md)

应用读写的文件，以及用来配置它的文件。需要你自己编辑的文件都在共享存储的
`Android/data/x.editor.app/files/` 下：`lsp/servers.json`、`lsp/formatting.json`、
`lsp/packages.json`（见[工具链](toolchain_cn.md)）和 `snippets/`。

## 高亮资源

语法库、查询文件、主题和代码片段不打包在 APK 中。首次启动时，应用会从 [lzhiyong/tree-sitter-parser](https://github.com/lzhiyong/tree-sitter-parser/releases) 的发布页下载四个资源包，地址配置在 [`app/src/main/assets/treesitter.json`](../app/src/main/assets/treesitter.json)：

| 资源包 | 内容 |
| --- | --- |
| `tree-sitter-configs.zip` | 文件类型映射（`config/filetypes.json`）和高亮主题（`themes/`） |
| `tree-sitter-languages-{arch}.zip` | 对应设备架构的语法库 |
| `tree-sitter-queries.zip` | 查询文件：`highlights`、`injections`、`folds`、`indents`、`locals` |
| `tree-sitter-snippets.zip` | [friendly-snippets](https://github.com/rafamadriz/friendly-snippets)，用于代码补全 |

它们都保存在应用的 home 目录 `/data/data/x.editor.app/files/home` 下：前三个在 `treesitter/`，代码片段在 `lsp/friendly-snippets/`。文档会话（`sessions/`，JSON 格式）和崩溃日志（`logs/`、`native_crash/`）也在这里。

下载支持断点续传；只有当资源包的 ETag 变化时才会重新下载。在**设置 → 语法高亮资源**中可以查看它们的状态。

## 语言服务器（`servers.json`）

应用不附带任何语言服务器，因为那样会让 APK 远超体积限制。服务器在 `servers.json` 中配置：

配置文件位于 `Android/data/x.editor.app/files/lsp/`，服务器日志 `lsp.log` 也在这里。自定义代码片段在旁边的 `snippets/` 目录（见[自定义代码片段](#自定义代码片段)）。除此之外，应用不在共享存储上保存任何文件。

首次使用时，应用会在该目录写入一个示例文件，下面几种方式各有示例，默认都处于关闭状态（`"enabled": false`）。每个服务器配置两项：它负责哪些扩展名的文件，以及如何连接它。

**优先用你安装的，否则用 Termux 的**（`"type": "auto"`）。只写服务器的命令，不管它装在哪里：如果本应用的工具链里有它（在 usr/bin 中，见[工具链](toolchain_cn.md)），由应用直接启动；没有的话，就让 Termux 在 `port` 上启动一个桥接（`socat TCP-LISTEN:<port> EXEC:<命令>`；不写端口时按 id 在 5100～5899 中选一个）。示例文件中的第一个服务器就是这种写法。

```json
{ "id": "clangd", "extensions": ["c", "cpp", "h"],
  "connection": { "type": "auto", "command": ["clangd", "--background-index"], "port": 5009 } }
```

**在 Termux 中运行，通过 socket 连接**（`"type": "socket"`）：适合 Termux 已打包的服务器。
clangd 和 pyright 的配置，以及连接不上时的排查方法，见[示例：在 Termux 中运行语言服务器](example_cn.md)。

**由应用启动进程**（`"type": "process"`）：

```json
{
  "id": "lua",
  "extensions": ["lua"],
  "connection": { "type": "process", "command": ["lua-language-server"], "env": {} }
}
```

- `command` 填可执行文件的绝对路径，或应用 home 目录（`/data/data/x.editor.app/files/home`）下的相对路径。应用暂不负责安装服务器。
- 位于应用私有存储中的可执行文件会通过系统动态链接器（`/system/bin/linker64`）启动，因为 Android 10 及以上版本不允许应用直接执行这类文件。它依赖的库通过 `env` 中的 `LD_LIBRARY_PATH` 指定。
- 因此，服务器必须是为 Android（bionic）编译的动态链接程序。Termux 自己的软件包依赖 Termux 的库，请改用 socket 方式在 Termux 中运行（见[示例](example_cn.md)）。

可选配置项：

- `languageIds`：按扩展名指定 LSP 语言 ID，例如 `{ "h": "cpp" }`。
- `root`：项目根目录。不设置时，取文件上方最近一个含有标记文件（如 `.git`、`compile_commands.json`）的目录。
- `rootMarkers`：要查找的标记文件。
- `initializationOptions`：原样传给服务器。
- `initializeTimeout`：服务器启动时间的上限，单位为秒，默认 60。pyright 这类 Node.js 服务器在手机上首次启动可能比较慢。

## 格式化规则

**格式化文档**和**文档大纲**在选区或光标上方的浮动栏中（⋮ 菜单内），服务器支持时才显示；选中文字且服务器支持局部格式化时，显示为**格式化选中内容**。

格式化规则默认根据编辑器生成：缩进方式（空格或 Tab）和宽度取自编辑器设置，Go 和 Makefile 固定用 Tab，并去掉行尾空白、文件末尾保留一个换行。**菜单 → 开发工具 → 格式化规则**会打开 `servers.json` 旁边的 `formatting.json`，为当前文档的语言和所有已启用服务器的语言各生成一节，内容就是这些默认规则，直接在里面修改即可：

```json
{
  "cpp": {
    "tabSize": 4,
    "insertSpaces": true,
    "trimTrailingWhitespace": true,
    "insertFinalNewline": true,
    "trimFinalNewlines": true
  },
  "lua": { "tabSize": 2, "quote_style": "single" }
}
```

没写的规则、没有小节的语言，使用生成的默认值；三条空白规则写成 `null` 表示交给服务器决定；删掉某一节，下次打开时重新生成。其他键（如 `quote_style`）原样发给服务器。保存后立即重新读取。

项目自己的样式文件优先于这些规则：

- **`.editorconfig`** 由编辑器读取：从文档所在目录向上查找，直到遇到 `root = true`（按规范，越近的文件、越靠后的小节优先）。`indent_style`、`indent_size`、`tab_width` 决定在该文档中输入时的缩进；格式化时，它们连同 `trim_trailing_whitespace`、`insert_final_newline` 一起发给服务器，覆盖 `formatting.json`。
- **`.clang-format`**（或 `_clang-format`）由 clangd 处理：clangd 从文档路径向上查找并按它格式化，与发给它的选项无关。编辑器也会读取其中的 `IndentWidth`、`TabWidth`、`UseTab` 和 `BasedOnStyle`，让输入时的缩进与格式化结果一致（适用于 C、C++、Objective-C、Java、JavaScript、Proto、C#）。
- 其他格式化工具的配置（`rustfmt.toml`、ruff 或 black 用的 `pyproject.toml`、`.prettierrc`、`stylua.toml`）由各自的服务器读取。

服务器按文档路径读取样式文件，因此必须能读到项目目录：在 Termux 中运行的服务器需要先执行一次 `termux-setup-storage` 才能访问 `/sdcard`。编辑器自己按路径读取 `.editorconfig` 和 `.clang-format`，没有权限时通过文件树中打开的文件夹读取。每次格式化依据了哪些文件，会记录在 `lsp.log` 中。

## 自定义代码片段

friendly-snippets 每次更新都会被整体替换，所以不要直接修改它。自己的代码片段放在 `Android/data/x.editor.app/files/snippets/`，每种语言一个文件，格式与 VS Code 相同：

| 文件 | 作用范围 |
| --- | --- |
| `c.json`、`python.json` 等 | 某种语言，文件名用 friendly-snippets 中的语言名 |
| `<扩展名>.json` | friendly-snippets 没有对应语言的文件类型，例如 `just.json` 对应 `x.just` |
| `all.json` | 所有文件 |

**菜单 → 开发工具 → 自定义代码片段**会打开当前文档所属语言的文件；文件不存在时，先按模板创建一个带示例的文件。保存后立即重新读取，并提示读到了几个片段；如果有错误，提示第一个错误所在的行。文件中可以写注释，最后一项后面也可以带逗号。

自定义片段在补全列表中排在前面。如果和 friendly-snippets 中某个片段的前缀相同，会替换掉那个片段。没有下载 friendly-snippets 时，自定义片段也能正常使用。

也可以在代码中用 `LanguageServers.register(spec)` 注册服务器（位于 `app/.../lsp`）。注意文档必须位于服务器能按路径读取的位置：从共享存储打开的文件可以；来自其他文档提供者的文档（如网盘）不会发送给服务器。

## 快捷键（`keymap.json`）

**菜单 → 开发工具 → 快捷键**会按分组列出当前生效的所有快捷键，每项下方标出它在文件里对应的动作名；如果文件里有被忽略的条目，也会在顶部列出。点击其中的**编辑**会打开 `keymap.json`（位于 `lsp` 文件夹旁）；第一次打开时按模板生成，模板中列出了所有动作和默认按键。保存后立即重新读取，并提示读到了几条映射；如果有被忽略的条目，会提示是哪一条以及原因。

按键写法与 Neovim 相同：`<C-s>`、`<C-S-f>`、`<A-z>`（或 `<M-z>`）、`<D-k>`（Meta 键）、`<F5>`、`<C-/>`、`<C-Space>`，也支持连续按键，如 `<C-k>c`、`<leader>f`。第一个键必须带 Ctrl、Alt 或 Meta，或者是 F1–F12；后面的键可以是普通按键。

```jsonc
{
  "leader": "<C-k>",
  "timeoutlen": 1000,
  "keymaps": [
    { "keys": "<leader>f", "action": "format", "desc": "格式化文档" },
    { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**" },
    { "keys": "<C-y>", "action": "nop" }
  ]
}
```

与默认按键相同的映射会替换默认值；`"nop"` 用来取消某个默认按键。`"insert"` 按代码片段的语法在光标处插入文字。

同一份配置对实体键盘、自带 Ctrl/Alt 键的软键盘，以及快捷键栏上的 **Ctrl**、**Alt** 键都有效：点按后作用于下一个键（软键盘输入的字符或快捷键栏上的符号），长按则一直保持，直到再次点按。

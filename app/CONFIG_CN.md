# 配置

[English](CONFIG.md) | 中文 · [app](README_CN.md)

需要你自己编辑的配置都是 JSON 文件（允许注释和末尾逗号），位于共享存储：

```
Android/data/x.editor.app/files/
├── keymap.json              快捷键
├── snippets/<语言>.json     自定义代码片段
└── lsp/
    ├── servers/<id>.json    语言服务器，每个服务器一个文件
    ├── formatting/<语言>.json  格式化规则，每种语言一个文件
    ├── packages.json        工具链清单，见「工具链」
    └── lsp.log              语言服务器日志
```

这些文件都可以在**菜单 → 开发工具**的对应面板中编辑，也可以像普通文件一样在编辑器中打开，保存后同样立即生效。

## 开发工具面板

6 个面板（工具链、语言服务器、格式化规则、自定义代码片段、快捷键、创建主题）的操作方式相同：

| 我想… | 怎么做 |
| --- | --- |
| 看某一项的状态和问题 | 单击该行，弹出说明小窗（现状、怎么办、错误原因） |
| 编辑某一项 | 小窗中的「编辑」，或行尾 ⋮ / 长按 → 编辑 |
| 删除某一项 | ⋮ / 长按 → 删除（红色，需确认） |
| 新建 | 列表标题旁的「新建」：输入名称，用模板生成草稿，点「保存」才写入文件 |
| 改回模板 | 编辑区的「恢复模板」，只替换编辑框内容，可撤销 |

- 编辑框输入停顿后自动检查，只有**内容有改动且检查通过**时「保存」才可点；错误会给出行号。
- 有未保存的修改时切换条目或关闭面板，会先确认。
- 状态颜色：绿 = 正常，黄 = 等待或未保存，红 = 需要你处理，灰 = 你自己关掉的。
- 标题旁的 ⓘ 中有「导出全部配置」：把以上所有配置和自定义主题打包成 zip 保存到 `/sdcard/Download`。

## 语言服务器

应用不附带任何语言服务器。每个服务器一个文件 `lsp/servers/<id>.json`，文件名就是服务器 id。

语言服务器**不依赖**工具链面板的 `packages.json`：`servers/` 中每个格式正确的文件都会列出，也都可以启动。在面板中点**新建**输入名称即可：有同名预设（`clangd`、`pyright`、`lua-language-server`）时用预设，否则用通用模板生成草稿，`command` 默认就是这个名称。

### 配置文件

每个服务器写两件事：负责哪些扩展名，以及怎么连接。

```jsonc
// lsp/servers/clangd.json
{
  "enabled": true,
  "extensions": ["c", "cpp", "h"],
  "connection": {
    "type": "auto",
    "command": ["clangd", "--background-index", "--header-insertion=never"],
    "port": 5001
  }
}
```

`connection.type` 有三种：

| 类型 | 含义 | 适用 |
| --- | --- | --- |
| `auto`（推荐） | 自动检测：应用的工具链（usr/bin）里有这个命令就在本地启动，否则让 Termux 用 socat 桥接到 `port` 上再连接（Termux 中的服务器和 socat 需要你自己安装）。不写 `port` 时自动在 5100～5899 中选一个 | 大多数情况：不用关心服务器装在哪里 |
| `socket` | 连接 `host:port`。可写 `termux: { command, arguments }`，端口没人监听时应用让 Termux 用 socat 启动服务器 | 需要精确控制 Termux 中的启动命令 |
| `process` | 本地运行：应用直接启动 `command`，可带 `env`。命令写绝对路径、应用 home 下的相对路径，或 usr/bin 中的名字 | 装在本应用中、为 Android（bionic）编译的服务器 |

可选项：

| 键 | 作用 |
| --- | --- |
| `enabled` | 是否启用。新建和自带示例默认为 `false`，确认命令无误后改为 `true` |
| `languageIds` | 按扩展名指定 LSP 语言 ID，如 `{ "h": "cpp" }` |
| `root`、`rootMarkers` | 项目根目录；不写时取文件上方最近一个含 `.git`、`compile_commands.json` 等标记的目录 |
| `initializationOptions` | 原样传给服务器（不支持 `workspace/configuration`，设置都从这里传） |
| `initializeTimeout` | 启动超时，秒，默认 60。pyright 这类 Node.js 服务器首次启动较慢，可设为 120 |

### 什么时候运行

- 没有单独的启动、停止按钮：`enabled` 为 `true` 且打开了它负责的文件时自动启动。
- 启动后一直保持连接，切换、打开、关闭文件都不会重启；只有保存或删除它的配置文件、或应用退出时才断开。不同项目根目录的文件各用一个服务器实例。
- 一个扩展名由**第一个**负责它的已启用服务器处理（自带的在前，其余按 id 字母顺序）。被抢占的服务器在单击小窗中显示「扩展名被占用」。
- 每个启用的服务器要用不同端口，否则文件可能发给了别的服务器。
- 「未安装」的判断：`process` 在本地查找命令；`auto` 先查 usr/bin，再看打开面板时 Termux 报告的命令列表；`socket` 不检查。
- 工具链有变化（安装、卸载、修改 `packages.json`）时，服务器只会重新查找自己的程序，不会被停掉。比如本地程序被卸载后，`auto` 类型会改用 Termux 里的。
- 首次使用时自动写入 `clangd`、`pyright`、`lua-language-server` 三个示例（默认关闭）。已存在的文件不会被更新，需要时点「恢复模板」。

### 示例：在 Termux 中运行 clangd 和 pyright

Termux 已经打包好的服务器，用 socket 连接最简单。先在 Termux 中：

```sh
pkg install clang socat nodejs && npm install -g pyright
termux-setup-storage                                         # 让服务器能读到 /sdcard 上的项目
echo "allow-external-apps = true" >> ~/.termux/termux.properties
```

```jsonc
// lsp/servers/pyright.json
{
  "enabled": true,
  "extensions": ["py", "pyi"],
  "initializeTimeout": 120,
  "connection": {
    "type": "socket", "host": "127.0.0.1", "port": 5002,
    "termux": {
      "command": "socat",
      "arguments": [
        "-d", "-d", "-lf", "/data/data/com.termux/files/usr/tmp/socat-pyright.log",
        "TCP-LISTEN:5002,bind=127.0.0.1,reuseaddr,fork",
        "EXEC:/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/lib/node_modules/pyright/langserver.index.js --stdio"
      ]
    }
  }
}
```

系统弹出授权时，允许 X Editor「在 Termux 中运行命令」。

**为什么用 node 的绝对路径**：`pyright-langserver` 是 `#!/usr/bin/env node` 脚本，在 Termux 终端里能运行，但应用在后台启动 socat 时 shebang 不一定被改写，表现为连接成功却等不到 `initialize` 回应。`typescript-language-server`、`bash-language-server` 等 npm 安装的服务器同理。clangd 是原生程序，直接 `EXEC:clangd --background-index` 即可。

**连接不上时**：

1. **先关掉端口上残留的 socat**。端口上有旧的 socat 时，应用会直接连上它，新配置不生效（日志中没有 "asking Termux to run socat" 就是这种情况）：`pkill -f 'TCP-LISTEN:5002'`。
2. **在 Termux 前台运行 socat**，再回到应用打开文件，服务器的报错会直接显示：`socat -d -d TCP-LISTEN:5002,bind=127.0.0.1,reuseaddr,fork 'EXEC:pyright-langserver --stdio'`。前台能用、后台不行，通常就是上面的 shebang 问题。
3. **查看后台日志**：`cat $PREFIX/tmp/socat-pyright.log`，注意 `child ... exited with status ...`。
4. **防止 Termux 被系统杀掉**：设为「不限制电池优化」，或在 Termux 中运行 `termux-wake-lock`。

单击服务器的小窗中也会给出失败原因和建议（命令不存在、超时、没有权限、`allow-external-apps` 未开启、端口无人监听等），完整日志在控制台的「输出」页和 `lsp.log` 中。

## 格式化规则

**格式化文档**（有选区时为**格式化选中内容**）和**文档大纲**在浮条的 ⋮ 菜单中，服务器支持时才显示。

每种语言的规则写在 `lsp/formatting/<语言 id>.json` 中：

```jsonc
// lsp/formatting/lua.json
{
  "tabSize": 2,
  "insertSpaces": true,
  "trimTrailingWhitespace": true,   // 去掉行尾空白
  "insertFinalNewline": true,       // 文件末尾补一个换行
  "trimFinalNewlines": true,        // 去掉末尾多余空行
  "quote_style": "single"           // 其他键原样发给服务器
}
```

- 没有文件的语言、文件中没写的规则，使用模板默认值（缩进跟随编辑器设置，三条空白规则开启；`go`、`make` 固定用 Tab）。三条空白规则写 `null` 表示交给服务器决定。
- 面板中的**预览**按编辑框中的规则（不必先保存）请求服务器格式化，但不改动文档；服务器不可用或超时时，只按规则模拟缩进和空白。

**项目自己的样式文件优先于这些规则**：

- `.editorconfig`：从文档目录向上查找到 `root = true`。决定输入时的缩进，格式化时覆盖规则文件。
- `.clang-format`：由 clangd 自己读取；编辑器也读取其中的缩进设置，让输入时的缩进与格式化结果一致。
- `rustfmt.toml`、`pyproject.toml`、`.prettierrc`、`stylua.toml` 等由各自的服务器读取。

服务器按路径读取这些文件，Termux 中的服务器需要先执行 `termux-setup-storage`。超过 100 万字符的文件不发给服务器，只按规则处理缩进和空白。

## 自定义代码片段

friendly-snippets 每次更新会被整体替换，自己的片段请放在 `snippets/` 中，格式与 VS Code 相同：

| 文件 | 作用范围 |
| --- | --- |
| `c.json`、`python.json`… | 某种语言（文件名用 friendly-snippets 中的语言名） |
| `<扩展名>.json` | friendly-snippets 没有的文件类型，如 `just.json` |
| `all.json` | 所有文件 |

自定义片段在补全列表中排在前面，前缀相同时替换 friendly-snippets 中的片段。

## 快捷键

`keymap.json` 包含全部快捷键（包括自带的），可以任意修改或删除；第一次打开时按模板生成，可用动作列在文件的注释中。

```jsonc
{
  "version": 3,
  "leader": "<C-k>",
  "timeoutlen": 1000,
  "groups": ["Markdown"],
  "keymaps": [
    { "keys": "<C-s>", "action": "save" },
    { "keys": "<leader>f", "action": "format", "desc": "格式化文档" },
    { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**", "group": "Markdown" }
  ]
}
```

- 按键写法与 Neovim 相同：`<C-s>`、`<C-S-f>`、`<A-z>`、`<D-k>`（Meta）、`<F5>`、`<C-Space>`，支持连续按键 `<C-k>c`。第一个键必须带 Ctrl、Alt、Meta，或者是 F1–F12。
- 后面的映射覆盖前面相同按键的映射；`"action": "nop"` 取消某组按键；`"insert"` 按代码片段语法插入文字。
- `"group"` 决定在面板的哪个标签下显示：`edit`、`code`、`file`、`insert` 或自己的标签名。
- 文件有误时，修好之前使用模板中的快捷键；保存前面板会检查未知动作和写错的按键。
- 同一份配置对实体键盘、带 Ctrl/Alt 的软键盘，以及符号栏上的 **Ctrl**、**Alt**、**Shift** 键都有效（收在 Tab 旁的 **⌃** 中；点按作用于下一个键，长按保持，两种状态下按键文字都会高亮）。展开后，按完快捷键不会自动收起，所以 Ctrl+C 之后可以直接 Ctrl+V；再点一次 **⌃** 收起。Ctrl 或 Alt 处于按下状态且有选区时，软键盘不能删掉或挪走选区，保证 Ctrl+C 复制的是选中的文字。
- 默认快捷键中，`<C-o>` 打开文件，`<F11>` 切换全屏。版本 2 的文件第一次读取时会补上这两个（并升为版本 3）；如果文件里已经用了这两个键（映射成别的动作，或者用 `nop` 取消），则保持不变。
- 工具栏菜单中有快捷键的菜单项，会在标题后面显示快捷键。

## 高亮主题

- **导入 / 导出**：**设置 → 编辑器 → 高亮主题**对话框底部。导入的文件格式与下载的主题相同（参考 `astrodark.json`），必须含有 `captures` 对象；导入后自动设为当前主题。导出写到 `/sdcard/Download/<主题名>.json`。
- **创建**：**菜单 → 开发工具 → 创建主题**。新主题从模板开始（深色，带注释说明各项含义），编辑时用当前打开的文件实时预览；保存到 `home/local/themes/<名称>.json`，长按可设为高亮主题。名称只能包含字母、数字、`-` 和 `_`。
- 下载的主题在资源包更新时会被覆盖，不能在这里编辑或删除；导入和创建的主题不受影响。
- **删除**：导入和创建的主题在**菜单 → 开发工具 → 创建主题**中删除（主题的 ⋮ 或长按 → 删除，会先确认）；设置里的列表只用来选择主题。删除正在使用的主题后改用默认主题。

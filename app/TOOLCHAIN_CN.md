# 工具链

[English](TOOLCHAIN.md) | 中文 · [app](README_CN.md)

**菜单 → 开发工具 → 工具链**把编译器、构建工具和语言服务器安装到应用自己的 usr 目录。它只管理本应用里的工具，**不管理 Termux**：Termux 中的软件请你自己在 Termux 里安装。

## 先弄清三件事

**1. 面板只安装到应用自己的 usr。**

位置是 `/data/data/x.editor.app/files/usr`，结构与 Termux 的 `$PREFIX` 相同（`bin/`、`lib/`、`opt/`、`tmp/`…）。应用按清单中的地址下载、校验、解压。能装进来的只有**为 Android（bionic）编译的动态链接程序**；Termux 的软件包内置了 Termux 的路径，不能直接放进来。

**2. `packages.json` 是唯一的清单。**

它位于 `Android/data/x.editor.app/files/lsp/packages.json`，每一项写明：它提供哪些命令、版本、从哪里下载、SHA-256。面板只显示清单中的工具。**保存清单就会安装新增或改动的工具**，不需要另点按钮。

**3. 运行时先用应用自己的，没有再用 Termux 的。**

| 谁要运行程序 | 规则 |
| --- | --- |
| 控制台、编译并运行 | 命令用到的程序在 usr/bin 中都有时用应用自己的，否则在 Termux 中运行（需要你在 Termux 中装好） |
| 语言服务器 | **不依赖工具链清单**，见[配置](CONFIG_CN.md#语言服务器)。`auto` 类型在 usr/bin 中找命令，找不到就通过 socat 用 Termux 里的 |

工具链有变化（安装、卸载、改清单）时，语言服务器只会重新查找自己的程序，不会被停掉。

## 安装一个工具

1. 在面板中点**新建**，输入工具 id（通常就是命令名，如 `ripgrep`）。编辑框中会按模板生成一项草稿。
2. 补全内容，至少要有 `provides` 和适用于本设备的 `source`：
   ```jsonc
   {
     "id": "clangd-android",
     "name": "clangd for Android",
     "kind": "server",
     "version": "18.1.8",
     "provides": ["clangd"],
     "abi": {
       "arm64-v8a": { "source": "https://example.org/clangd-18.1.8-aarch64.tar.gz", "sha256": "…" }
     }
   }
   ```
3. 点**保存**。「保存」处会弹出进度窗口，显示每个工具的当前步骤（下载、校验、解压）；装好后该行显示为「已安装」。
4. 以后把 `version` 改成新版本并保存，就会更新到新版本；也可以点**更新**，把所有已安装的工具更新到清单中的版本。

**默认清单**列出了 clang、CMake/Ninja/make、OpenJDK 21/Gradle、Python、lua-language-server、pyright，但它们的 `source` 和 `sha256` 都是空的，所以显示「没有适用于本设备的来源」，**填上下载地址之前不能安装**。另有一个默认关闭的 clangd 示例。

## packages.json 字段

```jsonc
{
  "packages": [
    { "id": "rg", "name": "ripgrep", "kind": "tool", "version": "14.1.0",
      "provides": ["rg"], "source": "https://example.org/ripgrep-aarch64.tar.gz", "sha256": "…" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一标识，字母或数字开头，可含 `. _ + -` |
| `name`、`description` | 显示用；只改它们不会触发安装 |
| `kind` | `compiler`、`server` 或 `tool`，只影响显示 |
| `provides` | 它提供的命令 |
| `source` | 下载地址：`https://…`、`file://…`、文件路径或 `content://…`。为空时只列出，不能安装 |
| `sha256` | 下载内容的 SHA-256。填写后安装前会校验，不一致则不安装 |
| `abi` | 按设备 ABI 分别指定 `source` 和 `sha256`；取设备支持的第一个匹配项，没有匹配时用顶层的 `source` |
| `version` | 比已安装的新时显示为可更新；为空表示没有版本 |
| `version_command` | 点击该行时用来获取版本的命令；不写时对每个命令执行 `--version`（Java 为 `-version`） |
| `strip`、`into`、`bin`、`env` | 只在自动识别的目录结构不对时才需要，见下一节 |
| `enabled` | 写 `false` 隐藏这一项 |

修改已安装工具的 `id` 时，如果来源、ABI、版本和 SHA-256 都没变，已安装的内容会跟随新 id，不会重新下载。旧清单中的 `"termux"` 字段会被忽略。

## 下载的内容如何放进 usr

- **支持的格式**：zip、tar、tar.gz、`.deb`（取其中的 `data.tar(.gz)` 并去掉 Termux 的路径前缀），或单个可执行文件、脚本。**不支持** XZ、Zstandard、bzip2。
- **目录自动识别**：压缩包只有一个顶层目录时去掉这一层；内容已经按前缀布局（有 `bin/`、`lib/`）的直接放进 usr；其他的放进 `usr/opt/<id>`，并把其中 `bin/` 下的程序链接到 `usr/bin`。
- **识别不对时手动指定**：`strip` 去掉几层顶层目录，`into` 放到 usr 下的哪个目录（`""` 表示 usr 本身），`bin` 指定要链接到 usr/bin 的程序（`{ "clangd": "opt/clangd/bin/clangd" }`），`env` 添加运行时的环境变量。
- **断点续传**：下载保存在应用缓存中。停止、下载失败或应用被强行关闭后，id、版本、地址、SHA-256 都没变的话，下次接着下载；改了其中任何一项，旧的半截文件在保存清单时删除。安装完成后删除下载的文件。
- 解压时保留可执行权限和链接，不会写到 usr 之外。每个工具安装了哪些文件记录在 `usr/var/lib/xedit/packages/<id>.list`，卸载时按它删除。
- 程序运行时带有 `PREFIX`、`PATH`（usr/bin 在前）、`LD_LIBRARY_PATH`（usr/lib）、`HOME`、`TMPDIR`，以及各工具 `env` 中的变量。

## 面板

- 每行右侧的图标只表示状态：可安装、可更新、已安装；安装时换成进度环。没有来源的工具在行中注明「没有适用于本设备的来源」。
- 列表上方的**显示**可以切换「全部」或只看「已安装」。
- 单击一行查看版本信息；正在安装或更新时查看进度。
- 所有操作在 ⋮ / 长按中：编辑（定位到这一项）、安装、更新或重新安装、卸载、移除。**卸载**只删除安装的文件；**移除**还会从 `packages.json` 中删除这一项（保留文件中的注释）。
- 安装进行时可以关闭面板，安装在后台继续，通知栏显示进度；再次打开能看到进度。可以单独停止，或**全部停止**。

## 编译并运行

工具栏运行按钮和控制台「构建」页的命令：

- **在哪里运行**：命令用到的程序在 usr/bin 中都有时用应用自己的工具链（输出逐行显示，可以停止），否则在 Termux 中运行。没有选项可以固定其中一边。
- **在哪个目录运行**：当前文件所在项目的根目录。从文件目录向上查找，依次取含 Gradle settings 的目录、最外层的 CMakeLists.txt 目录、最近的含构建文件（CMake、Gradle、make、Meson、Cargo、Go、package.json）的目录；不越过 `.git`；都没有时为文件所在目录。
- **修改命令**：长按运行按钮。预览中同时显示命令和运行目录。**运行参数**只对当前文件（或当前项目）生效，追加在命令末尾，或放在模板中的 `${args}` 处。

命令模板中的变量：

| 变量 | 含义 |
| --- | --- |
| `${file}` | 当前文件，相对项目根目录的路径 |
| `${name}` | 文件名，不含扩展名 |
| `${path}`、`${dir}`、`${root}` | 文件绝对路径、文件所在目录、项目根目录 |
| `${tmp}` | 编译产物存放处：应用的 `usr/tmp` 或 Termux 的 tmp |
| `${exec}` | 启动编译产物的方式：用应用工具链时为系统链接器，在 Termux 中为空 |
| `${prefix}` | 运行处的前缀：应用的 usr 或 Termux 的 `$PREFIX` |
| `${gradle}` | 项目有 wrapper 时为 `sh ./gradlew`，否则为 `gradle` |
| `${args}` | 运行参数 |

例如 C 文件的默认命令：`clang -Wall "${file}" -o ${tmp}/${name} && ${exec}${tmp}/${name}`。

CMake、Gradle、Android 项目的源文件使用项目命令（项目中的其他文件仍用各自类型的命令）：

| 项目 | 默认命令 |
| --- | --- |
| CMake | `(test -f build/CMakeCache.txt \|\| cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug -DCMAKE_EXPORT_COMPILE_COMMANDS=ON) && cmake --build build -j4` |
| Gradle | `${gradle} assemble --console=plain` |
| Android | `${gradle} assembleDebug --console=plain` |

## 使用 Termux 中的程序

面板不检测、不安装、也不更新 Termux 中的软件。要用 Termux 里的编译器或语言服务器，请自己在 Termux 中准备好：

```sh
echo "allow-external-apps = true" >> ~/.termux/termux.properties
termux-setup-storage      # 让 Termux 中的程序能读写 /sdcard 上的项目
pkg install clang socat   # 以及你需要的其他工具
```

并在系统弹窗中允许 X Editor「在 Termux 中运行命令」。之后控制台命令在本应用缺少程序时会交给 Termux 运行，语言服务器可以通过 socat 连接 Termux 中的服务器（见[配置](CONFIG_CN.md#示例在-termux-中运行-clangd-和-pyright)）。

## 限制

- **usr 中的程序必须为 Android（bionic）编译、动态链接，并按本应用的前缀构建。**
- **Android 10 起应用不能直接执行自己数据目录中的文件。** 应用通过系统链接器（`/system/bin/linker64`）启动 usr/bin 中的程序、通过解释器启动脚本。会再启动其他程序的程序（clang 调用链接器、cmake 调用编译器）同样会被拒绝，除非在 `usr/lib` 中安装了 termux-exec 的库（`libtermux-exec*.so`）。
- 用应用自己的工具链构建共享存储上的项目，需要「所有文件访问」权限。
- 应用自己的工具链由开关 `Toolchain.LOCAL_ENABLED` 控制（默认开启）；关闭后所有服务器和命令都在 Termux 中运行。

## 排错

| 现象 | 原因与处理 |
| --- | --- |
| 显示「没有适用于本设备的来源」 | 该项的 `source` 为空，或 `abi` 中没有本设备的 ABI；填上下载地址后保存 |
| 保存后安装失败：SHA-256 不一致 | 地址指向的文件与 `sha256` 不符；核对两者 |
| 安装后程序无法运行 | 程序不是为 Android 编译的，或依赖的库不在 `usr/lib` 中 |
| clang、cmake 能启动但编译失败 | 它们需要再启动其他程序：安装 termux-exec 的库，或在 Termux 中构建 |
| 命令在 Termux 中运行而不是本应用 | 命令用到的程序有一个不在 usr/bin 中；在面板中安装它 |
| 版本获取不到 | 用 `version_command` 指定获取版本的命令 |

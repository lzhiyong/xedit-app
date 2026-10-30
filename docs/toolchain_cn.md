# 工具链

> [X Editor](../README_cn.md) 文档 · [English](toolchain.md)

**菜单 → 开发工具 → 工具链**只有一个面板：Termux（是否可用，缺什么时提供对应按钮）、优先使用哪一边（**优先使用**：自动、本应用、Termux），以及 `packages.json` 中的工具链和它们各自的状态。安装不需要手工填写任何内容：每个工具链是什么、从哪里来，都写在列表中，**编辑列表**可打开。

## 应用的 usr 目录

本应用自己的工具链安装在它的前缀目录 `/data/data/x.editor.app/files/usr`（`AppDirs.prefix`，相当于 Termux 的 `$PREFIX`），结构与 Termux 相同：`bin/`、`lib/`、`libexec/`、`include/`、`etc/`、`share/`、`opt/`、`tmp/`。参照 dpkg 的 `info/<包名>.list`，`var/lib/xedit/packages/` 中为每个工具链保存一个 `<id>.list`（它安装的所有路径，目录在前）和一个 `<id>.json`（版本、来源、链接、环境变量）。程序运行时带有 `PREFIX`、`PATH`（usr/bin 在前）、`LD_LIBRARY_PATH`（usr/lib）、`HOME`、`TMPDIR`，以及各工具链添加的环境变量。

## packages.json

`servers.json` 旁边的 `packages.json` 列出所有工具链：

```jsonc
{
  "packages": [
    { "id": "clang", "name": "clang, clangd", "kind": "compiler",
      "provides": ["clang", "clangd"], "termux": "pkg install clang" },
    {
      "id": "clangd-android", "name": "clangd for Android", "kind": "server",
      "version": "18.1.8",
      "provides": ["clangd"],
      "source": "https://example.org/clangd-18.1.8-android.tar.gz", "sha256": "…",
      "abi": {
        "arm64-v8a": { "source": "https://example.org/clangd-18.1.8-aarch64.tar.gz", "sha256": "…" },
        "x86_64": { "source": "https://example.org/clangd-18.1.8-x86_64.tar.gz", "sha256": "…" }
      },
      "strip": 1, "into": "opt/clangd", "bin": { "clangd": "opt/clangd/bin/clangd" },
      "env": { "CLANGD_FLAGS": "--header-insertion=never" }
    }
  ]
}
```

- `provides`：它提供的命令，也会在 Termux 中检测。`termux`：在 Termux 中如何安装，两边都没有时显示出来（需要你自己在 Termux 中执行）。
- `source`（`https://…`、`file://…` 或路径、`content://…`）和 `sha256`，或者在 `abi` 下按设备 ABI 分别指定（取设备支持的 ABI 中第一个匹配的，否则用 `source`）：安装到本应用时下载的内容。没有来源的工具链只在 Termux 中检测。
- `version`：列表中的版本比已安装的新时，显示为可更新。
- `strip`、`into`、`bin`、`env`：仅当自动识别的目录结构不对时才需要（只有一个顶层目录时去掉这一层；按前缀布局的直接放进 usr，其他的放进 `opt/<id>` 并把其中 `bin/` 下的程序链接到 usr/bin）。
- `enabled: false` 可隐藏某一项。首次写入的列表包含 clang、CMake 与 Ninja 和 make、OpenJDK 21 与 Gradle、Python、lua-language-server、pyright、socat（用于在 Termux 中检测），以及一个安装到本应用的示例（默认关闭）。

下载的内容可以是 zip、tar、tar.gz、`.deb`（其中的 `data.tar` 或 `data.tar.gz`，会去掉 Termux 的路径前缀），也可以是单个可执行文件或脚本；不支持 XZ、Zstandard、bzip2。解压时保留可执行权限、符号链接和硬链接，不会写到 usr 之外。安装完成后删除下载的安装包。

## 面板中的列表

每个工具链显示类型、名称和版本，以及所在位置（已安装到本应用、可安装到本应用、Termux 中可用，或在 Termux 中的安装方法），右侧一个图标：

- **安装**（下载箭头）：安装到本应用，进度显示在该行中；
- **更新**（向上箭头）：列表中有更新的版本，覆盖安装；
- **已安装**（对勾）：菜单中可**重新安装**或**卸载**。卸载时按 `.list` 删除它的文件和链接，再删除因此变空的目录；
- 灰色对勾：未安装到本应用，但 Termux 中有它的命令。

已安装但不在列表中的工具链也会显示，方便卸载。

## Termux

本应用不会在 Termux 中安装任何软件包，请在 Termux 中自行安装（`pkg install clang`）。打开面板时（以及点击刷新按钮时），本应用会在后台让 Termux 检测列表中的命令（`command -v`）并显示结果。Termux 需要在 `~/.termux/termux.properties` 中设置 `allow-external-apps = true`，本应用也需要获得在 Termux 中运行命令的权限；缺少时面板会提示，并提供对应按钮。

## 优先用自己的，否则用 Termux 的

- **语言服务器**：`"type": "auto"` 的服务器，usr/bin 中有就从这里启动，否则在 Termux 中启动（见上文）。`"type": "process"` 的命令如果只写了名字，也会先在 usr/bin 中查找。
- **命令**（控制台构建页、编译并运行）：命令用到的程序在 usr/bin 中都有时，用本应用的工具链运行（`gradlew`、`mvn` 还需要 `java`），否则在 Termux 中运行。工具链面板中的**优先使用**可以固定为本应用或 Termux。用本应用运行时，输出逐行显示，并可以停止。

以上功能由一个开关控制：`Toolchain.LOCAL_ENABLED`（已开启）。关闭后全部使用 Termux：auto 类型的服务器和所有命令都在 Termux 中运行，本地工具链的代码保持不变。

安装到 usr 中的程序必须是为 Android（bionic）编译、动态链接、并按本前缀构建的；Termux 的软件包内置了 Termux 的路径，应在 Termux 中运行。Android 10 及以上不允许应用直接执行自己数据目录中的文件：本应用通过系统链接器（`/system/bin/linker64`）启动 usr/bin 中的程序，通过解释器启动脚本，并在它运行的 shell 中把这些程序定义为函数。会再启动其他程序的程序（clang 调用链接器、cmake 调用编译器）同样会被拒绝，除非在 `usr/lib` 中安装了 termux-exec 的库（`libtermux-exec*.so`，以系统链接器模式预加载）；工具链面板会显示是否已安装。用本应用的工具链构建共享存储中的项目，需要给本应用“所有文件访问”权限；在 Termux 中构建则需要先执行 `termux-setup-storage`。

## 在项目根目录运行

“编译并运行”和控制台构建页的命令都在当前文件所在项目的根目录中运行。项目根目录的确定：从文件所在目录向上查找，依次取含 Gradle settings 文件的目录、嵌套 CMakeLists.txt 中最外层的目录、最近的含构建文件（CMake、Gradle、make、Meson、Cargo、Go、package.json）的目录；不越过 `.git`；都没有时为文件所在目录。

因此模板中的 `${file}` 是相对项目根目录的路径（如 `src/main.c`），`${root}` 是项目根目录；长按运行按钮时，预览会同时显示命令和运行目录。

CMake、Gradle 和 Android 项目有自己的命令，用于项目的源文件（CMake：C、C++、汇编；Gradle：Java、Kotlin、Groovy 和 Gradle 脚本；Android：以上这些，加上 C、C++、XML、AIDL）；项目中的其他文件（比如其中的 Python 脚本）仍使用各自类型的命令：

| 项目 | 默认命令 |
| --- | --- |
| CMake | `(test -f build/CMakeCache.txt \|\| cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug -DCMAKE_EXPORT_COMPILE_COMMANDS=ON) && cmake --build build -j4` |
| Gradle | `${gradle} assemble --console=plain` |
| Android（根目录或 `app` 的构建文件中有 Android 插件） | `${gradle} assembleDebug --console=plain` |

`${gradle}` 在项目带有 wrapper 时为 `sh ./gradlew`（共享存储中的文件不能直接执行），否则为 `gradle`，两者都需要 `java`。当前文件是项目源文件时，长按运行按钮设置的是项目的命令。ARM 汇编（`.s`、`.S`，以及经预处理的 `.asm`）由 clang 汇编并链接 libc，因此入口为 `main`：`clang "${file}" -o ${tmp}/${name} && ${exec}${tmp}/${name}`。`${prefix}` 是命令运行处的前缀：本应用的或 Termux 的。

“编译并运行”的模板新增 `${exec}`，表示启动编译出的程序的方式：用本应用工具链时是系统链接器，在 Termux 中为空（`${tmp}` 相应为 usr/tmp 或 Termux 的 tmp）。默认模板已经使用它：`clang … -o ${tmp}/${name} && ${exec}${tmp}/${name}`。

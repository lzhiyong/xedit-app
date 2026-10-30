# 示例：在 Termux 中运行语言服务器

> [X Editor](../README_cn.md) 文档 · [English](example.md)

## 在 Termux 中运行 clangd 和 pyright，通过 socket 连接

**在 Termux 中运行，通过 socket 连接**（`"type": "socket"`）。对于 Termux 已经打包好的服务器，这是最简单的方式。

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
        "-d", "-d", "-lf", "/data/data/com.termux/files/usr/tmp/socat-pyright.log",
        "TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork",
        "EXEC:/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/lib/node_modules/pyright/langserver.index.js --stdio"
      ]
    }
  }
}
```

这份配置和 clangd 有三处不同：

- **用 node 的绝对路径启动 pyright，而不是 `EXEC:pyright-langserver --stdio`。** `pyright-langserver` 是一个以 `#!/usr/bin/env node` 开头的脚本。在 Termux 的终端里，这个 shebang 会被改写成 Termux 自己的路径，所以在前台运行一切正常；但应用通过 `RUN_COMMAND` 在后台启动 socat 时，这层改写不一定生效，pyright 一启动就会失败。表现是连接成功，却等不到 `initialize` 的回应，直到超时。clangd 是原生程序，不受影响。
- **`-lf` 把 socat 的日志写进 `$PREFIX/tmp/socat-pyright.log`。** 后台运行时看不到输出，出了问题就看这个文件。
- **`initializeTimeout` 设为 120 秒。** pyright 首次启动要加载大量 JavaScript，在手机上可能比较慢。

改完配置之前，先确认入口文件存在：

```sh
ls $PREFIX/lib/node_modules/pyright/langserver.index.js
```

其他通过 npm 安装的 Node.js 服务器（如 `typescript-language-server`、`bash-language-server`）也是脚本，同样建议用 `node` 加入口文件的绝对路径启动。

## 连接不上时

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

3. **查看后台日志**：`cat $PREFIX/tmp/socat-pyright.log`，重点看 `child ... exited with status ...` 这样的行。

4. **防止 Termux 在后台被系统杀掉**：在系统设置中把 Termux 设为"不限制电池优化"；如果服务器用着用着断开，可以在 Termux 中运行一次 `termux-wake-lock`。

另外，`pyright-langserver --version` 会报错 "Connection input stream is not set"，这是正常的：它不支持 `--version`，只说明需要指定 `--stdio` 等通信方式。要查看版本，请用 `pyright --version`。

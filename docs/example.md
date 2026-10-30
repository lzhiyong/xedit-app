# Example: language servers in Termux

> Part of the [X Editor](../README.md) documentation · [中文](example_cn.md)

## clangd and pyright in Termux, over a socket

**In Termux, over a socket** (`"type": "socket"`). This is the easy way for servers that
Termux packages.

```sh
pkg install clang socat          # clangd, and a bridge from TCP to its stdio
termux-setup-storage             # so that Termux sees the files you edit
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

When nothing listens on the port, the app asks Termux to run the `termux` command, through
Termux's `RUN_COMMAND` service, then connects to it. Allow X Editor to *run commands in
Termux* when asked. Without a `termux` entry, start the bridge yourself.

pyright, for Python, the same way, on another port:

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

Three things differ from clangd:

- **pyright is started with node and the absolute path of its entry file, not with
  `EXEC:pyright-langserver --stdio`.** `pyright-langserver` is a script that starts with
  `#!/usr/bin/env node`. In Termux's terminal that shebang is rewritten to Termux's own
  path, so it works in the foreground; when the app has Termux start socat in the
  background (`RUN_COMMAND`), the rewriting may not happen, and pyright fails as soon as it
  starts. The connection succeeds, but `initialize` is never answered, until the timeout.
  clangd is a native binary and is not affected.
- **`-lf` writes socat's log to `$PREFIX/tmp/socat-pyright.log`.** Nothing is seen of a server run in
  the background: look there when something goes wrong.
- **`initializeTimeout` is 120 seconds.** pyright loads a lot of JavaScript the first time,
  which can be slow on a phone.

Check that the entry file is there before you use the configuration:

```sh
ls $PREFIX/lib/node_modules/pyright/langserver.index.js
```

Other Node.js servers installed with npm (`typescript-language-server`,
`bash-language-server`, ...) are scripts too: start them the same way, with `node` and the
absolute path of their entry file.

## When a server does not connect

1. **Stop the socat left on the port.** The app has Termux start socat with your
   configuration only when nothing listens on the port; while an old socat is there, the app
   connects to it, and a changed configuration has no effect. If the log has no line
   "asking Termux to run socat", the app connected to one that ran already.

   ```sh
   pgrep -af socat                    # the socats running, with their arguments
   pkill -f 'TCP-LISTEN:5008'         # only the one on 5008, not the other servers
   ```

2. **Run socat in the foreground, and watch what it prints.** Leave it running in Termux,
   go back to X Editor and open the file. The app connects to this socat, and the server's
   errors and the exit status of its process show in Termux:

   ```sh
   socat -d -d TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork 'EXEC:pyright-langserver --stdio'
   ```

   If it works in the foreground but not in the background, it is the shebang above: start
   the server with node and an absolute path.

3. **Read the log of the background socat**: `cat $PREFIX/tmp/socat-pyright.log`, and look for lines
   such as `child ... exited with status ...`.

4. **Keep Android from stopping Termux in the background**: let Termux run without battery
   restrictions in the system settings; if a server goes away after a while, run
   `termux-wake-lock` in Termux once.

`pyright-langserver --version` fails with "Connection input stream is not set": that is
expected, it has no `--version` and only says that it needs `--stdio` or another way to
talk. `pyright --version` shows the version.

# Configuration

English | [中文](CONFIG_CN.md) · [app](README.md)

The configuration you edit is JSON (comments and trailing commas allowed), on the shared storage:

```
Android/data/x.editor.app/files/
├── keymap.json                shortcuts
├── snippets/<language>.json   your own snippets
└── lsp/
    ├── servers/<id>.json      language servers, one file each
    ├── formatting/<language>.json  formatting rules, one file per language
    ├── packages.json          the toolchain list, see Toolchain
    └── lsp.log                language server log
```

Each of them can be edited in its panel under **Menu → Developer tools**, or opened in the editor like any file; saved either way, it takes effect at once.

## The developer tools panels

The six panels (Toolchain, Language servers, Formatting rules, My snippets, Shortcuts, Create theme) work the same way:

| I want to… | Do this |
| --- | --- |
| See how an entry is and what is wrong | Tap its row: a small window says how it is, what to do, and the error |
| Edit an entry | **Edit** in that window, or ⋮ / long press → Edit |
| Delete an entry | ⋮ / long press → Delete (red, asks first) |
| Add one | **New** by the list's title: type a name, a draft is made from the template, and nothing is written until **Save** |
| Go back to the template | **Restore template** in the editor: replaces only the editor's text, and can be undone |

- The editor checks its text when typing pauses; **Save** works only when the text **has changed and is right**. Errors give their line.
- Switching entries or closing the panel with unsaved changes asks first.
- Colors: green = fine, yellow = waiting or unsaved, red = needs you, gray = turned off by you.
- ⓘ by the title has **Export all configuration**: everything above plus your own themes, zipped into `/sdcard/Download`.

## Language servers

No language server comes with the app. Each one is a file, `lsp/servers/<id>.json`; the file name is the server's id.

Language servers **do not depend** on the toolchain panel's `packages.json`: every right file in `servers/` is listed and can be started. Tap **New** in the panel and give a name: with a preset of that name (`clangd`, `pyright`, `lua-language-server`) the draft is the preset, otherwise the general template, with the name as its `command`.

### The file

Each server says two things: which extensions it takes, and how to reach it.

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

`connection.type` is one of three:

| Type | What it does | For |
| --- | --- | --- |
| `auto` (recommended) | Found by itself: if the app's toolchain (usr/bin) has the command it runs locally; otherwise Termux is asked to bridge it with socat on `port`, and the app connects (install the server and socat in Termux yourself). Without `port`, one in 5100–5899 is chosen | Most cases: where the server is installed does not matter |
| `socket` | Connects to `host:port`. With `termux: { command, arguments }`, the app has Termux start the server with socat when nothing listens on the port | Full control of what Termux starts |
| `process` | Local: the app starts `command`, with `env`. The command is an absolute path, a path under the app's home, or a name in usr/bin | Servers installed in the app, built for Android (bionic) |

Optional keys:

| Key | What it does |
| --- | --- |
| `enabled` | On or off. New files and the examples are `false`; set it to `true` once the command is right |
| `languageIds` | The LSP language id by extension, such as `{ "h": "cpp" }` |
| `root`, `rootMarkers` | The project root; by default the nearest directory above the file with `.git`, `compile_commands.json` or another marker |
| `initializationOptions` | Passed to the server as they are (`workspace/configuration` is not supported, so settings go here) |
| `initializeTimeout` | Start-up timeout in seconds, 60 by default. Node.js servers such as pyright start slowly the first time; 120 is safer |

### When servers run

- There is no start or stop button: a server starts when `enabled` is `true` and a file it takes is open.
- Once started it stays connected; switching, opening and closing files never restart it. It is disconnected only when its file is saved or deleted, or the app exits. Files under different project roots get a server instance each.
- An extension goes to the **first** enabled server that takes it (the built-in ones first, then by id). A server whose extensions are all taken shows "extension taken" in its window.
- Every enabled server needs a port of its own, or files may go to the wrong server.
- "Not installed": `process` looks for its command locally; `auto` looks in usr/bin, then in the commands Termux reported when the panel opened; `socket` is not checked.
- When the toolchain changes (an install, an uninstall, an edit of `packages.json`), servers only look for their programs again and are not stopped. A local program uninstalled, an `auto` server goes on with Termux's.
- On first use, examples for `clangd`, `pyright` and `lua-language-server` are written (off). Existing files are never updated; use **Restore template** if you want the new example.

### Example: clangd and pyright in Termux

Servers that Termux packages are easiest over a socket. In Termux first:

```sh
pkg install clang socat nodejs && npm install -g pyright
termux-setup-storage                                         # so servers can read projects on /sdcard
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

When Android asks, allow X Editor to "run commands in Termux".

**Why node's absolute path**: `pyright-langserver` is a `#!/usr/bin/env node` script. It runs in Termux's terminal, but when the app starts socat in the background the shebang is not always rewritten; the connection succeeds and `initialize` never answers. The same goes for `typescript-language-server`, `bash-language-server` and other npm servers. clangd is a native program: `EXEC:clangd --background-index` is enough.

**When it does not connect**:

1. **Stop a socat left on the port.** If an old socat listens there, the app connects to it and the new configuration is not used (the log then has no "asking Termux to run socat"): `pkill -f 'TCP-LISTEN:5002'`.
2. **Run socat in Termux in the foreground**, then open a file in the app; the server's errors show in Termux: `socat -d -d TCP-LISTEN:5002,bind=127.0.0.1,reuseaddr,fork 'EXEC:pyright-langserver --stdio'`. Working in the foreground but not in the background is usually the shebang above.
3. **Read the background log**: `cat $PREFIX/tmp/socat-pyright.log`, looking for `child ... exited with status ...`.
4. **Keep Android from killing Termux**: set it to unrestricted battery use, or run `termux-wake-lock` in Termux.

A server's window also gives the reason it failed with advice (command missing, timeout, no permission, `allow-external-apps` off, nothing listening on the port); the full log is in the console's Output tab and in `lsp.log`.

## Formatting rules

**Format document** (**Format selection** with a selection) and **Document outline** are in the ⋮ of the selection bar, when the server supports them.

The rules of a language are in `lsp/formatting/<language id>.json`:

```jsonc
// lsp/formatting/lua.json
{
  "tabSize": 2,
  "insertSpaces": true,
  "trimTrailingWhitespace": true,
  "insertFinalNewline": true,
  "trimFinalNewlines": true,
  "quote_style": "single"           // other keys go to the server as they are
}
```

- A language without a file, and rules left out, use the template's defaults (indentation from the editor's settings, the three whitespace rules on; `go` and `make` always use tabs). `null` for a whitespace rule leaves it to the server.
- **Preview** in the panel asks the server to format with the rules in the editor (saved or not) without touching the document; when the server is unavailable or times out, indentation and whitespace are worked out from the rules alone.

**A project's own style files come before these rules**:

- `.editorconfig`: looked for from the document's directory up to `root = true`. It sets the indentation while typing, and overrides the rule file when formatting.
- `.clang-format`: read by clangd itself; the editor reads its indentation too, so typing indents as formatting will.
- `rustfmt.toml`, `pyproject.toml`, `.prettierrc`, `stylua.toml` and the like are read by their servers.

Servers read these files by path, so a server in Termux needs `termux-setup-storage` first. Files over a million characters are not sent to the server; only indentation and whitespace are worked out.

## Your own snippets

friendly-snippets is replaced whole by every update, so keep your snippets in `snippets/`, in VS Code's format:

| File | Applies to |
| --- | --- |
| `c.json`, `python.json`, ... | One language (the name friendly-snippets uses) |
| `<extension>.json` | A file type friendly-snippets does not have, such as `just.json` |
| `all.json` | Every file |

Your snippets come first in completion, and replace a friendly-snippets snippet with the same prefix.

## Shortcuts

`keymap.json` holds every shortcut, the built-in ones included; any of them can be changed or removed. It is written from the template the first time, with the available actions listed in its comments.

```jsonc
{
  "version": 3,
  "leader": "<C-k>",
  "timeoutlen": 1000,
  "groups": ["Markdown"],
  "keymaps": [
    { "keys": "<C-s>", "action": "save" },
    { "keys": "<leader>f", "action": "format", "desc": "Format document" },
    { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**", "group": "Markdown" }
  ]
}
```

- Keys are written as in Neovim: `<C-s>`, `<C-S-f>`, `<A-z>`, `<D-k>` (Meta), `<F5>`, `<C-Space>`, and sequences such as `<C-k>c`. The first key has to have Ctrl, Alt or Meta, or be F1–F12.
- A later mapping replaces an earlier one with the same keys; `"action": "nop"` takes keys away; `"insert"` inserts text in snippet syntax.
- `"group"` is the tab it is listed under: `edit`, `code`, `file`, `insert` or a name of your own.
- While the file is wrong, the template's shortcuts are used; the panel checks unknown actions and misspelt keys before saving.
- The same keymap works for hardware keyboards, soft keyboards with Ctrl/Alt, and the **Ctrl**, **Alt**, **Shift** of the extra keys (folded into **⌃** by Tab; a tap applies to the next key, a long press holds, and either lights the key up). Once out, they stay out after a shortcut, so Ctrl+C can be followed by Ctrl+V; tap **⌃** to fold them. While Ctrl or Alt is on and text is selected, the soft keyboard cannot take the selection away, so Ctrl+C copies what is selected.
- Among the defaults: `<C-o>` opens a file, `<F11>` turns full screen on or off. A file of version 2 gets these two the first time it is read (and becomes version 3), unless their keys are already used in it, mapped to something else or taken away with `nop`.
- Items of the toolbar's menu that have a shortcut show it after their title.

## Highlight themes

- **Import / export**: at the bottom of **Settings → Editor → Highlight theme**. An imported file has the format of the downloaded themes (see `astrodark.json`) and needs a `captures` object; once imported it becomes the theme. Export writes `/sdcard/Download/<theme>.json`.
- **Create**: **Menu → Developer tools → Create theme**. A new theme starts from a template (dark, with comments saying what each part is) and is previewed live on the file open in the editor; it is saved to `home/local/themes/<name>.json`, and a long press makes it the highlight theme. Names may use letters, digits, `-` and `_`.
- Downloaded themes are replaced when their package updates and cannot be edited or deleted here; imported and created themes are not touched.
- **Delete**: imported and created themes are deleted in **Menu → Developer tools → Create theme** (⋮ or a long press on a theme → Delete, asked first); the list in the settings only picks one. Deleting the theme in use goes back to the default.

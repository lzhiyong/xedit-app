# Toolchain

English | [中文](TOOLCHAIN_CN.md) · [app](README.md)

**Menu → Developer tools → Toolchain** installs compilers, build tools and language servers into the app's own usr directory. It manages only what is in the app, **not Termux**: install what you want of Termux in Termux yourself.

## Three things to understand first

**1. The panel installs only into the app's own usr.**

That is `/data/data/x.editor.app/files/usr`, laid out as Termux's `$PREFIX` (`bin/`, `lib/`, `opt/`, `tmp/`, ...). The app downloads, checks and unpacks from the address in the list. Only **dynamically linked programs built for Android (bionic)** can go there; Termux's packages have Termux's paths built in and cannot simply be copied in.

**2. `packages.json` is the one list.**

It is `Android/data/x.editor.app/files/lsp/packages.json`, and each entry says which commands it provides, its version, where to download it and its SHA-256. The panel shows only what is in the list. **Saving the list installs what was added or changed**; there is no separate button for it.

**3. At run time, the app's own comes first, Termux's otherwise.**

| Who runs a program | Rule |
| --- | --- |
| The console, compile and run | The app's own when usr/bin has every program the command uses; otherwise in Termux (which you have set up) |
| Language servers | **Do not depend on the toolchain list**; see [Configuration](CONFIG.md#language-servers). Type `auto` looks for the command in usr/bin and otherwise uses Termux's through socat |

When the toolchain changes (an install, an uninstall, an edit of the list), language servers only look for their programs again; they are not stopped.

## Installing a tool

1. Tap **New** in the panel and type the tool's id (usually its command, such as `ripgrep`). A draft from the template appears in the editor.
2. Fill it in, with at least `provides` and a `source` for this device:
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
3. Tap **Save**. A window by Save shows each tool's step (download, verify, unpack); once installed, its row says so.
4. Later, raise `version` and save to update; or tap **Update** to bring every installed tool to the version in the list.

**The default list** has clang, CMake/Ninja/make, OpenJDK 21/Gradle, Python, lua-language-server and pyright, but their `source` and `sha256` are empty, so they show "No source for this device" and **cannot be installed until you fill in an address**. There is also a clangd example, off.

## The fields of packages.json

```jsonc
{
  "packages": [
    { "id": "rg", "name": "ripgrep", "kind": "tool", "version": "14.1.0",
      "provides": ["rg"], "source": "https://example.org/ripgrep-aarch64.tar.gz", "sha256": "…" }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Unique; starts with a letter or digit, may have `. _ + -` |
| `name`, `description` | Shown in the list; changing only these installs nothing |
| `kind` | `compiler`, `server` or `tool`; only for display |
| `provides` | The commands it provides |
| `source` | The download: `https://…`, `file://…`, a path or `content://…`. Empty: listed, but nothing to install |
| `sha256` | The SHA-256 of the download. Given, it is checked before installing, and a mismatch installs nothing |
| `abi` | `source` and `sha256` by the device's ABI; the first ABI the device supports is taken, the top-level `source` when none matches |
| `version` | Newer than the installed one: shown as an update; empty means no version |
| `version_command` | What tells its version when its row is tapped; by default each command with `--version` (`-version` for Java) |
| `strip`, `into`, `bin`, `env` | Only when the layout worked out is wrong; see the next section |
| `enabled` | `false` hides the entry |

Changing the `id` of an installed tool keeps what is installed when its source, ABI, version and SHA-256 are unchanged: nothing is downloaded again. A `"termux"` field left from older lists is ignored.

## How a download goes into usr

- **Formats**: zip, tar, tar.gz, `.deb` (its `data.tar(.gz)`, with Termux's path prefix taken off), or a single executable or script. XZ, Zstandard and bzip2 are **not** supported.
- **Layout, worked out**: a single top-level directory is taken off; content already laid out as a prefix (`bin/`, `lib/`) goes straight into usr; anything else goes into `usr/opt/<id>`, with the programs of its `bin/` linked into `usr/bin`.
- **When that is wrong**: `strip` takes off that many top-level directories, `into` is the directory under usr it goes to (`""` is usr itself), `bin` names the programs to link into usr/bin (`{ "clangd": "opt/clangd/bin/clangd" }`), `env` adds environment variables.
- **Resumed downloads**: downloads are kept in the app's cache. After a stop, a failed download or the app being killed, the next attempt goes on where it stopped as long as the id, version, address and SHA-256 are the same; change any of them and the old partial file is deleted when the list is saved. The download is deleted once installed.
- Unpacking keeps executable bits and links and never writes outside usr. What each tool installed is recorded in `usr/var/lib/xedit/packages/<id>.list`, which uninstalling follows.
- Programs run with `PREFIX`, `PATH` (usr/bin first), `LD_LIBRARY_PATH` (usr/lib), `HOME`, `TMPDIR`, and the `env` of each tool.

## The panel

- The icon at the end of a row only shows its state: installable, update, installed; a progress ring while installing. A tool without a source says "No source for this device" in its row.
- **Show** above the list switches between **All** and only **Installed**.
- Tapping a row shows its version, or its progress while it installs or updates.
- Every action is in ⋮ / long press: edit (the file, at this entry), install, update or reinstall, uninstall, remove. **Uninstall** deletes only the installed files; **remove** also takes the entry out of `packages.json` (keeping the file's comments).
- The panel can be closed during an install, which goes on in the background with its progress in a notification; open the panel again to see it. Installs can be stopped one by one, or all with **Stop all**.

## Compile and run

The run button in the toolbar and the commands of the console's Build tab:

- **Where they run**: with the app's own toolchain when usr/bin has every program the command uses (output line by line, and it can be stopped); in Termux otherwise. There is no setting to force either.
- **In which directory**: the root of the project of the current file. Looking up from the file's directory: the directory with the Gradle settings file, else the outermost directory with a CMakeLists.txt, else the nearest with a build file (CMake, Gradle, make, Meson, Cargo, Go, package.json); never above `.git`; the file's own directory when there is none.
- **Changing the command**: long press the run button. The preview shows the command and where it runs. **Arguments** apply only to the current file (or project) and go at the end of the command, or where the template has `${args}`.

Variables of command templates:

| Variable | Meaning |
| --- | --- |
| `${file}` | The current file, relative to the project root |
| `${name}` | The file's name without its extension |
| `${path}`, `${dir}`, `${root}` | The file's absolute path, its directory, the project root |
| `${tmp}` | Where compiled programs go: the app's `usr/tmp` or Termux's tmp |
| `${exec}` | What starts a compiled program: the system linker with the app's toolchain, nothing in Termux |
| `${prefix}` | The prefix where it runs: the app's usr or Termux's `$PREFIX` |
| `${gradle}` | `sh ./gradlew` when the project has a wrapper, `gradle` otherwise |
| `${args}` | The arguments |

For instance, the default command of a C file: `clang -Wall "${file}" -o ${tmp}/${name} && ${exec}${tmp}/${name}`.

The source files of CMake, Gradle and Android projects use the project's command (other files in the project keep the command of their type):

| Project | Default command |
| --- | --- |
| CMake | `(test -f build/CMakeCache.txt \|\| cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug -DCMAKE_EXPORT_COMPILE_COMMANDS=ON) && cmake --build build -j4` |
| Gradle | `${gradle} assemble --console=plain` |
| Android | `${gradle} assembleDebug --console=plain` |

## Using programs from Termux

The panel does not look for, install or update anything in Termux. To use Termux's compilers or language servers, set them up in Termux yourself:

```sh
echo "allow-external-apps = true" >> ~/.termux/termux.properties
termux-setup-storage      # so programs in Termux can read and write projects on /sdcard
pkg install clang socat   # and whatever else you need
```

and allow X Editor to "run commands in Termux" when Android asks. Then console commands go to Termux when the app lacks a program, and language servers can reach Termux's servers through socat (see [Configuration](CONFIG.md#example-clangd-and-pyright-in-termux)).

## Limits

- **What runs from usr has to be built for Android (bionic), dynamically linked, for this app's prefix.**
- **Since Android 10 an app may not execute files in its own data directory.** The app starts the programs of usr/bin through the system linker (`/system/bin/linker64`) and scripts through their interpreter. A program that starts others in turn (clang its linker, cmake the compilers) is refused the same way, unless termux-exec's library is in `usr/lib` (`libtermux-exec*.so`).
- Building a project on the shared storage with the app's toolchain needs all files access.
- The app's own toolchain is behind the switch `Toolchain.LOCAL_ENABLED` (on); turned off, every server and command runs in Termux.

## Troubleshooting

| What you see | Why, and what to do |
| --- | --- |
| "No source for this device" | The entry's `source` is empty, or `abi` has no entry for this device's ABI; fill in an address and save |
| Install fails on SHA-256 | The file at the address does not match `sha256`; check both |
| An installed program does not run | It is not built for Android, or a library it needs is not in `usr/lib` |
| clang or cmake start but the build fails | They start other programs: install termux-exec's library, or build in Termux |
| A command runs in Termux, not in the app | One of the programs it uses is not in usr/bin; install it in the panel |
| The version is not found | Set `version_command` to what tells it |

# Toolchain

> Part of the [X Editor](../README.md) documentation · [中文](toolchain_cn.md)

**Menu → Developer tools → Toolchain** is one sheet: Termux (found and allowed, or a button for what it
needs), which goes first (**Use first**: auto, X Editor, Termux), and the toolchains of
`packages.json`, each with where it is. Nothing is typed in to install: what a toolchain is
and where it comes from is said in the list, **Edit list** opens it.

## The app's usr

X Editor's own toolchains go to its prefix, `/data/data/x.editor.app/files/usr` (`AppDirs.prefix`,
as Termux's `$PREFIX`), laid out as Termux's:
`bin/`, `lib/`, `libexec/`, `include/`, `etc/`, `share/`, `opt/`, `tmp/`. As dpkg keeps
`info/<package>.list`, `var/lib/xedit/packages/` keeps for each toolchain a `<id>.list`, every
path it installed (its directories first), and a `<id>.json` (its version, source, links,
environment). Programs run with `PREFIX`, `PATH` (usr/bin first), `LD_LIBRARY_PATH`
(usr/lib), `HOME`, `TMPDIR`, and what the toolchains add.

## packages.json

`packages.json`, next to `servers.json`, lists the toolchains:

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

- `provides`: its commands; they are also looked for in Termux. `termux`: how it is had in
  Termux, shown when it is in neither place (you run it in Termux yourself).
- `source` (`https://…`, `file://…` or a path, `content://…`) and `sha256`, or by the
  device's ABI under `abi` (the first of the device's ABIs it has wins, else `source`): what
  is downloaded to install it into X Editor. Without one, the toolchain is only looked for
  in Termux.
- `version`: a newer one in the list than the one installed is an update.
- `strip`, `into`, `bin`, `env`: only if the layout worked out is not right (a single top
  directory is taken off; what is laid out as a prefix goes into usr itself, anything else
  into `opt/<id>` with its `bin/` linked into usr/bin).
- `enabled: false` hides one. The list X Editor writes the first time has clang, CMake with
  Ninja and make, OpenJDK 21 with Gradle, Python, lua-language-server, pyright and socat,
  to look for in Termux, and an example of one to install into X Editor (switched off).

What is downloaded may be a zip, a tar, a tar.gz, a `.deb` (its `data.tar` or
`data.tar.gz`; Termux's prefix in it is taken off), or a single binary or script; XZ,
Zstandard and bzip2 are not unpacked. Executable bits, symbolic and hard links are kept;
nothing is written outside usr. The download is deleted once it is installed.

## The list on the sheet

Each toolchain shows its kind, name and version, where it is (in X Editor, can be installed
into X Editor, in Termux, or how to have it in Termux), and one icon:

- **install** (a download arrow): installs it into X Editor, its progress in its row;
- **update** (an arrow up): a newer version in the list, installed over the old one;
- **installed** (a check): a menu to reinstall or **uninstall** it: what its `.list` says is
  deleted, its files and links, then the directories it leaves empty;
- a grey check: it is not installed into X Editor, but Termux has its commands.

What is installed but no longer listed is shown too, to be uninstalled.

## Termux

X Editor installs nothing in Termux: install its packages there yourself (`pkg install
clang`). When the sheet opens (and with its refresh button), X Editor asks Termux which
commands of the list it has (`command -v`, in the background) and shows them. Termux needs
`allow-external-apps = true` in `~/.termux/termux.properties`, and X Editor its permission
to run commands; the sheet says what is missing, with a button.

## Yours first, Termux's otherwise

- **Language servers** of `"type": "auto"` are started from usr/bin when they are there, in
  Termux otherwise (see above). A `"type": "process"` command that is a name is looked for in
  usr/bin too.
- **Commands** (the console's build tab, "compile and run") run with the app's own
  toolchain when its usr/bin has every program the command runs (`gradlew` and `mvn` need
  `java` too), in Termux otherwise. **Use first** in the toolchain sheet can make
  that always the app's own, or always Termux. A run with the app's own shows its output as
  it comes and can be stopped.

All of this is behind one switch, `Toolchain.LOCAL_ENABLED` (on). Turned off, everything is
Termux's: servers of type auto and every command run there, the code of the app's own kept.

What runs from usr has to be built for Android (bionic), dynamically linked, for this
prefix: Termux's packages have Termux's paths compiled in, and run in Termux. An app that
targets Android 10 or later may not execute its own files directly: the app starts the
programs of usr/bin through the system's linker (`/system/bin/linker64`) and scripts through
their interpreter, so a shell it runs finds them as functions. A program that starts others
in turn (clang its linker, cmake the compilers) is refused the same way, unless
termux-exec's library is in `usr/lib` (`libtermux-exec*.so`, preloaded with its system
linker mode); the sheet says whether it is there. Building a project on the shared storage
with the app's own toolchain needs all files access for X Editor; in Termux,
`termux-setup-storage`.

## In the project's root

"Compile and run" and the commands of the console's build tab run in the root of the
project the file is in: the directory above the file with the Gradle settings file, else the
outermost of nested CMakeLists.txt, else the nearest with a build file (CMake, Gradle, make,
Meson, Cargo, Go, package.json); not past a `.git`; none of them, the file's own directory.

So `${file}` in a template is the file relative to that root (`src/main.c`), and `${root}`
is the root; a long press on the run button shows the command and where it runs.

A CMake, Gradle or Android project has a command of its own, for its sources (C, C++ and
assembly in CMake; Java, Kotlin, Groovy and Gradle's scripts in Gradle; those, C, C++,
XML, AIDL in an Android one); its other files (a Python script in it) keep theirs:

| Project | Default |
| --- | --- |
| CMake | `(test -f build/CMakeCache.txt \|\| cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug -DCMAKE_EXPORT_COMPILE_COMMANDS=ON) && cmake --build build -j4` |
| Gradle | `${gradle} assemble --console=plain` |
| Android (the Android plugin in the root's or `app`'s build file) | `${gradle} assembleDebug --console=plain` |

`${gradle}` is `sh ./gradlew` when the project has the wrapper (the shared storage executes
nothing), else `gradle`; either needs `java`. A long press on the run button sets the
command of the project when the file is one of its sources. ARM assembly (`.s`, `.S`, and
`.asm` through the preprocessor) is assembled and linked by clang with libc, so its entry
is `main`: `clang "${file}" -o ${tmp}/${name} && ${exec}${tmp}/${name}`. `${prefix}` is
the prefix of where the command runs: the app's own, or Termux's.

"Compile and run" templates also have `${exec}`, what starts a compiled program: the
system's linker with the app's own toolchain, nothing in Termux (`${tmp}` is usr/tmp, or
Termux's tmp). The default templates use it: `clang … -o ${tmp}/${name} && ${exec}${tmp}/${name}`.

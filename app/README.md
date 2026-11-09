# app

English | [中文](README_CN.md)

The X Editor app itself: the editing screen, settings, tree-sitter highlighting, language servers and completion, the console and the toolchain. It uses every library module except `bypass`.

| Doc | Contents |
| --- | --- |
| This page | Features and use, where files are, limits, notes for developers |
| [Configuration](CONFIG.md) | Language servers, formatting rules, snippets, shortcuts, highlight themes |
| [Toolchain](TOOLCHAIN.md) | Installing compilers and language servers into the app, `packages.json`, compile and run |

## Use

### Editing

- **Undo and redo across sessions**: reopening a file whose content has not changed (by SHA-256) brings back the cursor and the undo history; unsaved changes can be got back with redo.
- **Code folding**: from tree-sitter's `folds` query, by indentation when there is none.
- **Open files**: tap the app title in the toolbar for a list of every open file, the current one highlighted and scrolled to the middle; tap one to switch to its tab. Quicker than scrolling the tabs with dozens open. A long press on a tab closes it, the others or all, or shares it.
- **Go to line**: type `line` or `line:column`, with a preview of the line.
- **Selection bar**: cut, copy, paste, select all, search; its ⋮ also has share, the apps that process selected text (translate, read aloud, ...), comment, format and document outline.
- **Comment**: whole lines get line comments (`//`, `#`, `--`, at the least indentation); part of a line, or a language without line comments, gets a block comment; commented text is uncommented. One undo step.
- **Changes from outside**: when another app changes the document shown, reload it, or keep editing and overwrite on save; when the file is deleted, keep the text (the file is made again on save) or close it.

### Full screen

On a phone with the keyboard up, the toolbar and the tabs take a large part of the screen. In full screen they fold into one slim bar (as high as the tabs alone) and the status bar is hidden (a swipe from the top shows it for a moment): about 90dp more for the editor.

- **The slim bar**: the name of the file shown, where it is among the open ones (such as `3/12`), a dot for unsaved changes, a spinner while it is busy; a tap on the name lists every open file. On the right: undo, redo, save, ⋮ (the whole menu of the toolbar) and out of full screen.
- **When**: **Settings → Editor → Full screen**: only from the menu (the default) / while the keyboard is up / always. **Full screen** in the menu, or `F11`, turns it on or off at any time.
- **Out**: the button on the slim bar, the menu, or back. Turned off while the keyboard is up (in "while the keyboard is up"), it comes back with the keyboard the next time.
- While a menu, the list of files or a sheet is open, it stays as it is, even if the keyboard goes down for it.

### Completion

- **When it shows**: right after a trigger character of the server (`.`, `->`, `::`), and after a short pause while typing a word; typing on filters the list locally. Without a language server, the words of the text are completed fuzzily.
- **On picking an item**: the word is replaced, a snippet expanded, extra edits made (such as an `#include`). When the server sends only a function name, placeholders are made from its signature: `foo(a, b)`. A closing character already after the cursor is typed over, not doubled. All of it is one undo step.
- **Snippet stops**: Tab goes to the next argument and finally past the `)`; Shift+Tab (or a long press on Tab in the extra keys) goes back.
- **Paths**: typing `./`, `../`, a path in quotes or an absolute path such as `/sdcard/...` lists the files there.
- With a keyboard: the arrows move in the list, Enter or Tab picks, Esc closes.

### Console

A bottom panel in the manner of VS Code, with three tabs: **Output** (logs by channel: the app, language servers, builds), **Problems** (the diagnostics of open documents and the errors of the last build; tap to go there) and **Build** (runs commands and picks the errors of gcc, clang and Python out of the output).

The run button in the toolbar compiles and runs the current file; a long press edits its command and arguments. Where commands run and in which directory: see [Toolchain](TOOLCHAIN.md#compile-and-run).

### AI assistant

**Menu → Developer tools → AI assistant** lets a large language model work on the project of the file shown: it reads, searches and changes its files and can run commands in it with the app's toolchain, in a loop, until the task is done.

- **Setting up (⚙)**: the provider, the API address, the model and the API key. **Anthropic (Claude)** uses Anthropic's Messages API; **OpenAI-compatible** takes any API with OpenAI's chat completions and tool calls (OpenAI, DeepSeek, OpenRouter, Qwen, a local Ollama at `http://127.0.0.1:11434/v1`, ...), the address being the part before `/chat/completions`. The model must support tool calls. Each provider keeps its own address, model and key; the keys are kept encrypted on the device (Android Keystore) and sent only to their address.
- **What it can do**: list, read and search the files under the workspace root (always allowed); write a file and edit one by an exact replacement; run a shell command in the root (up to 15 minutes; output cut if long). It cannot reach files outside the root. By default it asks before each change and each command, showing the diff or the command: **Allow**, **Allow all in this chat**, or **Deny**. ⚙ can let it change files, or do everything, without asking.
- **The workspace** is the project of the file shown, found as for compile and run (the directory with the build files; see [Toolchain](TOOLCHAIN.md#compile-and-run)). The model is also told the open file, the cursor line and the selected text.
- A file it changes is read again in its tab if that has no unsaved changes; with some, you are asked as for a change by another app. **Open** on its card shows the file.
- **Code and output, read only**: the code blocks of an answer and what read_file and run_command came to are shown in the app's own editor (`CodeEditorView`), highlighted by language, up to 20 lines and scrolled sideways; **Copy** in the head copies the block, **Expand** (or a tap on the code) shows it whole. Whole, it has line numbers and can be selected with a long press; the floating bar has only **Copy** and **Select all**, nothing can be changed and the keyboard never comes up; a long text is laid out in the background as the editor lays out a large file. **View** on the card of a file it wrote, changed or read shows that file the same way (up to 32 MB).
- **Stop** ends the turn at once (the request, the command, the question waiting). Closing the sheet does not: the conversation goes on and is there when it is opened again, until the screen is closed. **New chat** (+) starts over.

### Look

**Settings → Editor** has the app theme (light, dark, pure black, dynamic color), the highlight theme (astrodark and astrolight built in; import and export), the font (JetBrains Mono and Source Code Pro built in; import your own), text size and tab width. To make your own theme, see [Configuration](CONFIG.md#highlight-themes).

## Where files are

| What | Where |
| --- | --- |
| **The configuration you edit**: language servers, formatting rules, the toolchain list, snippets, shortcuts | Shared storage, `Android/data/x.editor.app/files/`; see [Configuration](CONFIG.md) |
| Highlighting resources: grammars, queries, themes, friendly-snippets | Downloaded on first launch from the addresses in [`treesitter.jsonc`](src/main/assets/treesitter.jsonc), kept in `/data/data/x.editor.app/files/home/` |
| Imported themes and fonts | `home/local/themes/`, `home/local/fonts/` |
| Edit sessions, crash logs | `home/sessions/`, `home/logs/`, `home/native_crash/` |
| The app's own toolchain | `/data/data/x.editor.app/files/usr/`; see [Toolchain](TOOLCHAIN.md) |
| Compile and run commands | The app's settings, by file extension |

Downloads resume where they stopped, and a package is downloaded again only when its ETag changes; **Settings → Highlighting resources** shows their state.

## Limits

- Until the highlighting resources are downloaded, files have no syntax highlighting.
- No language server comes with the app. Documents without a file path (such as those of a cloud drive) are not sent to language servers.
- Without all files access, path completion and project style files such as `.editorconfig` can be read only through folders opened with **Add folder** in the file tree; a document opened alone with **Open file** has no folder to use.
- Only the document shown is watched for changes from outside, by the modification time and size its provider gives; a provider that gives neither cannot be watched.
- Files are read and written as UTF-8 (a BOM is kept).
- The AI assistant's conversation is kept only while the editor screen lives, and is not trimmed: a very long one may outgrow the model's context (start a new chat). Its commands run with the app's own toolchain only (not in Termux), and need all files access to run in a project in shared storage.

## Notes for developers

**Packages** (under `x.editor.app`): `activity` (the editing screen), `controller` (file tree, tabs, search, the language server controller `LspController`), `highlight` (tree-sitter highlighting), `lsp` (server configuration and connections, completion helpers), `console`, `toolchain`, `config` (the developer tools panels), `service` (reading and writing documents, sessions), `view` (`CodeEditorView`, the `EditorView` wired to tree-sitter). The other packages are what their names say.

**Why highlighting does not stutter**:

- tree-sitter queries never run on the main thread. The text is highlighted in blocks of 2048 characters on a worker thread, with a copy of the syntax tree and a snapshot of the text; the main thread only joins the blocks that are done.
- While editing, the blocks move with the text, so a character just typed takes its neighbour's color at once; after the next parse only the blocks reported as changed are queried again.
- A line that has been colored is never replaced with plain text (`RowCache`), so nothing flickers.
- Parsing, comparing the old and new trees, indent guides and folds are all worked out on worker threads.
- Query predicates are decided in C++ (see [treesitter](../treesitter/README.md)); comments that cannot be highlighted are not handed to the comment grammar (92% of them in `sqlite3.c`).
- The result, scrolling and editing `sqlite3.c` on the development device: janky frames from 31% to 4%, the 90th percentile frame time from 89 ms to 18 ms.

**Opening and saving documents**: one document is loaded at a time, and a switch waits until the earlier load has fully stopped; only a document loaded completely is ever written back, so the content of one file can never go into another; saves run one after another, and a failed write leaves the document open with its changes. An edit session is kept with the SHA-256 of the file's content and restored only when the hash still matches.

**The developer tools panels** (the six under Menu → Developer tools) share one skeleton and one set of gestures; each runs one content-changing operation at a time (`OperationGate`). New entries come from the templates in `assets/templates/`, whose placeholders are all written `{{name}}`.

**The busy dots** (`view/BusyDotsDrawable`, the icon of Update in the toolchain panel while it works) can be used anywhere a drawable goes. They are made of four sub-animations (`BusyMotion`): `SquareSwap` (the diagonals of a square swap in turn), `TriangleWalk` (three dots go round a triangle), `RowWave` (three dots in a row jump in turn) and `Pulse` (the dots run together into one and split back). Each loops on its own; a `BusyDotsSpec` plays several in turn, and between two of different shapes the dots run together and split into the next one (`morph`). `speed` scales every duration.

```kotlin
val dots = BusyDotsDrawable.ofDp(context)                                   // the whole loop
val wave = BusyDotsDrawable.ofDp(context, spec = BusyDotsSpec.of(BusyMotion.RowWave()))
val mix = BusyDotsDrawable.ofDp(context, 24f, BusyDotsSpec.of(
    BusyMotion.SquareSwap(rounds = 2), BusyMotion.Pulse(), speed = 1.5f))

button.setBusyDots(busy, dots, R.drawable.ic_update)   // MaterialButton: dots while busy, the icon after
imageView.setBusyDots(busy, wave)                      // ImageView: dots while busy, the old drawable after
textView.setBusyDotsAtStart(busy, dots)                // TextView: dots before the text while busy (AI assistant "Thinking")
menuItem.icon = mix; mix.start()                       // or by hand, as any Animatable
```

The dots take the view's tint, pause while the view is not shown, and can be given a new `spec` while they run.

**The AI assistant** (`agent/`) is built in layers. `LlmProvider` is the one interface to a model: a request (system prompt, messages of text, tool calls and tool results, tool specs as JSON schemas) streamed back as `LlmEvent`s and returned whole as an `LlmReply`; `AnthropicProvider` and `OpenAiProvider` turn it into their APIs' JSON and put the streamed events back together (`SseParser`, `LlmHttp` on OkHttp; cancelling the coroutine cancels the call). Another provider is another class of that interface, and a kind in `AgentSettings.ProviderKind`. `AgentTools` are the tools (`AgentTool`: a spec, a risk — read, write, command — a summary, a preview for the user, a run), on a `Workspace` that keeps every path under its root and reads and writes by path or through the user's SAF grants (`LocalFiles.writeText`). `AgentSession` runs the loop (model → tools → results → model, at most `MAX_ROUNDS` requests a turn), asks the user through the sheet for the risks the settings say, and talks to the editor through `AgentHost` (EditorActivity). The text work (exact replacement, globs, clipping), the stream parsing and the request bodies are tested without Android (`src/test/.../agent/`).

**The maze** (`view/MazeView`, behind the card at the top of the resources status) is played like a game: the ball sees the exit (a faintly breathing ring at the edge) but not the way to it, and rolls there at one steady speed (a cell in 300 ms, forwards and back alike, never stopping), leaving a dot in every cell it gets to (going back too). Each dot fades out slowly on its own and is gone by the time the ball is five cells further on, so there is only a short trail behind it (as the 6th dot is drawn, the 1st has gone; `DOT_LIFE_STEPS`). At a fork it takes a way it has not tried, mostly the one heading towards the exit; at a dead end it turns and rolls back to the last fork with a way left (a depth-first search, walked). At the exit a new random maze (Kruskal's, with a few extra openings so there are loops) comes in slowly from the ball outwards, with a new exit at a medium distance. A tap puts the ball in the cell tapped and it sets out from there, knowing nothing of where it has been. It is a background, not a show: everything is drawn in the card's text colour at low alphas (the ball too, not in the accent colour), and under the views given to `setQuietViews` (the card's icon, title, subtitle and numbers) at a third of that, so the text stays easy to read; in the dark theme the card itself is a little darker than its container colour. The timings, sizes and alphas are the constants at the end of the class. It pauses while not shown, and stands still when the system turns animations off. It was written from screenshots and a screen recording of Vector's status card only; none of Vector's (GPL) code was read or used.

**Resource names**: `<kind>_<feature>_<part>`, such as `dialog_goto_line`, `item_extra_key`, `ic_content_copy`, `bg_pref_card_top`; strings start with their feature (`search_`, `lsp_`, `toolchain_`). `strings.xml` and `values-zh/strings.xml` have their sections in the same order: add or remove a string in both.

**Tests**: `./gradlew :app:testDebugUnitTest`, for the pure logic: when completion is asked for, word completion, problems in build output, run commands, hover Markdown, server configuration files, formatting rules, the shortcuts file (and bringing an older one up to date), toolchain rules. The UI and Termux are checked by hand.

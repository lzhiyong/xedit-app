# crash: Design and internals

> Part of the documentation of the [crash](../crash/README.md) module · [中文](crash_architecture_cn.md)

## Architecture

### Processes

| Process | Role |
|---|---|
| Main process | Installs crash capture, writes crash logs, runs upload work |
| `:crash` | Runs `CrashActivity` only. Crash capture is **never** installed here |

`CrashActivity` lives in its own process so it can still be shown while the
main process is stuck (ANR) or dying (native crash).

### Source files

| File | Responsibility |
|---|---|
| `CrashHandler.kt` | Public entry point: `install()` / `uninstall()`, Java uncaught handler, JNI callbacks for native crashes and ANRs, debug-only test APIs |
| `CrashLogStore.kt` | The only owner of crash logs: writing, deduplication, pruning, adopting native fallback reports, `consumeAll()` |
| `CrashReportUploader.kt` | Queues reports for upload with WorkManager; `CrashReportWorker` posts them |
| `CrashActivity.kt` | Shows every unseen crash; report, copy, export, restart, close |
| `MyApplication.kt` | Example host: installs capture, opens `CrashActivity`, initializes WorkManager in `:crash` |
| `crash_handler.cpp` | Signal handlers, the crash reporter thread, the SIGQUIT (ANR) monitor |
| `fallback_report.cpp/.h` | Native write-ahead copy of a native crash report |

All crash types end up in **one** place, `CrashLogStore`, in **one** format.
Everything downstream (deduplication, `CrashActivity`, upload) only ever deals
with that format.

## Capture paths

### Java crashes

```
uncaught exception (any thread, main thread included)
  └─ JavaCrashHandler.uncaughtException()
       ├─ CrashLogStore.write()                 log is on disk
       ├─ listener.onJavaCrash()                first crash of the process only
       └─ previous handler (finally)            ALWAYS, even if the above threw
            └─ system: ActivityManager.handleApplicationCrash(), then kill
```

- The main `Looper` is **not** wrapped. A main-thread exception propagates out
  of `Looper.loop()` in `ActivityThread.main()`, and the runtime dispatches it
  to the default handler like any other thread's. A crash is never swallowed
  to keep the app running.
- If several threads crash at nearly the same time, every crash is saved, but
  only the first one reaches the listener. `CrashActivity` reads all saved
  logs anyway.
- `onJavaCrash` must **not** exit the process and should return quickly. The
  system kills the process right after.
- If no previous handler exists (unusual), the process is killed directly.

**Expected exceptions belong at their source.** An invalid regular expression
typed by the user, a malformed file, an I/O error: catch these where they
happen and show a precise error. Anything that reaches the uncaught handler
ends the process.

### Native crashes

```mermaid
sequenceDiagram
    participant C as Crashing thread
    participant R as crash_report thread
    participant K as Kotlin (CrashHandler)
    participant D as debuggerd

    C->>R: signal handler wakes the reporter
    R->>R: fallback: write header + abort message, fsync
    R->>R: unwind the crashing thread
    R->>R: fallback: append trace, fsync, close
    R->>K: callback(signum, message, fallbackName)
    K->>K: CrashLogStore.write()
    K->>K: delete fallback file (only if the write succeeded)
    K->>K: listener.onNativeCrash()
    K-->>R: return
    R-->>C: done (or 10 s timeout)
    C->>C: chain to previous handler, restore SIG_DFL, re-raise
    C->>D: crash, process dies
```

- The signal handler runs on an alternate signal stack and only wakes the
  reporter thread. Only one thread reports per process; the crashing thread
  waits up to 10 seconds for the report, then lets the signal continue.
- The native **fallback report** is written before any JVM work, so a crash is
  not lost if the JNI callback cannot complete (ART deadlocked, attach failed,
  process killed mid-report). See [Log storage](#log-storage).
- `onNativeCrash` runs on the `crash_report` thread. It must **not** exit the
  process: after it returns, the signal reaches `debuggerd`, which writes a
  crash, and the process dies on its own.
- Native signal handlers become active when the native library loads (first
  access to `CrashHandler`). `install()` connects them to the listener.

### ANRs

When `install(anrMonitor = true)`, the native layer watches SIGQUIT, which the
system sends to a process it is about to declare unresponsive.

1. SIGQUIT arrives. `onAnrSignal()` snapshots the main thread's stack at once,
   since a background ANR may be killed right after the system's dump.
2. SIGQUIT is forwarded to ART's Signal Catcher, so the system trace dump
   happens as usual.
3. `onAnrConfirm()` checks, for up to 5 seconds, whether this is really an ANR
   of this process: the main thread must stay unresponsive, and
   `ActivityManager.getProcessesInErrorState()` must report this process as
   `NOT_RESPONDING`. A main thread that answers (another app's ANR,
   `adb shell kill -3`, a recovered stall) cancels the report.
4. Once confirmed: `CrashLogStore.write()`, then `listener.onAnrCrash()`.

`onAnrCrash` runs on the native `anr_report` thread while the main thread is
stuck: do not touch the UI or wait for the main thread there. By default it
forwards to `onJavaCrash`. The library never kills the process on an ANR; the
example `MyApplication` chooses to (see [Usage](crash_usage.md#usage)).

## Log storage

### Directories

| Directory | Files | Role | Written by | Deleted by |
|---|---|---|---|---|
| `filesDir/home/native_crash/` | `native_<epoch_ms>_<pid>.log` | Native write-ahead copy | native reporter thread | Kotlin, once its own log is saved; or adopted on the next launch |
| `filesDir/home/logs/` | `crash_<yyyyMMdd>_<HHmmss>_<SSS>_<seq>_<type>_<signature>_<count>.log` | **The** crash log | `CrashLogStore.write()` | dedup replacement, startup pruning, `consumeAll()` |
| `noBackupFilesDir/crash_reports/` | `report_<ms>_<uuid>.txt` | Upload queue entry | `CrashReportUploader.enqueue()` | `CrashReportWorker` |

Only `crash_*` files are listed or deleted in `filesDir/home/logs/`, so the
directory can be shared with the app's other logs.

### Log format

```
Type: Java Crash
Time: 2026-10-08 14:03:21.457
Occurrences: 3 (only the latest is kept)     <- only when count > 1
Thread: main                                 <- when known
Process: x.editor.app (pid 12345)
App: x.editor.app 1.2.0 (120)
Device: Google Pixel 8
Android: 15 (API 35)
ABIs: arm64-v8a, armeabi-v7a, armeabi
------------------------------------------
java.lang.RuntimeException: ...
    at ...
```

### Saving

Every crash is persisted **before** the listener runs, so it survives a
listener that fails, an activity start that is blocked, or a process that dies.

- Content is written to a `.tmp` file and then renamed to `.log`, so a reader
  never sees a half-written log.
- Only after the rename succeeds are older logs of the same crash deleted.
- `write()` never throws. If saving fails (disk full), the returned
  `CrashLog.file` is `null` and `CrashLog.content` still carries the text,
  which `MyApplication` then passes to `CrashActivity` inline.

Native crashes add a write-ahead step:

1. The reporter writes the fallback file's header (signal, pid, tid, thread
   name, abort message) and `fsync`s it, without using the heap.
2. After unwinding, it appends the trace, `fsync`s again and closes the file.
3. Kotlin saves its own log with `CrashLogStore.write()`.
4. **Only if that succeeded**, Kotlin deletes the fallback file, and does so
   **before** calling the listener. A listener that throws or hangs therefore
   cannot leave a duplicate behind, and a failed write keeps the fallback so
   the crash is not lost.

### Deleting

**Crash logs (`crash_*.log`)**

- **Dedup replacement**: when the same crash happens again, the new log
  replaces the old one and the count in its name goes up. Only the newest copy
  of each distinct crash is kept.
- **Startup pruning** (`CrashLogStore.init()`): removes `.tmp` leftovers older
  than 60 seconds and keeps at most the 20 newest distinct crashes.
- **Seen means deleted** (`consumeAll()`): `CrashActivity` reads every log and
  deletes each one as it reads it, unreadable ones included, so a corrupt file
  cannot reappear forever. From then on the text lives only in the
  `ViewModel`: it survives rotation, but not the `:crash` process ending.
  Exporting is how the user keeps a copy.

**Native fallback reports (`native_*.log`)**

- **Normal case**: deleted by Kotlin during the same crash (step 4 above).
- **Left behind**: on the next launch, `install()` calls
  `CrashLogStore.adoptNativeFallbacks()` before re-enabling the fallback. Each
  file is converted into a regular crash log, using the original crash time
  and a signature computed exactly like the live path, so it deduplicates
  against normal logs of the same bug. Converted files are deleted. Blank
  files are deleted. Files that fail to convert are kept and retried on the
  next launch. Files whose writer process is still alive and that are younger
  than 60 seconds are skipped.

**Upload queue entries (`report_*.txt`)**

- Deleted by the worker after a successful upload, a non-retryable rejection,
  or the last failed attempt.
- Entries older than 7 days are removed the next time `enqueue()` runs.

### Deduplication signature

Two crashes are "the same" when their signatures match. Messages, addresses and
ids are left out on purpose, since they differ between occurrences of one bug.

| Type | Signature |
|---|---|
| Java | Exception class plus top 10 frames, for up to 5 throwables in the cause chain |
| ANR | Top 20 frames of the main thread |
| Native | Signal number plus the reporter's message, with hex addresses, pid/tid and the randomized `/data/app/...` install path normalized |

The type is part of the hash, and the result is 12 hex characters of SHA-1.

### Typical scenarios

**Foreground crash, everything works.** The log is saved (for a native crash,
the fallback is written and then deleted), and `CrashActivity` opens, reads the
log and deletes it. Nothing is left on disk unless the user submitted a
report, which stays in the upload queue until it is sent.

**Native crash, JNI callback fails.** Only the fallback file remains. On the
next launch, `install()` in `attachBaseContext` adopts it as a regular log.
When the first activity is created, `hasLogs()` is true, so `CrashActivity`
opens in "previous launch" mode on top of the app. Adoption runs before any
activity, so the adopted log is always found.

**Background crash.** The log is saved, but Android 10+ blocks starting an
activity from the background. The log stays on disk until the user opens the
app again, then follows the previous scenario.

## Uploading

`CrashReportUploader.enqueue(context, url, content)` queues a report with
WorkManager, so an upload survives the user leaving the screen, the process
being killed, and the device being offline.

- The content is staged as a file (WorkManager input data is limited to
  10 KB); only its path is passed to the worker.
- `enqueue()` blocks until WorkManager has persisted the work, so the caller
  may end its process right after. Call it off the main thread.
- The worker sends an HTTP `POST` with a `text/plain; charset=utf-8` body. It
  needs a network connection and retries with exponential backoff starting at
  30 seconds.
- Retried: I/O errors, HTTP 408, 429 and 5xx, up to 5 attempts in total.
  Other 4xx responses are not retried.
- `:crash` only records the work. It is **executed** in the main process,
  which the system job scheduler starts if needed.

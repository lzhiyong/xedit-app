# Crash Capture Module

Captures Java crashes, native crashes and ANRs in-process, saves every crash to
disk before anything else happens, and shows all unseen crashes to the user in
a dedicated `CrashActivity`, from which they can be copied, exported or
uploaded.

The module is designed to coexist with the platform rather than replace it:

- **Java crashes** are recorded, then handed to the previous default handler
  (normally the system's), which reports them to ActivityManager and kills the
  process. Android vitals, `ApplicationExitInfo` and other crash SDKs see them
  as usual.
- **Native crashes** are recorded, then the signal is passed on so `debuggerd`
  still writes a crash.
- **ANRs** are detected through SIGQUIT, which is always forwarded to ART's
  Signal Catcher, so the system's `traces.txt` is unaffected.

## Documentation

The details are in the documents of this module:

- [Design and internals](../docs/crash_architecture.md): Architecture, Capture paths, Log storage, Uploading
- [Usage](../docs/crash_usage.md): Integration, Usage
- [Testing](../docs/crash_testing.md): Testing

## Known limitations

- **Repeated crashes show a system dialog.** Since Java crashes go to the
  system, a second crash of the main process within about a minute makes
  Android show its "app keeps stopping" dialog, possibly on top of
  `CrashActivity`. The first crash shows no system dialog on Android 9+.
- **In-process native unwinding.** The stack is unwound inside the crashing
  process and the report uses the heap. For heap corruption or crashes inside
  `malloc`, the reporter may stall; then only the fallback header (signal,
  thread, abort message) survives, without a stack trace. Mature solutions
  (Crashpad, Breakpad, xCrash) unwind from a separate process.
- **Crashing thread only.** Native reports contain the crashing thread's stack
  and the abort message, not all threads, registers or memory maps. The
  system crash has those.
- **No symbolication.** Release `.so` files are stripped, so native frames are
  addresses. Keep unstripped libraries per release and symbolize with
  `ndk-stack` or `llvm-symbolizer`; the report must include the build-id and
  relative pc for this to work.
- **Upload is user-initiated.** Only reports the user submits are sent, so this
  module cannot measure crash rates. Pair it with a crash reporting service if
  you need statistics.
- **Not captured**: processes killed by the system without a signal the app
  can see (low-memory killer, background ANRs that are killed silently).
  `ApplicationExitInfo` (API 30+) can fill this gap on the next launch.
- **Adopted crash headers** describe the launch that adopted them, not the one
  that crashed. If the app was updated in between, the version differs.
- Startup pruning runs before adoption, so after many adopted reports the log
  count can briefly exceed 20 until the next launch.
- Crash logs may contain user data (exception messages). Tell users before
  uploading, and cover it in the privacy policy.

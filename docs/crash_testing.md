# crash: Testing

> Part of the documentation of the [crash](../crash/README.md) module · [中文](crash_testing_cn.md)

Test APIs only work in debuggable builds; in release builds they log a warning
and do nothing.

| Call | Effect |
|---|---|
| `CrashHandler.testJavaCrash()` | Throws a `RuntimeException` on the calling thread |
| `CrashHandler.testNativeCrash()` | Dereferences a null pointer in native code |
| `CrashHandler.testAnrCrash()` | Deadlocks the main thread inside a foreground ordered broadcast; the system declares an ANR after about 10 seconds. Call it while the app is in the foreground. |

Checks worth running:

1. **Java crash reaches the system.** After `testJavaCrash()`, `CrashActivity`
   opens, `adb logcat -s AndroidRuntime` shows `FATAL EXCEPTION`, and
   `adb shell dumpsys activity exit-info <package>` (API 30+) lists the exit
   with reason `CRASH`.
2. **Native fallback cleanup.** After `testNativeCrash()`,
   `files/native_crash/` is empty and a crash exists.
3. **Fallback adoption.** Temporarily throw at the start of
   `CrashHandler.callback`, trigger a native crash, then relaunch. The crash
   appears in `CrashActivity`, marked as recovered from the native fallback
   report.
4. **Deduplication.** Trigger the same crash twice before opening the app.
   There is one entry, marked `×2`.
5. **Background crash.** Trigger a crash while the app is in the background,
   then open the app. `CrashActivity` opens in previous-launch mode.

---

## Unit tests

On the JVM (`./gradlew :crash:testDebugUnitTest`):

| Test | Covers |
|---|---|
| `CrashSignatureTest` | What makes two crashes the same: the exception classes and top frames of the cause chain, not the messages; native crashes without addresses, pids and install directories |
| `CrashLogStoreTest` | One file per distinct crash, named with its time, type, signature and count; the same crash again counts up instead of making a new file |

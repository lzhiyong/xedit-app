# crash

English | [中文](README_CN.md) · [Design](DESIGN.md)

Captures **Java crashes, native crashes and ANRs** in-process, writes each one to disk first, then hands it to your callback (usually to open a screen showing it), where the user can copy, export or upload the report.

It **lives alongside the system's handling instead of replacing it**:

- A Java crash is recorded, then passed to the previous default handler, and the system ends the process as usual. Android vitals, `ApplicationExitInfo` and other crash SDKs still see it.
- A native crash is recorded, then the signal goes on, and `debuggerd` writes its tombstone as usual.
- ANRs are detected through SIGQUIT, which always goes on to ART, so the system's `traces.txt` is unaffected.

So it **does not keep the app running after a crash**. What it does: no crash is lost, the next launch shows it, and the user can send it.

## Adding it

Three things: the dependency, a screen in its own process `:crash`, and the install in `Application`. A complete working example is the app module's `EditorApplication` and `CrashActivity`.

**1. Dependency** (the native library is built with CMake; uploading needs WorkManager):

```kotlin
dependencies {
    implementation(project(":crash"))
    implementation("androidx.work:work-runtime-ktx:<version>")
}
```

**2. Manifest**: the screen runs in a process of its own, so it still shows when the main process hangs or is going away.

```xml
<uses-permission android:name="android.permission.INTERNET" />  <!-- for uploads -->

<activity
    android:name=".activity.CrashActivity"
    android:process=":crash"
    android:exported="false"
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="x.editor.app.CRASH_HANDLER" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

**3. Install**:

```kotlin
class MyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isCrashProcess()) return                     // never install in the :crash process

        CrashHandler.install(this, object : OnExceptionListener {
            override fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog) =
                startCrashActivity(log)                  // the system ends the process afterwards
            override fun onNativeCrash(signum: Int, message: String, log: CrashLog) =
                startCrashActivity(log)                  // debuggerd writes the tombstone afterwards
            override fun onAnrCrash(thread: Thread, error: AnrException, log: CrashLog) {
                // not overridden, it goes to onJavaCrash and the system's ANR dialog decides; here we end it
                try { startCrashActivity(log) } finally { Process.killProcess(Process.myPid()) }
            }
        })
    }

    private fun startCrashActivity(log: CrashLog) {
        val intent = Intent(Constants.ACTION_CRASH_HANDLER).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (log.file == null) intent.putExtra(Constants.EXTRA_CRASH_STACK_TRACE, log.content.take(100_000)) // only when not on disk
        runCatching { startActivity(intent) }            // failing is fine: the log is on disk for next launch
    }
}
```

**4. Show at next launch what could not be shown** (background crashes, a failed Activity start): when the first Activity is created, check `CrashLogStore.hasLogs(context)` and open the screen if there are any.

**5. Read and upload in the screen** (any process, background thread):

```kotlin
val crashes = CrashLogStore.consumeAll(context)    // reads and deletes; keep the text yourself if needed later
CrashReportUploader.enqueue(context, "https://example.com/crash", text)   // WorkManager: survives offline and process death
```

In the `:crash` process, initialize WorkManager yourself with the main process as its default (`Configuration.Builder().setDefaultProcessName(packageName)`); the main process runs the uploads.

## Rules to follow

- Call `install()` in `Application.attachBaseContext`, on the main thread, and **never** in the `:crash` process.
- Do not touch `CrashHandler` in `:crash` (it would load the native library); `CrashLogStore` and `CrashReportUploader` are safe there.
- **Do not exit the process** in `onJavaCrash` or `onNativeCrash`, and return quickly: the system and `debuggerd` end it.
- `onAnrCrash` runs while the main thread is stuck: no UI, and do not wait for the main thread.
- Several threads can crash at once: make a new `Intent` for each crash.
- Install other crash SDKs **after** `install()`: they wrap this handler, which still forwards to the system.
- Catch expected exceptions (a bad regex typed by the user, a malformed file, I/O errors) where they happen and say what went wrong. An exception that reaches the uncaught handler always ends the process.

After `CrashHandler.uninstall()` callbacks stop and the ANR monitor stops; the native signal handlers stay (other SDKs may have chained onto them), so native crashes are still saved and shown at the next launch.

## Logs

- Where: `filesDir/home/logs/` (the logs), `filesDir/home/native_crash/` (native write-ahead copies); the upload queue is `noBackupFilesDir/crash_reports/`.
- **Deduplication**: the same crash (a signature of exception classes and top frames, ignoring messages and addresses) replaces its earlier log and counts up; the 20 most recent kinds are kept.
- The header has the type, time, count, thread, process, app version, device, Android version and ABIs, followed by the stack trace.

## Testing

Only in debuggable builds (release builds just log a warning): `CrashHandler.testJavaCrash()`, `testNativeCrash()`, `testAnrCrash()` (with the app in the foreground; the ANR comes after about 10 seconds).

Worth checking by hand: after a Java crash `adb logcat -s AndroidRuntime` has `FATAL EXCEPTION`; after a native crash `home/native_crash/` is empty and there is a tombstone; the same crash twice shows once, marked `×2`; a crash in the background is shown on opening the app.

JVM unit tests: `./gradlew :crash:testDebugUnitTest` (crash signatures, log file names and counts).

## Limits

- **A repeated crash brings up the system dialog**: a second crash of the main process within a minute makes Android show "keeps stopping", possibly over the crash screen.
- **Native stacks are unwound in the crashing process**, using the heap. With a corrupted heap or a crash inside `malloc` the reporting thread can hang, leaving only the signal, thread and abort message, no stack. Mature solutions (Crashpad, Breakpad, xCrash) unwind from another process.
- **Only the crashing thread**: its stack and abort message, not the other threads, registers or memory maps (those are in the tombstone).
- **No symbolication**: release `.so` files are stripped, so native frames are addresses. Keep the unstripped libraries of each release and use `ndk-stack` or `llvm-symbolizer`.
- **Uploads are the user's choice**, so crash rates cannot be counted; pair it with a crash reporting service for that.
- **Not caught**: processes the system kills outright (low-memory kills, background ANRs ended silently); `ApplicationExitInfo` (API 30+) can fill that in at the next launch.
- A native crash adopted at the next launch has a header describing that launch (after an update, a different version).
- Logs can contain user data (exception messages): tell users before uploading and say so in your privacy policy.
- Native code is built for `arm64-v8a` only.

# crash: Usage

> Part of the documentation of the [crash](../crash/README.md) module · [中文](crash_usage_cn.md)

## Integration

### Gradle

The module builds its native library with CMake. The app module needs
WorkManager:

```groovy
dependencies {
    implementation project(':crash')                              // module name: adjust
    implementation "androidx.work:work-runtime-ktx:<version>"
}
```

### Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Export on Android 9 and below only -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<application android:name=".MyApplication" ...>

    <activity
        android:name=".activity.CrashActivity"
        android:process=":crash"
        android:exported="false"
        android:excludeFromRecents="true">
        <intent-filter>
            <!-- Must equal Constants.ACTION_CRASH_HANDLER -->
            <action android:name="x.editor.app.CRASH_HANDLER" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>

</application>
```

### Rules

- Call `CrashHandler.install()` in `Application.attachBaseContext`, on the main
  thread, and **never** in the `:crash` process.
- Do not exit the process in `onJavaCrash` or `onNativeCrash`. The system and
  `debuggerd` take care of it.
- In `onAnrCrash`, do not touch the UI or wait for the main thread.
- Do not access `CrashHandler` from the `:crash` process; that would load the
  native library there. `CrashLogStore` and `CrashReportUploader` are safe to
  use from `:crash`.
- Initialize WorkManager manually in `:crash` with the main process as its
  default process (see the example below). Its default initializer only runs
  in the main process.
- Create a new `Intent` for every crash. Several threads may crash at once.
- Other crash SDKs: install them **after** `CrashHandler.install()`. They then
  wrap this handler, which still forwards to the system.

## Usage

### Installing in the Application

```kotlin
class MyApplication : Application() {

    private var isCrashProcess = false

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)

        isCrashProcess = detectCrashProcess()
        if (isCrashProcess) return

        CrashHandler.install(this, object : OnExceptionListener {

            // The system kills the process after this returns
            override fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog) {
                startCrashActivity(log)
            }

            // debuggerd writes a crash and the process dies after this returns
            override fun onNativeCrash(signum: Int, message: String, log: CrashLog) {
                startCrashActivity(log)
            }

            // This example ends the process on a confirmed ANR. Omit the
            // override to fall back to onJavaCrash and leave the decision to
            // the system's ANR dialog.
            override fun onAnrCrash(thread: Thread, error: AnrException, log: CrashLog) {
                try {
                    startCrashActivity(log)
                } finally {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        if (isCrashProcess) {
            // :crash records upload work; the main process executes it
            try {
                WorkManager.initialize(
                    this,
                    Configuration.Builder().setDefaultProcessName(packageName).build()
                )
            } catch (e: IllegalStateException) {
                // already initialized
            }
        } else {
            showPendingCrashOnFirstActivity()
        }
    }

    private fun startCrashActivity(log: CrashLog) {
        try {
            val intent = Intent(Constants.ACTION_CRASH_HANDLER).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Normally the log is on disk; pass the text only if it is not
                if (log.file == null) {
                    putExtra(Constants.EXTRA_CRASH_STACK_TRACE, log.content.take(100_000))
                }
            }
            startActivity(intent)
        } catch (e: Throwable) {
            // The log is on disk and will be shown on the next launch
        }
    }

    // Crashes the app could not show (background, failed activity start):
    // open CrashActivity on top of the first activity of the next launch.
    private fun showPendingCrashOnFirstActivity() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                unregisterActivityLifecycleCallbacks(this)
                if (!CrashLogStore.hasLogs(this@MyApplication)) return
                activity.startActivity(
                    Intent(Constants.ACTION_CRASH_HANDLER)
                        .setPackage(packageName)
                        .putExtra(Constants.EXTRA_CRASH_FROM_PREVIOUS_LAUNCH, true)
                )
            }
            // other callbacks: empty
        })
    }
}
```

See `MyApplication.kt` for the complete version, including
`detectCrashProcess()`.

### Reading and uploading logs yourself

`CrashActivity` already does this. The minimal form, from any process of the
app, on a background thread:

```kotlin
val crashes: List<StoredCrash> = CrashLogStore.consumeAll(context)   // reads AND deletes
if (crashes.isNotEmpty()) {
    val text = crashes.joinToString("\n\n") { crash ->
        "[${crash.type?.label ?: "Crash"} x${crash.count}]\n${crash.content}"
    }
    CrashReportUploader.enqueue(context, "https://example.com/crash", text)
}
```

`consumeAll()` deletes what it returns, so keep the text if you still need it.

### Uninstalling

```kotlin
CrashHandler.uninstall()   // main thread
```

After `uninstall()`:

- The listener is no longer called.
- Java crashes go straight to the system. If another SDK has wrapped this
  handler since, the handler stays in that chain and only forwards, so the
  other SDK is not cut out.
- The ANR monitor is stopped.
- Native signal handlers stay registered, because other SDKs may have chained
  theirs on top. A native crash is still **saved** to disk and shown on the
  next launch, but not reported to the listener.

`install()` may be called again afterwards.

# crash：使用指南

> [crash](../crash/README_CN.md) 模块文档 · [English](crash_usage.md)

## 集成

### Gradle

本模块使用 CMake 构建其 Native 库。应用模块需要依赖 WorkManager：

```groovy
dependencies {
    implementation project(':crash')                              // 模块名：请按实际调整
    implementation "androidx.work:work-runtime-ktx:<version>"
}
```

### Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- 仅在 Android 9 及以下版本导出时需要 -->
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
            <!-- 必须与 Constants.ACTION_CRASH_HANDLER 一致 -->
            <action android:name="x.editor.app.CRASH_HANDLER" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>

</application>
```

### 规则

- 在 `Application.attachBaseContext` 中、在主线程上调用 `CrashHandler.install()`，并且**绝不**在 `:crash` 进程中调用。
- 不要在 `onJavaCrash` 或 `onNativeCrash` 中退出进程。系统和 `debuggerd` 会负责处理。
- 在 `onAnrCrash` 中，不要操作 UI，也不要等待主线程。
- 不要在 `:crash` 进程中访问 `CrashHandler`，否则会在该进程中加载 Native 库。`CrashLogStore` 和 `CrashReportUploader` 可以在 `:crash` 中安全使用。
- 在 `:crash` 中手动初始化 WorkManager，并将主进程设为其默认进程（参见下面的示例）。WorkManager 的默认初始化器只在主进程中运行。
- 每次崩溃都创建一个新的 `Intent`。可能有多个线程同时崩溃。
- 其他崩溃 SDK：请在 `CrashHandler.install()` **之后**安装。这样它们会包装本处理器，而本处理器仍会继续转发给系统。

## 使用方法

### 在 Application 中安装

```kotlin
class MyApplication : Application() {

    private var isCrashProcess = false

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)

        isCrashProcess = detectCrashProcess()
        if (isCrashProcess) return

        CrashHandler.install(this, object : OnExceptionListener {

            // 返回后系统会结束进程
            override fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog) {
                startCrashActivity(log)
            }

            // 返回后 debuggerd 会写入 crash，进程随之退出
            override fun onNativeCrash(signum: Int, message: String, log: CrashLog) {
                startCrashActivity(log)
            }

            // 本示例在确认 ANR 后结束进程。如果不重写此方法，
            // 则会回退到 onJavaCrash，并交由系统的 ANR 对话框决定。
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
            // :crash 负责记录上传任务；由主进程执行
            try {
                WorkManager.initialize(
                    this,
                    Configuration.Builder().setDefaultProcessName(packageName).build()
                )
            } catch (e: IllegalStateException) {
                // 已经初始化过
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
                // 正常情况下日志已在磁盘上；只有不在磁盘上时才传递文本
                if (log.file == null) {
                    putExtra(Constants.EXTRA_CRASH_STACK_TRACE, log.content.take(100_000))
                }
            }
            startActivity(intent)
        } catch (e: Throwable) {
            // 日志已在磁盘上，会在下次启动时显示
        }
    }

    // 应用未能显示的崩溃（后台崩溃、Activity 启动失败）：
    // 在下次启动的第一个 Activity 之上打开 CrashActivity。
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
            // 其他回调：空实现
        })
    }
}
```

完整版本（包括 `detectCrashProcess()`）请参见 `MyApplication.kt`。

### 自行读取和上传日志

`CrashActivity` 已经实现了这些功能。最简形式如下，可在应用的任意进程中、在后台线程上执行：

```kotlin
val crashes: List<StoredCrash> = CrashLogStore.consumeAll(context)   // 读取并删除
if (crashes.isNotEmpty()) {
    val text = crashes.joinToString("\n\n") { crash ->
        "[${crash.type?.label ?: "Crash"} x${crash.count}]\n${crash.content}"
    }
    CrashReportUploader.enqueue(context, "https://example.com/crash", text)
}
```

`consumeAll()` 会删除它所返回的内容，因此如果之后还需要这些文本，请自行保留。

### 卸载

```kotlin
CrashHandler.uninstall()   // 主线程
```

调用 `uninstall()` 之后：

- 不再调用监听器。
- Java 崩溃直接交给系统处理。如果在此期间有其他 SDK 包装了本处理器，本处理器会保留在该调用链中并只做转发，因此不会把其他 SDK 隔断。
- ANR 监控停止。
- Native 信号处理器保持注册状态，因为其他 SDK 可能已在其上链接了自己的处理器。Native 崩溃仍然会被**保存**到磁盘并在下次启动时显示，但不会通知监听器。

之后可以再次调用 `install()`。

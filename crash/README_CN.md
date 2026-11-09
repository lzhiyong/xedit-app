# crash

[English](README.md) | 中文 · [设计](DESIGN_CN.md)

在进程内捕获 **Java 崩溃、native 崩溃和 ANR**，先把每次崩溃写到磁盘，再交给你的回调（通常是打开一个展示崩溃的界面）。用户可以在其中复制、导出或上传报告。

它**与系统机制共存，而不是取而代之**：

- Java 崩溃记录后交还给原来的默认处理器，系统照常结束进程。Android vitals、`ApplicationExitInfo` 和其他崩溃 SDK 仍能看到它。
- native 崩溃记录后信号继续传递，`debuggerd` 照常生成 tombstone。
- ANR 通过 SIGQUIT 检测，SIGQUIT 总会转发给 ART，系统的 `traces.txt` 不受影响。

所以它**不会让应用在崩溃后继续运行**，它做的是：崩溃不丢、下次能看到、用户能提交。

## 接入

需要三样东西：模块依赖、一个运行在独立进程 `:crash` 中的展示界面、在 `Application` 中安装。完整可运行的例子是 app 模块的 `EditorApplication` 和 `CrashActivity`。

**1. 依赖**（模块用 CMake 构建 native 库，上传依赖 WorkManager）：

```kotlin
dependencies {
    implementation(project(":crash"))
    implementation("androidx.work:work-runtime-ktx:<version>")
}
```

**2. Manifest**：展示界面放在独立进程中，这样主进程卡死或正在退出时它仍能显示。

```xml
<uses-permission android:name="android.permission.INTERNET" />  <!-- 上传报告 -->

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

**3. 安装**：

```kotlin
class MyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isCrashProcess()) return                     // 绝不在 :crash 进程中安装

        CrashHandler.install(this, object : OnExceptionListener {
            override fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog) =
                startCrashActivity(log)                  // 返回后系统结束进程
            override fun onNativeCrash(signum: Int, message: String, log: CrashLog) =
                startCrashActivity(log)                  // 返回后 debuggerd 写 tombstone
            override fun onAnrCrash(thread: Thread, error: AnrException, log: CrashLog) {
                // 不重写时转给 onJavaCrash，由系统的 ANR 对话框决定；这里选择直接结束
                try { startCrashActivity(log) } finally { Process.killProcess(Process.myPid()) }
            }
        })
    }

    private fun startCrashActivity(log: CrashLog) {
        val intent = Intent(Constants.ACTION_CRASH_HANDLER).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (log.file == null) intent.putExtra(Constants.EXTRA_CRASH_STACK_TRACE, log.content.take(100_000)) // 没写进磁盘时才传文本
        runCatching { startActivity(intent) }            // 失败也没关系：日志在磁盘上，下次启动显示
    }
}
```

**4. 下次启动时显示没来得及显示的崩溃**（后台崩溃、Activity 启动失败）：在第一个 Activity 创建时检查 `CrashLogStore.hasLogs(context)`，有就打开展示界面。

**5. 在展示界面中读取和上传**（任意进程，后台线程）：

```kotlin
val crashes = CrashLogStore.consumeAll(context)    // 读取并删除，之后还需要文本请自己保留
CrashReportUploader.enqueue(context, "https://example.com/crash", text)   // WorkManager，离线、进程结束也不丢
```

在 `:crash` 进程中需要手动初始化 WorkManager，并把主进程设为默认进程（`Configuration.Builder().setDefaultProcessName(packageName)`），上传任务由主进程执行。

## 必须遵守的规则

- 在 `Application.attachBaseContext` 中、主线程上调用 `install()`，**绝不**在 `:crash` 进程中调用。
- `:crash` 进程中不要访问 `CrashHandler`（会加载 native 库）；`CrashLogStore` 和 `CrashReportUploader` 可以安全使用。
- `onJavaCrash`、`onNativeCrash` 中**不要退出进程**，并尽快返回，系统和 `debuggerd` 会负责结束。
- `onAnrCrash` 运行在主线程卡住期间：不要操作 UI，也不要等待主线程。
- 可能有多个线程同时崩溃，每次崩溃都创建新的 `Intent`。
- 其他崩溃 SDK 请在 `install()` **之后**安装，它们会包装本处理器，本处理器仍会继续转发给系统。
- 可预期的异常（用户输入的错误正则、格式错误的文件、I/O 错误）应在发生处捕获并提示。到达未捕获处理器的异常一定会结束进程。

`CrashHandler.uninstall()` 后不再调用回调，ANR 监控停止；native 信号处理器保留（其他 SDK 可能已链接在其上），native 崩溃仍会保存并在下次启动时显示。

## 日志

- 位置：`filesDir/home/logs/`（正式日志）、`filesDir/home/native_crash/`（native 预写副本），上传队列在 `noBackupFilesDir/crash_reports/`。
- **去重**：同一个崩溃（按异常类和栈顶帧计算签名，忽略消息和地址）再次发生时替换旧日志并把次数加一，最多保留最近 20 种。
- 日志头包含类型、时间、次数、线程、进程、应用版本、设备、Android 版本和 ABI，之后是调用栈。

## 测试

只在可调试构建中生效（release 中只输出警告）：`CrashHandler.testJavaCrash()`、`testNativeCrash()`、`testAnrCrash()`（应用在前台时调用，约 10 秒后判定 ANR）。

建议手动检查：Java 崩溃后 `adb logcat -s AndroidRuntime` 有 `FATAL EXCEPTION`；native 崩溃后 `home/native_crash/` 为空且有 tombstone；同一崩溃触发两次只显示一条并标记 `×2`；后台崩溃后打开应用会显示上次的崩溃。

JVM 单元测试：`./gradlew :crash:testDebugUnitTest`（崩溃签名、日志文件命名与计数）。

## 限制

- **重复崩溃会弹出系统对话框**：主进程一分钟内第二次崩溃时，Android 会显示「应用屡次停止运行」，可能盖在展示界面之上。
- **native 调用栈在崩溃进程内回溯**并使用堆内存。堆损坏或崩溃发生在 `malloc` 中时，上报线程可能卡住，只能留下信号、线程和 abort 消息，没有调用栈。成熟方案（Crashpad、Breakpad、xCrash）在独立进程中回溯。
- **只包含崩溃线程**的调用栈和 abort 消息，不含其他线程、寄存器和内存映射（这些在 tombstone 中）。
- **不做符号化**：release 的 `.so` 经过 strip，栈帧显示为地址。请为每个版本保留未 strip 的库，用 `ndk-stack` 或 `llvm-symbolizer` 还原。
- **上传由用户发起**，无法统计崩溃率；需要统计请搭配崩溃上报服务。
- **无法捕获**被系统直接杀死的进程（低内存终止、静默结束的后台 ANR），可用 `ApplicationExitInfo`（API 30+）在下次启动时补上。
- 下次启动时接管的 native 崩溃，日志头描述的是接管时的那次启动（如应用已更新，版本号不同）。
- 日志可能包含用户数据（异常消息），上传前请告知用户并在隐私政策中说明。
- native 代码只构建 `arm64-v8a`。

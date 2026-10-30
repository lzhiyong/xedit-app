# crash：测试

> [crash](../crash/README_CN.md) 模块文档 · [English](crash_testing.md)

测试 API 仅在可调试（debuggable）的构建中有效；在 release 构建中它们只会输出一条警告，不执行任何操作。

| 调用 | 效果 |
|---|---|
| `CrashHandler.testJavaCrash()` | 在调用线程上抛出 `RuntimeException` |
| `CrashHandler.testNativeCrash()` | 在 Native 代码中解引用空指针 |
| `CrashHandler.testAnrCrash()` | 在前台有序广播中使主线程死锁；系统会在大约 10 秒后判定 ANR。请在应用处于前台时调用。 |

建议执行的检查：

1. **Java 崩溃到达系统。** 调用 `testJavaCrash()` 后，`CrashActivity` 打开，`adb logcat -s AndroidRuntime` 显示 `FATAL EXCEPTION`，并且 `adb shell dumpsys activity exit-info <package>`（API 30+）列出原因为 `CRASH` 的退出记录。
2. **Native 兜底文件清理。** 调用 `testNativeCrash()` 后，`files/native_crash/` 为空，并且存在 crash。
3. **兜底文件接管。** 临时在 `CrashHandler.callback` 开头抛出异常，触发一次 Native 崩溃，然后重新启动应用。该崩溃会出现在 `CrashActivity` 中，并标记为从 Native 兜底报告中恢复。
4. **去重。** 在打开应用之前触发两次相同的崩溃。只会出现一条记录，并标记为 `×2`。
5. **后台崩溃。** 在应用处于后台时触发崩溃，然后打开应用。`CrashActivity` 会以上次启动模式打开。

---

## 单元测试

在 JVM 上运行（`./gradlew :crash:testDebugUnitTest`）：

| 测试 | 覆盖内容 |
|---|---|
| `CrashSignatureTest` | 判断两次崩溃是否为同一种：取决于异常类和原因链上的栈顶帧，与异常消息无关；原生崩溃会去掉地址、pid/tid 和安装目录这些每次都会变化的部分 |
| `CrashLogStoreTest` | 每种不同的崩溃对应一个文件，文件名包含时间、类型、签名和次数；同一种崩溃再次发生时，计数加一，而不是新建文件 |

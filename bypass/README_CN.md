# bypass

解除 Android 9 及以上版本对非 SDK 接口（隐藏 API）的限制，使应用可以通过反射访问它们。

English version: [README.md](README.md).

**X Editor 目前没有使用本模块**，app 模块不依赖它。

## 工作原理

`Reflection.bypass()` 调用 `VMRuntime.setHiddenApiExemptions(["L"])`，使所有类都被豁免（`L` 是所有类描述符的开头）。`VMRuntime.getRuntime` 和 `setHiddenApiExemptions` 本身也是隐藏 API，所以要用能通过检查的方式获取它们：

- **Android 9**：二级反射，也就是通过反射去调用 `Class.getDeclaredMethod` 本身；
- **Android 10 及以上**：通过 JNI（`bypass.cpp`），在一个附加到虚拟机的原生线程上查找。这个线程上没有任何 Java 调用帧，而隐藏 API 检查是根据调用者来判断的，因此找不到需要限制的应用。

## 用法

- **自动执行**：模块的清单中声明了 `BypassProvider`，应用启动时（在 `Application.onCreate` 之前）会自动执行解除限制。不要自己创建或调用它。
- **手动执行**：`Reflection.bypass { /* 此后可以访问隐藏 API */ }`。
- `Reflection.getDeclaredMethod` 和 `Reflection.getDeclaredField` 用同样的方式查找成员。

## 注意事项

- 它依赖 ART 检查隐藏 API 的具体实现方式，在以后的 Android 版本上可能失效。
- Google Play 对使用非 SDK 接口有限制，发布前请先确认相关政策。
- 原生代码目前只构建 `arm64-v8a`。

## 测试

`ReflectionTest`，在 JVM 上运行（`./gradlew :bypass:testDebugUnitTest`）。JVM 上 `Build.VERSION.SDK_INT` 为 0，因此测试的是二级反射这条路径：按名称获取私有方法和私有字段，以及成员不存在时的报错。测试还确认 `bypass()` 在 JVM 上会失败，因为 JVM 没有 `dalvik.system.VMRuntime`。解除限制本身以及 JNI 路径，只能在设备上运行。

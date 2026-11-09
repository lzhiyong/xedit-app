# bypass

[English](README.md) | 中文

解除 Android 9 及以上对非 SDK 接口（隐藏 API）的限制，让应用可以通过反射访问它们。

**X Editor 目前没有使用本模块**，app 不依赖它。

## 使用

- **自动**：模块清单中声明了 `BypassProvider`，应用启动时（`Application.onCreate` 之前）自动解除限制。引入模块即可，不要自己创建或调用它。
- **手动**：`Reflection.bypass { /* 此后可以访问隐藏 API */ }`。
- `Reflection.getDeclaredMethod`、`Reflection.getDeclaredField` 用同样能通过检查的方式查找成员。

## 原理

调用 `VMRuntime.setHiddenApiExemptions(["L"])` 豁免所有类（`L` 是所有类描述符的开头）。但 `getRuntime` 和 `setHiddenApiExemptions` 本身也是隐藏 API，所以要用能通过检查的方式拿到它们：

- **Android 9**：二级反射，即通过反射调用 `Class.getDeclaredMethod` 本身。
- **Android 10+**：在 JNI 中、在一个附加到虚拟机的原生线程上查找。这个线程没有 Java 调用帧，而隐藏 API 检查是按调用者判断的，因此找不到需要限制的应用。

## 限制

- 依赖 ART 检查隐藏 API 的具体实现，以后的 Android 版本上可能失效。
- Google Play 对使用非 SDK 接口有限制，发布前请确认政策。
- native 代码只构建 `arm64-v8a`。

## 测试

`./gradlew :bypass:testDebugUnitTest`：`ReflectionTest` 在 JVM 上测试二级反射路径（按名称获取私有方法和字段、成员不存在时的报错），并确认 `bypass()` 在 JVM 上会失败（没有 `VMRuntime`）。解除限制本身和 JNI 路径只能在设备上验证。

# document：测试

> [document](../document/README_CN.md) 模块文档 · [English](document_testing.md)

```
src/
├── test/                          JVM 测试（Robolectric），无需设备
│   ├── DocumentFlagsTest          可读 / 可写 / 虚拟文档规则
│   ├── RawDocumentFileTest        文件系统实现
│   ├── DocumentFileFactoryTest    Uri 分派、provider 不可达时的行为
│   └── TempDirectory              临时目录辅助类
└── androidTest/                   针对真实 DocumentsProvider 的仪器化测试
    ├── AndroidManifest.xml        注册 FakeDocumentsProvider
    ├── FakeDocumentsProvider      以缓存目录为后端的 provider，并统计查询次数
    ├── FakeProviderFixture        为每个测试提供全新的根目录
    ├── TreeDocumentFileTest
    └── SingleDocumentFileTest
```

仪器化测试走的是真实的框架代码路径（`DocumentsContract`、`DocumentsProvider`、权限检查），这些是 Robolectric 无法完整模拟的。假 provider 会统计自身的查询次数，因此测试也会验证上面性能表中的查询次数。

测试的注解和断言都使用 `kotlin.test`。唯一直接引用的 JUnit API 是 `@RunWith`，用于选择 Robolectric 或 AndroidX 的测试运行器；JUnit 4 本身由这两个库传递引入。

在模块的 `build.gradle.kts` 中添加以下配置（请使用可用的最新版本）：

```kotlin
android {
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

如果 `kotlin.test.Test` 或 `BeforeTest` 无法解析，说明 Kotlin Gradle 插件没有自动选中 JUnit 4 变体，请把 `kotlin("test")` 换成 `kotlin("test-junit")`。

运行方式：

```bash
./gradlew :document:testDebugUnitTest            # JVM 测试
./gradlew :document:connectedDebugAndroidTest    # 在设备或模拟器上运行
```

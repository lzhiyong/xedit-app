# piecetable：测试

> [piecetable](../piecetable/README_CN.md) 模块文档 · [English](piecetable_testing.md)

全部用例都放在 **`src/sharedTest`**，一份代码跑两遍：JVM 上 `testDebugUnitTest`，真机上
`connectedDebugAndroidTest`。只有必须在设备上才有意义的用例才留在 `androidTest`。

```
src/
├── main/java/x/github/module/piecetable/        # 实现
├── sharedTest/java/x/github/module/piecetable/  # JVM 与设备共用
│   ├── TestSupport.kt                   # 公共断言、红黑树不变量与元数据对账
│   ├── PieceTreeBaseTest.kt             # 移植自 VSCode 的用例集（86 条）
│   ├── PieceTreeBuilderTest.kt          # 分块、BOM、EOL 嗅探、代理对
│   ├── PieceTreeEditingTest.kt          # 编辑、行列映射、CRLF、快照
│   ├── PieceTreeTextBufferApiTest.kt    # applyEdits、撤销数据、区间、CharSequence
│   ├── PieceTreeSearchTest.kt           # 按词 / 正则 / 多行搜索
│   ├── PieceTreeEditOperationTest.kt    # getInverseEditRanges、toSingleEditOperation
│   ├── PieceTreeUndoRedoTest.kt         # 撤销 / 重做，不依赖协程与真实时间
│   ├── PieceTreeStressTest.kt           # 固定种子的随机测试、大文档
│   ├── PieceTreeMaintenanceTest.kt      # compact、存储统计、共享状态恢复
│   ├── PieceTreeSnapshotTest.kt         # 快照跨编辑 / compact / setEOL 不变、编辑期间并发读取
│   ├── PieceTreeUndoRoundTripTest.kt    # CRLF 与混合换行下 applyEdits + 撤销 / 重做的随机往返
│   ├── PieceTreeSearchOracleTest.kt     # find() 与朴素实现随机对拍
│   ├── PieceTreeSliceTest.kt            # 片段撤销 / 重做：随机往返、穿插 compact / setEOL、内存
│   ├── RandomEditing.kt                 # 随机测试共用：String 模型、随机编辑、位置换算
│   └── RegressionTest.kt                # 每个已修复缺陷一条（49 条），请勿删除
└── androidTest/java/x/github/module/piecetable/
    └── AndroidDeviceTest.kt             # API level 敏感路径、真机耗时
```

把共享源集同时挂到两个测试变体上：

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // java + kotlin 都要加：使用 AGP 内置 Kotlin（没有 org.jetbrains.kotlin.android 插件）时，
        // Kotlin 文件只会通过 kotlin.srcDir 被收录
        getByName("test") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
    }
    testOptions {
        unitTests.all {
            // 缓冲区之间没有共享的可变状态，测试类也可以并行跑
            // （例如 maxParallelForks = 2），这里无需额外配置
            it.forkEvery = 0
        }
    }
}

dependencies {
    // JVM：JUnit 5（tasks.withType<Test> { useJUnitPlatform() }）
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 设备：AndroidJUnitRunner 是 JUnit 4 运行器，kotlin.test 必须用 JUnit 4 版本
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
```

测试里访问了 `internal` 成员（`PieceTreeBase`、`TreeNode`、sentinel）。AGP 会把 `test` 与
`androidTest` 两个编译单元都设为主变体的 friend module，无需额外配置。

## androidTest 报 `Unresolved reference 'TestSupport'`

`TestSupport` 在 `src/sharedTest` 里。只有把这个目录挂到 `androidTest` 源集上，androidTest 的编译
才能看到它，所以这个错误说明没有挂上（如果是 `internal` 可见性问题，报错会是 "Cannot access"，
而不是 "Unresolved reference"）：

1. 确认模块的 `build.gradle.kts` 里，上面的 `sourceSets` 配置对 `test` **和** `androidTest` 都写了，
   而且每个都同时有 `java.srcDir` **和** `kotlin.srcDir`。没有 `org.jetbrains.kotlin.android` 插件的
   模块使用 AGP 内置 Kotlin，它只通过 `kotlin.srcDir` 收录 Kotlin 文件；只写 `java.srcDir` 时，
   JVM 测试可能正常，而 androidTest 里唯一的文件 `AndroidDeviceTest` 找不到 `TestSupport`。
2. `./gradlew :piecetable:sourceSets` 会打印每个源集包含的目录，`androidTest` 一项里必须有
   `src/sharedTest/java`。
3. 如果目录已列出但其中的 Kotlin 文件仍未参与编译，检查 `androidTest` 是否也加了 `kotlin.srcDir`。
4. Android Studio 可能把两个源集共享的目录里的代码标红，而 Gradle 构建其实正常，以命令行结果为准。
5. 不经过 AGP 的构建会忽略 `sourceSets`：把 `src/sharedTest/java` 里的文件复制（或软链接）到
   `src/androidTest/java`。

## 设备测试能编译，但几乎什么都没跑

`AndroidJUnitRunner` 是 JUnit 4 运行器。androidTest 的依赖里如果是 `kotlin-test-junit5`，
`kotlin.test.Test` 就是 JUnit 5 的注解：所有共享用例都会被跳过且不报错，只有使用 `org.junit.Test`
的 `AndroidDeviceTest` 会运行，`@BeforeTest` 也从不执行。androidTest 请用 `kotlin-test-junit`，
JVM 测试继续用 `kotlin-test-junit5`；共享源码只用 `kotlin.test.*`，两种都能编译。

## `Space characters in SimpleName ... are not allowed prior to DEX version 040`

反引号测试名在 JVM 上可以含空格，但 DEX 从 API 30 起才接受。模块的 `minSdk` 低于 30 时
（没写 `minSdk` 等于 1），打包 androidTest APK 会在 `` `random insert and delete bug 2` `` 这类名字上
失败。把所有共享用例名里的空格换成下划线即可（PieceTreeBaseTest、PieceTreeEditOperationTest、
PieceTreeUndoRedoTest 共 116 个，替换后名字仍然唯一）：

```bash
perl -pi -e 's/fun `([^`]*)`/"fun `" . ($1 =~ tr| |_|r) . "`"/ge' \
    src/sharedTest/java/x/github/module/piecetable/*.kt
```

同时在 `defaultConfig` 里把 `minSdk` 设成与应用一致，让设备测试按库真正支持的 API 级别构建。

```bash
# JVM 上跑整套共享用例
./gradlew :piecetable:testDebugUnitTest

# 真机 / 模拟器上跑共享用例 + AndroidDeviceTest
./gradlew :piecetable:connectedDebugAndroidTest

# JVM 上只跑某一个类
./gradlew :piecetable:testDebugUnitTest --tests "*RegressionTest"

# 设备上只跑某一个类
./gradlew :piecetable:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=x.github.module.piecetable.RegressionTest
```

随机用例全部使用固定种子（`PieceTreeStressTest.SEEDS`、`PieceTreeBaseTest.SEED`、
`PieceTreeUndoRoundTripTest.SEEDS`、`PieceTreeSearchOracleTest.SEEDS`），失败可以原样复现；
失败信息会写明种子和步骤。想扩大覆盖面就加种子，不要换回 `Math.random()`。

针对曾经的死循环写回归用例时，用 `TestSupport.runWithTimeout { ... }` 包住调用：缺陷复发时
该用例会在 10 秒后失败，而不是把整套测试卡住。

新增用例时注意两点：

- `sharedTest` 里不能出现 `android.*` / `androidx.*`，否则 JVM 变体编译不过；设备特有的用例
  放 `androidTest`。
- 可以并行跑测试：缓冲区之间没有共享的可变状态。`TestSupport.resetGlobalState()` 只是为了让现有的
  `@BeforeTest` 继续编译而保留的廉价保险。

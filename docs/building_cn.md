# 构建

> [X Editor](../README_cn.md) 文档 · [English](building.md)

环境要求：

- JDK 21
- Android SDK：platform 36，build tools 37.0.0
- NDK 29.0.14206865，以及 CMake 4.1.2 或更高版本，用于构建 `treesitter` 和 `crash` 模块的原生代码
- Python 3 和 git，用于拉取 tree-sitter 源码
- Gradle 9 或更高版本（Android Gradle 插件为 9.2.0，Kotlin 为 2.3.21）

各项版本见 [`libs.versions.toml`](../libs.versions.toml)。

构建步骤：

```sh
# 1. 拉取 tree-sitter 源码，克隆到 treesitter/src/main/cpp/tree-sitter
cd treesitter && python3 get_sources.py && cd ..

# 2. 生成 Gradle wrapper（仓库中没有附带），也可以直接用 Android Studio 构建
gradle wrapper

# 3. 构建应用
./gradlew :app:assembleDebug
```

原生代码只构建 `arm64-v8a`（见 `treesitter/build.gradle.kts` 中的 `abiFilters`）。如果需要在其他设备或模拟器上运行，在这里添加对应的 ABI。

## Release 构建与 R8

Release 构建由 R8 进行代码压缩、优化和混淆（`isMinifyEnabled`），并移除没有被引用的资源
（`isShrinkResources`），见 [`app/build.gradle.kts`](../app/build.gradle.kts)：

```sh
./gradlew :app:assembleRelease
```

- R8 不能改动的部分由 [`app/proguard-rules.pro`](../app/proguard-rules.pro) 和各模块的
  `consumer-rules.pro` 保留：`treesitter`、`crash`、`bypass` 的 native 代码按名字查找的类、字段和回调，
  lsp4j 的协议类，以及通过反射列出的字体（同时由 `res/raw/keep.xml` 防止被资源压缩移除）。
- 新增通过反射或 JNI 访问的代码时，在所在模块的 `consumer-rules.pro` 中加上对应规则。
- 混淆映射文件输出到 `app/build/outputs/mapping/release/mapping.txt`。每次发布都要保存它：崩溃报告中的
  堆栈要用它还原（`retrace mapping.txt stacktrace.txt`）；行号信息是保留的。
- 仓库中没有签名配置：请用自己的密钥为 release APK 签名（`app/build.gradle.kts` 中的
  `signingConfigs`，或使用 `apksigner`）。CI 构建的是 debug APK，不经过 R8。

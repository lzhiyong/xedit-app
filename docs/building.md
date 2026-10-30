# Building

> Part of the [X Editor](../README.md) documentation · [中文](building_cn.md)

Requirements:

- JDK 21
- Android SDK: platform 36, build tools 37.0.0
- NDK 29.0.14206865 and CMake 4.1.2 or later, for the `treesitter` and `crash` native code
- Python 3 and git, to fetch the tree-sitter sources
- Gradle 9 or later (the Android Gradle plugin is 9.2.0, Kotlin is 2.3.21)

The versions are in [`libs.versions.toml`](../libs.versions.toml).

Steps:

```sh
# 1. the tree-sitter sources, cloned into treesitter/src/main/cpp/tree-sitter
cd treesitter && python3 get_sources.py && cd ..

# 2. a Gradle wrapper (none is checked in), or build from Android Studio
gradle wrapper

# 3. the app
./gradlew :app:assembleDebug
```

Native code is built for `arm64-v8a` only (`abiFilters` in `treesitter/build.gradle.kts`).
Add ABIs there for other devices or emulators.

## Release builds and R8

Release builds are shrunk, optimized and obfuscated by R8 (`isMinifyEnabled`), and the
resources nothing refers to are left out (`isShrinkResources`), see
[`app/build.gradle.kts`](../app/build.gradle.kts):

```sh
./gradlew :app:assembleRelease
```

- What R8 must not touch is kept by [`app/proguard-rules.pro`](../app/proguard-rules.pro) and by
  the `consumer-rules.pro` of each module: the classes, fields and callbacks the native code of
  `treesitter`, `crash` and `bypass` finds by name, the protocol classes of lsp4j, the fonts
  listed by reflection (also kept from the resource shrinker by `res/raw/keep.xml`).
- When you add code that is reached by reflection or from JNI, add a rule to the
  `consumer-rules.pro` of its module.
- The mapping of the obfuscated names is written to
  `app/build/outputs/mapping/release/mapping.txt`. Keep it with each release: the stack traces of
  the crash reports are turned back with it (`retrace mapping.txt stacktrace.txt`); the line
  numbers are kept.
- No signing configuration is checked in: sign the release APK with your own key
  (`signingConfigs` in `app/build.gradle.kts`, or `apksigner`). The CI workflow builds the debug
  APK, which R8 does not process.

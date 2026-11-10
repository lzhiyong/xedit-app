# document

[English](README.md) | 中文

AndroidX `DocumentFile` 的重写版，更快、更安全。一套 API 同时覆盖普通文件和存储访问框架（SAF）中的文档：

| 类 | 底层 | 创建方式 |
| --- | --- | --- |
| `RawDocumentFile` | `java.io.File` | `DocumentFile.fromFile()` |
| `SingleDocumentFile` | 单个 SAF 文档 | `fromSingleUri()`，或树中的文件子项 |
| `TreeDocumentFile` | SAF 树中的目录 | `fromTreeUri()`，或树中的目录子项 |

和 AndroidX 版本相比：

- **列目录只需一次查询**：`listFiles()` 一次读取所有子项及其名称、类型、大小、修改时间。AndroidX 版本要 1 + 文件数 + 2 × 子目录数次。
- **构造时不做 I/O**：元数据首次访问时才读取（或直接来自列目录的结果），缓存到 `refresh()` 为止。
- **不会因存储状态崩溃**：文档已删除、权限被撤销、provider 出错，都以 `null` / `false` / `0` 返回而不是抛异常。
- **实例不可变**：Uri 永不改变，`renameTo()` 返回新实例。
- **安全的文件操作**：拒绝会逃出目录的名称，重命名不覆盖已有文件，递归删除不跟随符号链接。
- `equals()` / `hashCode()` 基于 Uri，可直接作 Map 键或用于 `DiffUtil`。

## 接入

```kotlin
// settings.gradle.kts
include(":document")
// build.gradle.kts
dependencies { implementation(project(":document")) }
```

只依赖 `androidx.annotation`。

## 使用

标注 `@WorkerThread` 的方法都可能执行 I/O，不要在主线程调用。

```kotlin
// 用户选择的目录树
val root = DocumentFile.fromTreeUri(context, treeUri)          // 非 tree Uri 时为 null；Uri 指向文件时返回 SingleDocumentFile
val entries = root?.listFiles().orEmpty()                       // 子项已带元数据，读取不再查询
val config = root?.find("config.json")                          // 一次查询，只构建匹配项

// 单个文档、磁盘文件、任意 Uri
val doc = DocumentFile.fromSingleUri(context, uri)              // 永不为 null，懒加载
val cache = DocumentFile.fromFile(context, context.cacheDir)
val any = DocumentFile.fromUri(context, uri)                    // 按 scheme 分派，不支持时为 null

// 创建、重命名、删除：失败时看返回值
val notes = dir.createFile("text/plain", "notes")               // provider 可能追加 ".txt"
val renamed = notes?.renameTo("todo.txt")                       // 之后用 renamed，notes 仍指向旧 Uri
if (dir.createDirectory("images")?.delete() != true) showError()

doc.refresh()                                                   // 文档可能已变化时重新读取元数据
```

返回值的含义：

| 情况 | 结果 |
| --- | --- |
| 文档已删除、权限被撤销、provider 出错 | `exists()` / `delete()` 为 `false`；`listFiles()` / `createFile()` / `renameTo()` 为 `null`；元数据为 `""` / `0` |
| 对非目录或无法读取的目录调用 `listFiles()` | `null`（没有可显示的内容） |
| 空目录 | 空列表 |
| 对 `SingleDocumentFile` 调用 `createFile()` | 抛 `UnsupportedOperationException` |

失败原因输出到 `DocumentFile` 标签的日志中。

## 从 AndroidX 版本迁移

方法名不变，大部分代码可以直接编译，需要注意：

- `renameTo()` 不再修改原实例，请使用返回的新实例。
- `fromTreeUri()` 对非 tree Uri 返回 `null` 而不是抛异常；`fromFile()`、`fromSingleUri()` 返回非空类型。
- `RawDocumentFile.listFiles()` 现在真正返回子项；目录无法读取时返回 `null`。
- `SingleDocumentFile.isDirectory()` / `isFile()` 按 MIME 类型判断。
- 未知的名称和类型为 `""`，不再是 `"."` 和 `"unknown"`。
- 删除 `DocumentsContractApi.kt` 和 `DocumentsContractApi19.kt`，移除了 `DocumentFile.LOG_TAG`。

## 限制

- `TreeDocumentFile`、`SingleDocumentFile` 报告的是最近一次读取时的元数据，文档可能已变化时调用 `refresh()`。`exists()` 每次都查询。
- `getParent()` 取决于实例是如何得到的，工厂方法创建的文档没有父节点。
- `TreeDocumentFile.getChildCount()` 被缓存；通过同一实例创建子项时自动失效，其他地方的修改需要 `refresh()`。
- 返回值不区分「provider 出错」和「未找到」，原因请看日志。

## 测试

```bash
./gradlew :document:testDebugUnitTest            # JVM（Robolectric）：标志位规则、文件系统实现、Uri 分派
./gradlew :document:connectedDebugAndroidTest    # 设备：基于缓存目录的假 DocumentsProvider
```

设备测试走真实的 `DocumentsContract` 和权限检查（Robolectric 模拟不了），假 provider 会统计查询次数，从而验证上面说的「列目录一次查询」。

## 许可证

基于 AndroidX `DocumentFile`，Copyright © The Android Open Source Project，Apache License 2.0。

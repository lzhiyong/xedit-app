# DocumentFile

AndroidX `DocumentFile` 的一个更快、更安全的重写版本，适用于通过存储访问框架（SAF）或直接在磁盘上浏览文件的应用。一套 API 覆盖三种文档：

| 类 | 底层 | 创建方式 |
|---|---|---|
| `RawDocumentFile` | `java.io.File` | `DocumentFile.fromFile()` |
| `SingleDocumentFile` | 单个 SAF 文档 | `DocumentFile.fromSingleUri()`，或树中的文件子项 |
| `TreeDocumentFile` | SAF 树中的目录 | `DocumentFile.fromTreeUri()`，或树中的目录子项 |

```
document
├── DocumentFile          公开 API 与工厂方法
├── RawDocumentFile       基于 java.io.File 的实现
├── SingleDocumentFile    单个 SAF 文档
├── TreeDocumentFile      SAF 树中的目录
├── DocumentInfo          （internal）从一行游标数据读取的元数据快照
├── DocumentQueries       （internal）从不抛出异常的 provider 调用
└── DocumentFlags         （internal）可读 / 可写 / 虚拟文档的判断规则
```

## 特性

- **列目录只需一次查询。** `listFiles()` 用一次 provider 查询读取所有子项及其元数据，而不是为每个子项再额外查询一到两次。
- **构造函数中不做 I/O。** 元数据在首次访问时懒加载，或直接来自列目录的查询结果，并缓存到调用 `refresh()` 为止。
- **不会因存储状态而崩溃。** 文档已删除、权限被撤销、provider 出错等情况都以 `null` / `false` / `0` 的形式返回，而不是抛出异常。
- **实例不可变。** 文档的 Uri 永远不变；`renameTo()` 返回一个新实例。
- **`fromUri()` 支持任意 Uri**：`file://`、tree Uri 和单文档 Uri。
- **安全的文件操作**：会逃出所在目录的名称会被拒绝，重命名绝不覆盖已有文件，递归删除绝不跟随符号链接。
- **基于 Uri 的 `equals()` / `hashCode()`**，文档可以作为 Map 的键，也可以直接用于 `DiffUtil`。

## 环境要求

- minSdk 24
- `androidx.annotation:annotation`

## 安装

引入模块并添加依赖：

```kotlin
// settings.gradle.kts
include(":document")

// app/build.gradle.kts
dependencies {
    implementation(project(":document"))
}
```

## 使用方法

所有标注了 `@WorkerThread` 的方法都可能执行 I/O，请勿在主线程中调用。

### 打开用户选择的目录树

```kotlin
private val openTree = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { treeUri ->
    if (treeUri == null) return@registerForActivityResult
    // 在应用重启后仍保留访问权限。
    contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    lifecycleScope.launch {
        val entries = withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            root?.listFiles().orEmpty()
                .sortedWith(compareBy({ !it.isDirectory() }, { it.getName().lowercase() }))
        }
        showEntries(entries)
    }
}
```

`listFiles()` 返回的子项已经携带了名称、类型、大小、最后修改时间和标志位，读取这些信息不会产生额外的查询。

### 创建、重命名和删除

```kotlin
val notes = directory.createFile("text/plain", "notes")      // provider 可能会追加 ".txt"
val images = directory.createDirectory("images")

val renamed = notes?.renameTo("todo.txt")   // 之后请使用 `renamed`；
                                            // `notes` 仍指向旧的 Uri

if (images?.delete() != true) showError("无法删除该文件夹")
```

### 单个文档与磁盘文件

```kotlin
// 来自 ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT。在调用 getter 之前不做任何 I/O。
val document = DocumentFile.fromSingleUri(context, uri)

// 应用本身已有访问权限的文件或目录。
val cache = DocumentFile.fromFile(context, context.cacheDir)

// 根据 Uri 的形式自动选择上述之一。
val anything = DocumentFile.fromUri(context, uri)   // 不支持的 scheme 返回 null
```

### 查找子项

```kotlin
val config = root.find("config.json")   // 一次查询；只构建匹配的那一项
```

## 行为说明

### 元数据快照

`SingleDocumentFile` 和 `TreeDocumentFile` 只读取一次元数据并缓存。如果文档可能已经发生变化，请调用 `refresh()`：

```kotlin
document.refresh()          // 重新读取元数据；对于目录，还会重新统计子项数量
val size = document.length()
```

`exists()` 每次都会查询 provider。`RawDocumentFile` 不做任何缓存。

### 用返回值代替异常

| 情况 | 结果 |
|---|---|
| 文档已删除、权限被撤销、provider 出错 | `exists()` / `delete()` → `false`；`listFiles()` / `createFile()` / `renameTo()` → `null`；元数据 → `""` / `0` |
| 对非目录调用 `listFiles()` | `null` |
| 对空目录调用 `listFiles()` | 空列表 |
| 对 `SingleDocumentFile` 调用 `createFile()` / `createDirectory()` | `UnsupportedOperationException` |

也就是说，`listFiles()` 返回 `null` 表示“没有可显示的内容”，返回空列表则表示“目录是空的”。失败信息会以 `DocumentFile` 为标签输出到日志。

### 不可变性

实例的 Uri 永远不会改变。`renameTo()` 返回一个新实例，原实例保持不变；重命名之后，原实例通常 `exists()` 会返回 `false`。

### `fromTreeUri()`

- 既接受 `ACTION_OPEN_DOCUMENT_TREE` 返回的 tree Uri，也接受树内的文档 Uri。
- 如果 Uri 不是 tree Uri，返回 `null`。
- 如果 Uri 指向文件，返回 `SingleDocumentFile`；否则返回 `TreeDocumentFile`。
- 恰好执行一次查询。

### 磁盘文件

`RawDocumentFile.createFile()` 会追加该 MIME 类型对应的扩展名，除非名称已经以该扩展名结尾；如果没有注册对应的扩展名，则直接使用原名称。`createFile()`、`createDirectory()` 和 `renameTo()` 会拒绝包含 `/` 的名称以及 `.` 和 `..`。`renameTo()` 在目标文件已存在时会失败，而不是覆盖它。`delete()` 会递归删除目录，但对于符号链接只删除链接本身，绝不删除其指向的目标。

## 性能

对于一个包含 **F** 个文件和 **D** 个子目录的目录，provider 查询次数如下：

| 操作 | 旧版本 | 现在 |
|---|---|---|
| `fromTreeUri()` | 2 | 1 |
| `fromSingleUri()` | 1（在构造函数中） | 调用 getter 之前为 0 |
| `listFiles()` | 1 + F + 2·D | **1** |
| 读取已列出子项的元数据 | 0 | 0 |
| `find(name)` | 1 + F + 2·D | **1** |
| `getChildCount()` | 每创建一个目录实例就立即统计一次 | 首次调用时 1 次，之后使用缓存 |

其中 `2·D` 这一项代价尤其高：每个被列出的子目录都要把自己的子项完整枚举一遍，只为了得到数量。

## API 概览

| 成员 | 说明 |
|---|---|
| `fromFile(context, file)` | 永不为 null |
| `fromSingleUri(context, uri)` | 永不为 null，懒加载 |
| `fromTreeUri(context, uri)` | 非 tree Uri 时为 null；一次查询 |
| `fromUri(context, uri)` | 根据 Uri 分派；不支持的 scheme 返回 null |
| `isDocumentUri(context, uri)`、`isValid(context, uri)` | `isValid` 从不抛出异常 |
| `getName()`、`getType()`、`length()`、`lastModified()` | provider 文档会缓存这些值 |
| `isDirectory()`、`isFile()`、`isVirtual()`、`canRead()`、`canWrite()` | |
| `listFiles()`、`getChildCount()`、`find(name)` | 各一次查询 |
| `createFile()`、`createDirectory()`、`renameTo()`、`delete()` | 用返回值代替异常 |
| `exists()` | 每次都查询 |
| `refresh()` | 丢弃缓存的元数据 |
| `getUri()`、`getParent()`、`getContext()` | |

## 测试

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

## 从旧版本迁移

- **删除 `DocumentsContractApi.kt` 和 `DocumentsContractApi19.kt`。** minSdk 为 24 时不再需要这层兼容代码，其逻辑已移至 internal 的 `DocumentQueries` 和 `DocumentFlags`。
- **`renameTo()` 不再修改调用它的实例。** 请使用返回的新实例。
- **`fromTreeUri()` 对非 tree Uri 返回 `null`**，而不是抛出异常；当 Uri 指向文件时返回 `SingleDocumentFile`。
- **`fromFile()` 和 `fromSingleUri()` 的返回类型改为非空。**
- **目录无法读取时，`listFiles()` 也会返回 `null`。** `RawDocumentFile` 现在会真正返回子项（之前总是返回 `null`）。
- **`SingleDocumentFile.isDirectory()` / `isFile()` 依据 MIME 类型判断**，不再是写死的值。
- **未知的名称和类型为 `""`**，而不是 `"."` 和 `"unknown"`。
- **`equals()` / `hashCode()` 基于 Uri 比较。**
- **移除了 `DocumentFile.LOG_TAG`**；日志使用 `DocumentFile` 标签。
- **`RawDocumentFile`** 会拒绝包含 `/` 的名称，重命名时不会覆盖已有文件，不会重复追加扩展名，删除时不会跟随符号链接。
- **接收缓存元数据的构造函数是 internal 的。** 公开的 `(parent, context, uri)` 构造函数仍然保留。

方法名（`getName()`、`isDirectory()` 等）保持不变，因此除上述几点外，现有调用代码可以照常编译。

## 局限性

- `TreeDocumentFile` 和 `SingleDocumentFile` 报告的是最近一次读取时的元数据；如果文档可能已经变化，请调用 `refresh()`。
- `getParent()` 反映的是该实例是如何被访问到的；由工厂方法创建的文档没有父节点。
- `TreeDocumentFile` 的 `getChildCount()` 会被缓存；通过同一个实例创建子项时缓存会自动失效，但在其他地方发生的修改需要调用 `refresh()`。
- 返回值不区分“provider 出错”和“未找到”；具体原因请查看日志。

## 许可证

基于 AndroidX `DocumentFile`，Copyright © The Android Open Source Project。

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

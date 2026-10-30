# document：使用指南

> [document](../document/README_CN.md) 模块文档 · [English](document_usage.md)

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

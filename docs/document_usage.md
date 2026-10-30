# document: Usage

> Part of the documentation of the [document](../document/README.md) module · [中文](document_usage_cn.md)

## Usage

All methods marked `@WorkerThread` may perform I/O. Call them off the main thread.

### Opening a tree chosen by the user

```kotlin
private val openTree = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { treeUri ->
    if (treeUri == null) return@registerForActivityResult
    // Keep access across restarts.
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

The children returned by `listFiles()` already carry their name, type, size, last modified
time and flags, so reading them costs no further queries.

### Creating, renaming and deleting

```kotlin
val notes = directory.createFile("text/plain", "notes")      // provider may append ".txt"
val images = directory.createDirectory("images")

val renamed = notes?.renameTo("todo.txt")   // use `renamed` from now on;
                                            // `notes` still points at the old Uri

if (images?.delete() != true) showError("Could not delete the folder")
```

### Single documents and files on disk

```kotlin
// From ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT. No I/O until a getter is called.
val document = DocumentFile.fromSingleUri(context, uri)

// A file or directory the app can already access.
val cache = DocumentFile.fromFile(context, context.cacheDir)

// Any of the above, chosen from the shape of the Uri.
val anything = DocumentFile.fromUri(context, uri)   // null for unsupported schemes
```

### Finding a child

```kotlin
val config = root.find("config.json")   // one query; only the match is built
```

## API overview

| Member | Notes |
|---|---|
| `fromFile(context, file)` | Never null |
| `fromSingleUri(context, uri)` | Never null, lazy |
| `fromTreeUri(context, uri)` | Null for non-tree Uris; one query |
| `fromUri(context, uri)` | Dispatches on the Uri; null for unsupported schemes |
| `isDocumentUri(context, uri)`, `isValid(context, uri)` | `isValid` never throws |
| `getName()`, `getType()`, `length()`, `lastModified()` | Cached for provider documents |
| `isDirectory()`, `isFile()`, `isVirtual()`, `canRead()`, `canWrite()` | |
| `listFiles()`, `getChildCount()`, `find(name)` | One query each |
| `createFile()`, `createDirectory()`, `renameTo()`, `delete()` | Return values instead of exceptions |
| `exists()` | Always queries |
| `refresh()` | Discards cached metadata |
| `getUri()`, `getParent()`, `getContext()` | |

## Migrating from the previous version

- **Delete `DocumentsContractApi.kt` and `DocumentsContractApi19.kt`.** With minSdk 24 the
  compatibility layer is unnecessary; its logic moved to the internal `DocumentQueries` and
  `DocumentFlags`.
- **`renameTo()` no longer modifies the receiver.** Use the returned instance.
- **`fromTreeUri()` returns `null` for non-tree Uris** instead of throwing, and returns a
  `SingleDocumentFile` when the Uri points to a file.
- **`fromFile()` and `fromSingleUri()` return non-null** types.
- **`listFiles()` returns `null` also when a directory can't be read.** `RawDocumentFile`
  now actually returns its children (it used to always return `null`).
- **`SingleDocumentFile.isDirectory()` / `isFile()` follow the MIME type** instead of being
  hard-coded.
- **Unknown names and types are `""`**, not `"."` and `"unknown"`.
- **`equals()` / `hashCode()` compare Uris.**
- **`DocumentFile.LOG_TAG` was removed**; logging uses the tag `DocumentFile`.
- **`RawDocumentFile`** rejects names containing `/`, doesn't overwrite on rename, doesn't
  duplicate extensions, and doesn't follow symbolic links when deleting.
- **The constructors that take cached metadata are internal.** The public
  `(parent, context, uri)` constructors remain.

Method names (`getName()`, `isDirectory()`, ...) are unchanged, so existing call sites
compile as before apart from the points above.

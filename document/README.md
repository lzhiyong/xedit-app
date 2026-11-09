# document

English | [中文](README_CN.md)

A faster, safer rewrite of AndroidX `DocumentFile`. One API for plain files and Storage Access Framework (SAF) documents:

| Class | Backed by | Made by |
| --- | --- | --- |
| `RawDocumentFile` | `java.io.File` | `DocumentFile.fromFile()` |
| `SingleDocumentFile` | One SAF document | `fromSingleUri()`, or a file child of a tree |
| `TreeDocumentFile` | A directory in a SAF tree | `fromTreeUri()`, or a directory child of a tree |

Compared with AndroidX:

- **One query to list a directory**: `listFiles()` reads every child with its name, type, size and modification time at once. AndroidX takes 1 + files + 2 × subdirectories.
- **No I/O in constructors**: metadata is read on first use (or comes from the listing) and cached until `refresh()`.
- **Storage state never crashes it**: a deleted document, a revoked permission or a failing provider give `null` / `false` / `0` instead of exceptions.
- **Immutable instances**: the Uri never changes; `renameTo()` returns a new instance.
- **Safe file operations**: names that would leave the directory are refused, renaming never overwrites, recursive deletes never follow symbolic links.
- `equals()` / `hashCode()` by Uri, for Map keys and `DiffUtil`.

## Adding it

```kotlin
// settings.gradle.kts
include(":document")
// build.gradle.kts
dependencies { implementation(project(":document")) }
```

Its only dependency is `androidx.annotation`.

## Use

Methods marked `@WorkerThread` may do I/O: not on the main thread.

```kotlin
// a tree the user picked
val root = DocumentFile.fromTreeUri(context, treeUri)          // null for a non-tree Uri; a SingleDocumentFile for a file
val entries = root?.listFiles().orEmpty()                       // children carry their metadata: no more queries
val config = root?.find("config.json")                          // one query, only the match built

// one document, a file on disk, any Uri
val doc = DocumentFile.fromSingleUri(context, uri)              // never null, lazy
val cache = DocumentFile.fromFile(context, context.cacheDir)
val any = DocumentFile.fromUri(context, uri)                    // by scheme; null when unsupported

// create, rename, delete: check the result
val notes = dir.createFile("text/plain", "notes")               // the provider may add ".txt"
val renamed = notes?.renameTo("todo.txt")                       // use renamed from now on; notes keeps the old Uri
if (dir.createDirectory("images")?.delete() != true) showError()

doc.refresh()                                                   // read the metadata again when it may have changed
```

What results mean:

| Case | Result |
| --- | --- |
| Deleted document, revoked permission, failing provider | `exists()` / `delete()` are `false`; `listFiles()` / `createFile()` / `renameTo()` are `null`; metadata is `""` / `0` |
| `listFiles()` on a non-directory or an unreadable one | `null` (nothing to show) |
| An empty directory | An empty list |
| `createFile()` on a `SingleDocumentFile` | `UnsupportedOperationException` |

Reasons for failures are logged under the `DocumentFile` tag.

## Migrating from AndroidX

Method names are the same, so most code compiles as it is. Watch for:

- `renameTo()` no longer changes the instance: use the one it returns.
- `fromTreeUri()` returns `null` for a non-tree Uri instead of throwing; `fromFile()` and `fromSingleUri()` return non-null types.
- `RawDocumentFile.listFiles()` now really returns children, and `null` for an unreadable directory.
- `SingleDocumentFile.isDirectory()` / `isFile()` go by the MIME type.
- Unknown names and types are `""`, no longer `"."` and `"unknown"`.
- Delete `DocumentsContractApi.kt` and `DocumentsContractApi19.kt`; `DocumentFile.LOG_TAG` is gone.

## Limits

- `TreeDocumentFile` and `SingleDocumentFile` report the metadata of their last read; call `refresh()` when the document may have changed. `exists()` queries every time.
- `getParent()` depends on how the instance was reached; documents from factory methods have no parent.
- `TreeDocumentFile.getChildCount()` is cached; creating children through the same instance clears it, changes made elsewhere need `refresh()`.
- Results do not tell "provider failed" from "not found"; see the log.

## Tests

```bash
./gradlew :document:testDebugUnitTest            # JVM (Robolectric): flags, the file system implementation, Uri dispatch
./gradlew :document:connectedDebugAndroidTest    # device: a fake DocumentsProvider on the cache directory
```

The device tests go through the real `DocumentsContract` and permission checks (which Robolectric cannot model), and the fake provider counts its queries, so "one query to list a directory" is tested too.

## License

Based on AndroidX `DocumentFile`, Copyright © The Android Open Source Project, Apache License 2.0.

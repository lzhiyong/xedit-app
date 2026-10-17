# DocumentFile

A faster, safer rewrite of AndroidX `DocumentFile` for apps that browse files through the
Storage Access Framework (SAF) or directly on disk. One API covers three kinds of documents:

| Class | Backed by | Created by |
|---|---|---|
| `RawDocumentFile` | a `java.io.File` | `DocumentFile.fromFile()` |
| `SingleDocumentFile` | one SAF document | `DocumentFile.fromSingleUri()`, or a file child of a tree |
| `TreeDocumentFile` | a directory in an SAF tree | `DocumentFile.fromTreeUri()`, or a directory child of a tree |

```
document
├── DocumentFile          public API and factory methods
├── RawDocumentFile       java.io.File implementation
├── SingleDocumentFile    single SAF document
├── TreeDocumentFile      directory inside an SAF tree
├── DocumentInfo          (internal) metadata snapshot read from one cursor row
├── DocumentQueries       (internal) provider calls that never throw
└── DocumentFlags         (internal) readable / writable / virtual rules
```

## Features

- **One query per directory listing.** `listFiles()` reads every child and its metadata in a
  single provider query, instead of one or two extra queries per child.
- **No I/O in constructors.** Metadata is read lazily on first access, or comes from the
  listing query, and is cached until `refresh()`.
- **Never crashes on storage state.** Deleted documents, revoked permissions and failing
  providers are reported as `null` / `false` / `0`, not as exceptions.
- **Immutable instances.** A document's Uri never changes; `renameTo()` returns a new instance.
- **`fromUri()` for any Uri**: `file://`, tree Uris and single-document Uris.
- **Safe file operations**: names that escape the directory are rejected, renames never
  overwrite, recursive delete never follows symbolic links.
- **Uri-based `equals()` / `hashCode()`**, so documents work as map keys and in `DiffUtil`.

## Requirements

- minSdk 24
- `androidx.annotation:annotation`

## Installation

Include the module and depend on it:

```kotlin
// settings.gradle.kts
include(":document")

// app/build.gradle.kts
dependencies {
    implementation(project(":document"))
}
```

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

## Behavior

### Metadata snapshots

`SingleDocumentFile` and `TreeDocumentFile` read their metadata once and cache it. If the
document may have changed, call `refresh()`:

```kotlin
document.refresh()          // re-reads metadata, and the child count for a directory
val size = document.length()
```

`exists()` always asks the provider. `RawDocumentFile` caches nothing.

### Return values instead of exceptions

| Situation | Result |
|---|---|
| Document deleted, permission revoked, provider failed | `exists()` / `delete()` → `false`; `listFiles()` / `createFile()` / `renameTo()` → `null`; metadata → `""` / `0` |
| `listFiles()` on something that isn't a directory | `null` |
| `listFiles()` on an empty directory | empty list |
| `createFile()` / `createDirectory()` on a `SingleDocumentFile` | `UnsupportedOperationException` |

So `null` from `listFiles()` means "nothing to show", while an empty list means "the
directory is empty". Failures are logged under the tag `DocumentFile`.

### Immutability

The Uri of an instance never changes. `renameTo()` returns a new instance and leaves the
receiver untouched. The receiver usually no longer `exists()` afterwards.

### `fromTreeUri()`

- Accepts both the tree Uri from `ACTION_OPEN_DOCUMENT_TREE` and document Uris inside a tree.
- Returns `null` if the Uri isn't a tree Uri.
- Returns a `SingleDocumentFile` if the Uri points to a file, a `TreeDocumentFile` otherwise.
- Performs exactly one query.

### Files on disk

`RawDocumentFile.createFile()` appends the extension registered for the MIME type, unless
the name already ends with it; with no registered extension the name is used unchanged.
Names containing `/`, as well as `.` and `..`, are rejected by `createFile()`,
`createDirectory()` and `renameTo()`. `renameTo()` fails instead of overwriting an existing
file. `delete()` removes directories recursively but deletes symbolic links themselves,
never their targets.

## Performance

Number of provider queries, for a directory with **F** files and **D** subdirectories:

| Operation | Previous version | Now |
|---|---|---|
| `fromTreeUri()` | 2 | 1 |
| `fromSingleUri()` | 1 (in the constructor) | 0 until a getter is called |
| `listFiles()` | 1 + F + 2·D | **1** |
| Metadata of listed children | 0 | 0 |
| `find(name)` | 1 + F + 2·D | **1** |
| `getChildCount()` | counted eagerly for every directory created | 1 on first call, then cached |

The `2·D` term was particularly expensive: every listed subdirectory enumerated its own
children just to count them.

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

## Testing

```
src/
├── test/                          JVM tests (Robolectric), no device needed
│   ├── DocumentFlagsTest          readable / writable / virtual rules
│   ├── RawDocumentFileTest        file system implementation
│   ├── DocumentFileFactoryTest    Uri dispatch, behavior with unreachable providers
│   └── TempDirectory              temporary directory helper
└── androidTest/                   instrumented tests against a real DocumentsProvider
    ├── AndroidManifest.xml        registers FakeDocumentsProvider
    ├── FakeDocumentsProvider      provider backed by a cache directory; counts queries
    ├── FakeProviderFixture        fresh root directory per test
    ├── TreeDocumentFileTest
    └── SingleDocumentFileTest
```

The instrumented tests run the real framework code paths (`DocumentsContract`,
`DocumentsProvider`, permission checks), which Robolectric doesn't fully emulate. The fake
provider counts its queries, so the tests also verify the query counts in the table above.

The tests use `kotlin.test` for annotations and assertions. The only JUnit API they reference
directly is `@RunWith`, which selects the Robolectric or AndroidX runner; JUnit 4 itself comes
in through those libraries.

Add to the module's `build.gradle.kts` (use the latest versions available):

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

If `kotlin.test.Test` or `BeforeTest` can't be resolved, the Kotlin Gradle plugin didn't pick
the JUnit 4 variant automatically; use `kotlin("test-junit")` instead of `kotlin("test")`.

Run them with:

```bash
./gradlew :document:testDebugUnitTest            # JVM tests
./gradlew :document:connectedDebugAndroidTest    # on a device or emulator
```

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

## Limitations

- `TreeDocumentFile` and `SingleDocumentFile` report metadata as of the last read; call
  `refresh()` if the document may have changed.
- `getParent()` reflects how an instance was reached; documents created by a factory method
  have no parent.
- `getChildCount()` on a `TreeDocumentFile` is cached; creating children through the same
  instance invalidates the cache, changes made elsewhere require `refresh()`.
- Provider failures and "not found" are not distinguished in return values; check the log
  for the cause.

## License

Based on AndroidX `DocumentFile`, Copyright © The Android Open Source Project.

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

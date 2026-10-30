# document: Behavior and performance

> Part of the documentation of the [document](../document/README.md) module · [中文](document_behavior_cn.md)

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

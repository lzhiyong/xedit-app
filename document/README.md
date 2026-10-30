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

## Documentation

The details are in the documents of this module:

- [Usage](../docs/document_usage.md): Usage, API overview, Migrating from the previous version
- [Behavior and performance](../docs/document_behavior.md): Behavior, Performance
- [Testing](../docs/document_testing.md): Testing

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

# TreeView

A tree view for Android built on a **single `RecyclerView`**. Rows have a fixed height, the
content width follows expansion and collapse, and the list scrolls both vertically and
horizontally without nesting scroll containers. Nodes are generic: the same library displays
file systems, organization charts, comment threads or any other hierarchy.

```
treeview
├── TreeNode<T>              the data model: value, parent/children, expansion and load state
├── TreeAdapter<T, VH>       flattens the tree into rows; expand, collapse, insert, remove, load
├── TreeLayoutManager        fixed-height rows, two-axis scrolling, content width, axis lock
├── RowWidthCalculator<T>    optional: row width without measuring views (exact scroll range)
├── ChildLoader<T>           optional: loads children lazily on first expansion
├── ContentWidthProvider     link between adapter and layout manager (implemented by TreeAdapter)
└── OnNodeClickListener<T> / OnNodeLongClickListener<T>
```

## Features

- Vertical **and** horizontal scrolling inside one RecyclerView, with an optional
  **per-gesture axis lock** (a drag and its fling move along one axis only).
- **Exact horizontal scroll range** that grows and shrinks as nodes are expanded and collapsed.
- **Fine-grained change notifications**: expanding or collapsing inserts or removes exactly
  the affected rows, so item animations work and untouched rows are kept.
- **Lazy loading** of children with loading / failed states, retry and refresh.
- **Single-child chain expansion** (`x › github › module › treeview` opens in one tap).
- **Click and long-click** listeners per row.
- Generic nodes, Kotlin DSL for building trees, Java-friendly API (`@JvmOverloads`,
  `fun interface`s), Kotlin explicit API mode.

## Documentation

The details are in the documents of this module:

- [Usage](../docs/treeview_usage.md): Usage, API overview
- [Design and internals](../docs/treeview_architecture.md): Design
- [Testing](../docs/treeview_testing.md): Tests

## Requirements

- minSdk 21
- `androidx.recyclerview:recyclerview` 1.4.0 (exposed as an `api` dependency)

## Installation

Include the module in your build and depend on it:

```kotlin
// settings.gradle.kts
include(":treeview")

// app/build.gradle.kts
dependencies {
    implementation(project(":treeview"))
}
```

## Limitations

- The RecyclerView must have an exact size; auto-measure (`wrap_content`) isn't supported.
- All rows share one height (`rowHeight`, including decoration insets and margins). If it is
  `<= 0`, the first item is measured once.
- Right-to-left layouts aren't supported; rows always start at the left edge.
- Predictive item animations aren't supported; regular add, remove and move animations run.
- `expandAll()` expands only nodes whose children are loaded; it doesn't trigger lazy loading.

## License

```
Copyright © 2026 Github Lzhiyong

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

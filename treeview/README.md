# treeview

English | [中文](README_CN.md) · [Design](DESIGN.md)

A tree view for Android on **a single `RecyclerView`**. Rows have a fixed height and scroll both vertically and horizontally, with no nested scrolling containers. Nodes are generic: file systems, org charts, comment threads or any other hierarchy.

- Two-way scrolling in one RecyclerView, optionally locked to one axis per gesture
- An exact horizontal scroll range that grows and shrinks as nodes expand and collapse
- Expanding and collapsing insert or remove only the affected rows, so item animations work
- Lazy loading of children, with loading and failed states, retry and refresh
- Single-child chains expand in one tap (`x › github › module`)
- Click and long-click listeners; a Kotlin DSL for building trees; Java-friendly

## Adding it

```kotlin
// settings.gradle.kts
include(":treeview")
// build.gradle.kts
dependencies { implementation(project(":treeview")) }   // brings recyclerview 1.4.0 as an api dependency
```

## Use

A file browser loading directories lazily from disk.

**1. The adapter**: subclass `TreeAdapter`, implement `onCreateNodeViewHolder` and `onBindNode`. Do not set click listeners on items yourself; the adapter does.

```kotlin
class FileAdapter(private val indentPx: Int) : TreeAdapter<FileItem, FileAdapter.VH>() {
    override fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun onBindNode(holder: VH, node: TreeNode<FileItem>, payloads: List<Any>) {
        // when only the expansion changed the payload is PAYLOAD_EXPANSION_CHANGED: update the arrow only
        val indicatorOnly = payloads.isNotEmpty() && payloads.all { it === PAYLOAD_EXPANSION_CHANGED }
        if (!indicatorOnly) {
            holder.itemView.setPaddingRelative(node.depth * indentPx, 0, 0, 0)
            holder.name.text = node.value.name
        }
        holder.arrow.text = when {
            node.loadState == TreeNode.LoadState.LOADING -> "…"
            !isExpandable(node) -> ""
            node.isExpanded -> "▼"
            else -> "▶"
        }
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) { /* arrow, name */ }
}
```

**2. Lazy loading**: the `ChildLoader` is called when a node is first expanded, and may answer on any thread.

```kotlin
class FileLoader(private val executor: Executor) : ChildLoader<FileItem> {
    override fun loadChildren(node: TreeNode<FileItem>, callback: ChildLoadCallback<FileItem>) =
        executor.execute {
            try {
                val files = node.value.file.listFiles() ?: throw IOException("Cannot list")
                callback.onLoaded(files.sortedBy { it.name }.map {
                    TreeNode(FileItem(it), childrenLoaded = !it.isDirectory)   // directories load lazily too
                })
            } catch (e: Exception) { callback.onFailed(e) }
        }
}
```

**3. Putting it together**: the RecyclerView needs a definite size (`match_parent` or a fixed value).

```kotlin
val adapter = FileAdapter(indentPx).apply {
    // row widths without inflating views: an exact horizontal scroll range
    rowWidthCalculator = RowWidthCalculator { node ->
        node.depth * indentPx + fixedPartsPx + ceil(labelPaint.measureText(node.value.name)).toInt()
    }
    childLoader = FileLoader(Executors.newSingleThreadExecutor())
    autoExpandSingleChild = true
    onNodeClickListener = OnNodeClickListener { node, _, _ -> if (!node.value.isDirectory) open(node.value.file) }
    setRoots(listOf(TreeNode(FileItem(rootDir), isExpanded = true, childrenLoaded = false)))
}
recyclerView.layoutManager = TreeLayoutManager(rowHeight = rowHeightPx)
recyclerView.adapter = adapter
```

**4. Changing the tree**: once shown, always through the adapter, so it can tell the RecyclerView.

```kotlin
adapter.expand(node); adapter.collapse(node); adapter.toggleAt(position)
adapter.insertNode(parent, TreeNode(item), index = 0)
adapter.removeNode(node)                // the whole subtree
adapter.reloadChildren(dirNode)         // drop the children and load again
node.value = renamed; adapter.notifyNodeChanged(node)
recyclerView.scrollToPosition(adapter.reveal(deepNode))   // expand its ancestors and scroll to it
```

A static tree can use the DSL:

```kotlin
val root = treeNode(Dept("Headquarters"), isExpanded = true) {
    child(Dept("R&D")) { child(Dept("Mobile")); child(Dept("Backend")) }
    child(Dept("Marketing"))
}
```

## Settings

| Property | What it does |
| --- | --- |
| `TreeAdapter.rowWidthCalculator` | Row widths for an exact horizontal scroll range; without it, the widths of measured rows |
| `TreeAdapter.childLoader` | Lazy loading of children |
| `TreeAdapter.autoExpandSingleChild` | Single-child chains expand in one go |
| `TreeAdapter.toggleOnClick` | A click toggles expansion (on by default) |
| `onNodeClickListener`, `onNodeLongClickListener`, `onNodeExpansionChanged`, `onChildrenLoadFailed` | Events |
| `isExpandable(node)` (open) | By default "has children, or they are not loaded yet"; for empty directories that expand too: `super.isExpandable(node) \|\| node.value.isDirectory` |
| `TreeLayoutManager.rowHeight` | Row height, decorations and margins included; `<= 0` measures the first item |
| `TreeLayoutManager.stretchRowWidth` | Rows stretched to the content width, so backgrounds and dividers span the scrollable area (on by default) |
| `TreeLayoutManager.isAxisLockEnabled` | A drag and its fling move along one axis only |

## Limits

- The RecyclerView needs a definite size; `wrap_content` is not supported.
- All rows have the same height.
- No right-to-left layout.
- No predictive item animations (the usual add, remove and move animations work).
- `expandAll()` expands only nodes whose children are loaded; it does not trigger lazy loading.
- Every method except `ChildLoadCallback` must be called on the main thread.

## Tests

`./gradlew :treeview:testDebugUnitTest`: `TreeNodeTest` covers the order of children, parents and depth, ancestors and descendants, leaves and "known empty". The adapter and layout manager depend on RecyclerView and are not covered.

## License

Copyright © 2026 Github Lzhiyong, Apache License 2.0.

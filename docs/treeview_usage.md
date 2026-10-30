# treeview: Usage

> Part of the documentation of the [treeview](../treeview/README.md) module · [中文](treeview_usage_cn.md)

## Usage

The example below shows a file browser that loads directories lazily from disk.

### 1. Model and row layout

```kotlin
data class FileItem(
    val file: File,
    val isDirectory: Boolean = file.isDirectory,
) {
    val name: String get() = file.name
}
```

```xml
<!-- res/layout/item_file.xml: a fixed-height row; the indentation is set in code. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="40dp"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingEnd="12dp">

    <TextView
        android:id="@+id/arrow"
        android:layout_width="24dp"
        android:layout_height="wrap_content"
        android:gravity="center" />

    <TextView
        android:id="@+id/icon"
        android:layout_width="24dp"
        android:layout_height="wrap_content"
        android:gravity="center" />

    <TextView
        android:id="@+id/name"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:maxLines="1"
        android:textSize="15sp" />
</LinearLayout>
```

### 2. Adapter

Subclass `TreeAdapter`, implement `onCreateNodeViewHolder` and `onBindNode`. Don't set click
listeners on the item view; the adapter installs them.

```kotlin
class FileAdapter(
    private val indentPx: Int,
) : TreeAdapter<FileItem, FileAdapter.ViewHolder>() {

    override fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun onBindNode(holder: ViewHolder, node: TreeNode<FileItem>, payloads: List<Any>) {
        // PAYLOAD_EXPANSION_CHANGED: only the indicator (expansion or load state) changed.
        val indicatorOnly = payloads.isNotEmpty() &&
            payloads.all { it === TreeAdapter.PAYLOAD_EXPANSION_CHANGED }
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
        holder.icon.text = if (node.value.isDirectory) "📁" else "📄"
        holder.icon.alpha = if (node.isKnownEmpty && node.value.isDirectory) 0.4f else 1f
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val arrow: TextView = view.findViewById(R.id.arrow)
        val icon: TextView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
    }
}
```

### 3. Lazy loading

A `ChildLoader` is called the first time a node with unknown children is expanded. It may
report back from any thread.

```kotlin
class FileSystemLoader(private val executor: Executor) : ChildLoader<FileItem> {

    override fun loadChildren(node: TreeNode<FileItem>, callback: ChildLoadCallback<FileItem>) {
        executor.execute {
            try {
                val files = node.value.file.listFiles()
                    ?: throw IOException("Cannot list ${node.value.file}")
                val children = files
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    // Directories are lazy themselves; files have no children to load.
                    .map { TreeNode(FileItem(it), childrenLoaded = !it.isDirectory) }
                callback.onLoaded(children)
            } catch (e: Exception) {
                callback.onFailed(e)
            }
        }
    }
}
```

### 4. Wiring it up

```xml
<!-- The RecyclerView needs an exact size: match_parent or a fixed dimension. -->
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/tree"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:scrollbars="vertical|horizontal" />
```

```kotlin
val density = resources.displayMetrics.density
val indentPx = (20 * density).toInt()
val rowHeightPx = (40 * density).toInt()
val fixedPartsPx = ((24 + 24 + 8 + 12) * density).toInt() // arrow + icon + margin + paddingEnd
val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
}

val adapter = FileAdapter(indentPx).apply {
    // Exact horizontal scroll range, computed without inflating views.
    rowWidthCalculator = RowWidthCalculator { node ->
        node.depth * indentPx + fixedPartsPx + ceil(labelPaint.measureText(node.value.name)).toInt()
    }
    childLoader = FileSystemLoader(Executors.newSingleThreadExecutor())
    autoExpandSingleChild = true

    onNodeClickListener = OnNodeClickListener<FileItem> { node, _, _ ->
        if (!node.value.isDirectory) openFile(node.value.file)
        // Directories toggle automatically afterwards (toggleOnClick = true).
    }
    onNodeLongClickListener = OnNodeLongClickListener<FileItem> { node, _, view ->
        showContextMenu(node, view)
        true
    }
    onChildrenLoadFailed = { node, _ ->
        Toast.makeText(recyclerView.context, "Cannot open ${node.value.name}", Toast.LENGTH_SHORT).show()
    }

    // An expanded lazy root starts loading as soon as it is set.
    setRoots(listOf(TreeNode(FileItem(rootDirectory), isExpanded = true, childrenLoaded = false)))
}

recyclerView.layoutManager = TreeLayoutManager(rowHeight = rowHeightPx)
recyclerView.adapter = adapter
```

### 5. Changing the tree

Once a tree is displayed, change it through the adapter so RecyclerView is notified:

```kotlin
adapter.expand(node)                    // also: collapse, toggle, toggleAt(position)
adapter.insertNode(parent, TreeNode(FileItem(newDir)), index = 0)
adapter.removeNode(node)                // removes the whole subtree
adapter.reloadChildren(directoryNode)   // drop children and load them again
node.value = renamedItem
adapter.notifyNodeChanged(node)         // rebind and recompute its row width

val position = adapter.reveal(deepNode) // expand all ancestors
recyclerView.scrollToPosition(position)
```

Static trees can be built with the DSL and need no loader:

```kotlin
val root = treeNode(Department("Headquarters"), isExpanded = true) {
    child(Department("R&D")) {
        child(Department("Mobile"))
        child(Department("Backend"))
    }
    child(Department("Marketing"))
}
```

## API overview

| Type | Key members |
|---|---|
| `TreeNode<T>` | `value`, `children`, `parent`, `depth`, `isExpanded`, `loadState`, `isLeaf`, `isKnownEmpty`, `root`, `addChild`, `insertChild`, `removeChild`, `child { }`, `ancestors()`, `descendants()`, `isDescendantOf()`, top-level `treeNode { }` |
| `TreeAdapter<T, VH>` | `onCreateNodeViewHolder`, `onBindNode`, `isExpandable`, `canAutoExpand` (protected), `setRoots`, `expand`, `collapse`, `toggle`, `toggleAt`, `expandAll`, `collapseAll`, `reveal`, `insertNode`, `removeNode`, `reloadChildren`, `notifyNodeChanged`, `invalidateRowWidths`, `getNode`, `getNodeOrNull`, `positionOf`, `contains`, `isDisplayed`, `rootNodes`, `maxRowWidth`, `PAYLOAD_EXPANSION_CHANGED` |
| `TreeAdapter` properties | `rowWidthCalculator`, `childLoader`, `autoExpandSingleChild`, `toggleOnClick`, `onNodeClickListener`, `onNodeLongClickListener`, `onNodeExpansionChanged`, `onChildrenLoadFailed` |
| `TreeLayoutManager` | `rowHeight`, `contentWidthProvider`, `stretchRowWidth`, `isAxisLockEnabled`, `contentWidth`, `invalidateContentWidth`, `findFirstVisibleItemPosition`, `findLastVisibleItemPosition`, `scrollToPositionWithOffset` |
| Interfaces | `RowWidthCalculator<T>`, `ChildLoader<T>`, `ChildLoadCallback<T>`, `ContentWidthProvider`, `OnNodeClickListener<T>`, `OnNodeLongClickListener<T>` |

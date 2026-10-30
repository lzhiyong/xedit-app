# treeview：使用指南

> [treeview](../treeview/README_CN.md) 模块文档 · [English](treeview_usage.md)

## 使用方法

下面的示例展示了一个从磁盘懒加载目录的文件浏览器。

### 1. 数据模型与行布局

```kotlin
data class FileItem(
    val file: File,
    val isDirectory: Boolean = file.isDirectory,
) {
    val name: String get() = file.name
}
```

```xml
<!-- res/layout/item_file.xml：固定高度的行；缩进在代码中设置。 -->
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

### 2. 适配器

继承 `TreeAdapter`，实现 `onCreateNodeViewHolder` 和 `onBindNode`。不要在 item 视图上设置点击监听器，适配器会自行安装。

```kotlin
class FileAdapter(
    private val indentPx: Int,
) : TreeAdapter<FileItem, FileAdapter.ViewHolder>() {

    override fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun onBindNode(holder: ViewHolder, node: TreeNode<FileItem>, payloads: List<Any>) {
        // PAYLOAD_EXPANSION_CHANGED：仅指示器（展开状态或加载状态）发生了变化。
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

### 3. 懒加载

当一个子节点未知的节点首次被展开时，会调用 `ChildLoader`。它可以在任意线程中回传结果。

```kotlin
class FileSystemLoader(private val executor: Executor) : ChildLoader<FileItem> {

    override fun loadChildren(node: TreeNode<FileItem>, callback: ChildLoadCallback<FileItem>) {
        executor.execute {
            try {
                val files = node.value.file.listFiles()
                    ?: throw IOException("Cannot list ${node.value.file}")
                val children = files
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    // 目录本身也是懒加载的；文件没有需要加载的子节点。
                    .map { TreeNode(FileItem(it), childrenLoaded = !it.isDirectory) }
                callback.onLoaded(children)
            } catch (e: Exception) {
                callback.onFailed(e)
            }
        }
    }
}
```

### 4. 组装

```xml
<!-- RecyclerView 需要确定的尺寸：match_parent 或固定数值。 -->
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
val fixedPartsPx = ((24 + 24 + 8 + 12) * density).toInt() // 箭头 + 图标 + 外边距 + paddingEnd
val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
}

val adapter = FileAdapter(indentPx).apply {
    // 精确的水平滚动范围，无需 inflate 视图即可计算。
    rowWidthCalculator = RowWidthCalculator { node ->
        node.depth * indentPx + fixedPartsPx + ceil(labelPaint.measureText(node.value.name)).toInt()
    }
    childLoader = FileSystemLoader(Executors.newSingleThreadExecutor())
    autoExpandSingleChild = true

    onNodeClickListener = OnNodeClickListener<FileItem> { node, _, _ ->
        if (!node.value.isDirectory) openFile(node.value.file)
        // 之后目录会自动切换展开状态（toggleOnClick = true）。
    }
    onNodeLongClickListener = OnNodeLongClickListener<FileItem> { node, _, view ->
        showContextMenu(node, view)
        true
    }
    onChildrenLoadFailed = { node, _ ->
        Toast.makeText(recyclerView.context, "Cannot open ${node.value.name}", Toast.LENGTH_SHORT).show()
    }

    // 已展开的懒加载根节点一经设置就会立即开始加载。
    setRoots(listOf(TreeNode(FileItem(rootDirectory), isExpanded = true, childrenLoaded = false)))
}

recyclerView.layoutManager = TreeLayoutManager(rowHeight = rowHeightPx)
recyclerView.adapter = adapter
```

### 5. 修改树

树显示出来之后，应通过适配器进行修改，以便通知 RecyclerView：

```kotlin
adapter.expand(node)                    // 另有：collapse、toggle、toggleAt(position)
adapter.insertNode(parent, TreeNode(FileItem(newDir)), index = 0)
adapter.removeNode(node)                // 删除整棵子树
adapter.reloadChildren(directoryNode)   // 丢弃子节点并重新加载
node.value = renamedItem
adapter.notifyNodeChanged(node)         // 重新绑定并重新计算其行宽

val position = adapter.reveal(deepNode) // 展开所有祖先节点
recyclerView.scrollToPosition(position)
```

静态树可以用 DSL 构建，不需要加载器：

```kotlin
val root = treeNode(Department("Headquarters"), isExpanded = true) {
    child(Department("R&D")) {
        child(Department("Mobile"))
        child(Department("Backend"))
    }
    child(Department("Marketing"))
}
```

## API 概览

| 类型 | 主要成员 |
|---|---|
| `TreeNode<T>` | `value`、`children`、`parent`、`depth`、`isExpanded`、`loadState`、`isLeaf`、`isKnownEmpty`、`root`、`addChild`、`insertChild`、`removeChild`、`child { }`、`ancestors()`、`descendants()`、`isDescendantOf()`、顶层函数 `treeNode { }` |
| `TreeAdapter<T, VH>` | `onCreateNodeViewHolder`、`onBindNode`、`isExpandable`、`canAutoExpand`（protected）、`setRoots`、`expand`、`collapse`、`toggle`、`toggleAt`、`expandAll`、`collapseAll`、`reveal`、`insertNode`、`removeNode`、`reloadChildren`、`notifyNodeChanged`、`invalidateRowWidths`、`getNode`、`getNodeOrNull`、`positionOf`、`contains`、`isDisplayed`、`rootNodes`、`maxRowWidth`、`PAYLOAD_EXPANSION_CHANGED` |
| `TreeAdapter` 属性 | `rowWidthCalculator`、`childLoader`、`autoExpandSingleChild`、`toggleOnClick`、`onNodeClickListener`、`onNodeLongClickListener`、`onNodeExpansionChanged`、`onChildrenLoadFailed` |
| `TreeLayoutManager` | `rowHeight`、`contentWidthProvider`、`stretchRowWidth`、`isAxisLockEnabled`、`contentWidth`、`invalidateContentWidth`、`findFirstVisibleItemPosition`、`findLastVisibleItemPosition`、`scrollToPositionWithOffset` |
| 接口 | `RowWidthCalculator<T>`、`ChildLoader<T>`、`ChildLoadCallback<T>`、`ContentWidthProvider`、`OnNodeClickListener<T>`、`OnNodeLongClickListener<T>` |

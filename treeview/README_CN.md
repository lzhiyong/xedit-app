# treeview

[English](README.md) | 中文 · [设计](DESIGN_CN.md)

基于**单个 `RecyclerView`** 的 Android 树形视图。行高固定，可同时垂直和水平滚动，不需要嵌套滚动容器。节点是泛型的，可以展示文件系统、组织架构、评论楼层等任何层级结构。

- 同一个 RecyclerView 内双向滚动，可选按手势锁定滚动轴
- 精确的水平滚动范围，随展开折叠增减
- 展开折叠只插入或删除受影响的行，条目动画正常
- 子节点懒加载，带加载中、加载失败、重试和刷新
- 单子节点链一次展开（`x › github › module` 一次点击全部展开）
- 点击、长按监听；构建树的 Kotlin DSL；对 Java 友好

## 接入

```kotlin
// settings.gradle.kts
include(":treeview")
// build.gradle.kts
dependencies { implementation(project(":treeview")) }   // 以 api 形式带上 recyclerview 1.4.0
```

## 使用

以从磁盘懒加载目录的文件浏览器为例。

**1. 适配器**：继承 `TreeAdapter`，实现 `onCreateNodeViewHolder` 和 `onBindNode`。不要自己给 item 设置点击监听器，适配器会安装。

```kotlin
class FileAdapter(private val indentPx: Int) : TreeAdapter<FileItem, FileAdapter.VH>() {
    override fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun onBindNode(holder: VH, node: TreeNode<FileItem>, payloads: List<Any>) {
        // 只是展开状态变了时，payload 为 PAYLOAD_EXPANSION_CHANGED，只需更新箭头
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
    class VH(view: View) : RecyclerView.ViewHolder(view) { /* arrow、name */ }
}
```

**2. 懒加载**：节点首次展开时调用 `ChildLoader`，可以在任意线程回传结果。

```kotlin
class FileLoader(private val executor: Executor) : ChildLoader<FileItem> {
    override fun loadChildren(node: TreeNode<FileItem>, callback: ChildLoadCallback<FileItem>) =
        executor.execute {
            try {
                val files = node.value.file.listFiles() ?: throw IOException("Cannot list")
                callback.onLoaded(files.sortedBy { it.name }.map {
                    TreeNode(FileItem(it), childrenLoaded = !it.isDirectory)   // 目录也懒加载
                })
            } catch (e: Exception) { callback.onFailed(e) }
        }
}
```

**3. 组装**：RecyclerView 要有确定尺寸（`match_parent` 或固定值）。

```kotlin
val adapter = FileAdapter(indentPx).apply {
    // 不 inflate 视图就能算出行宽，水平滚动范围才精确
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

**4. 修改树**：显示之后一律通过适配器修改，它才能通知 RecyclerView。

```kotlin
adapter.expand(node); adapter.collapse(node); adapter.toggleAt(position)
adapter.insertNode(parent, TreeNode(item), index = 0)
adapter.removeNode(node)                // 删除整棵子树
adapter.reloadChildren(dirNode)         // 丢弃子节点并重新加载
node.value = renamed; adapter.notifyNodeChanged(node)
recyclerView.scrollToPosition(adapter.reveal(deepNode))   // 展开所有祖先并滚到它
```

静态树可以用 DSL：

```kotlin
val root = treeNode(Dept("总部"), isExpanded = true) {
    child(Dept("研发")) { child(Dept("移动端")); child(Dept("后端")) }
    child(Dept("市场"))
}
```

## 可配置项

| 属性 | 作用 |
| --- | --- |
| `TreeAdapter.rowWidthCalculator` | 计算行宽，用于精确的水平滚动范围；不设置时按已测量行的宽度 |
| `TreeAdapter.childLoader` | 懒加载子节点 |
| `TreeAdapter.autoExpandSingleChild` | 单子节点链一次展开 |
| `TreeAdapter.toggleOnClick` | 点击时切换展开（默认开） |
| `onNodeClickListener`、`onNodeLongClickListener`、`onNodeExpansionChanged`、`onChildrenLoadFailed` | 事件 |
| `isExpandable(node)`（可重写） | 默认「有子节点，或子节点未加载」；如让空目录也可展开：`super.isExpandable(node) \|\| node.value.isDirectory` |
| `TreeLayoutManager.rowHeight` | 行高，含 decoration 和外边距；`<= 0` 时测量第一个条目 |
| `TreeLayoutManager.stretchRowWidth` | 行拉伸到内容宽度，背景和分割线横跨整个滚动区域（默认开） |
| `TreeLayoutManager.isAxisLockEnabled` | 一次拖动及其惯性只沿一个方向 |

## 限制

- RecyclerView 必须有确定尺寸，不支持 `wrap_content`。
- 所有行同一高度。
- 不支持从右到左布局。
- 不支持预测性条目动画（常规的添加、删除、移动动画正常）。
- `expandAll()` 只展开子节点已加载的节点，不触发懒加载。
- 除 `ChildLoadCallback` 外，所有方法都必须在主线程调用。

## 测试

`./gradlew :treeview:testDebugUnitTest`：`TreeNodeTest` 覆盖子节点顺序、父节点与深度、祖先与后代、叶子与「已知为空」。适配器和布局管理器依赖 RecyclerView，不在测试范围内。

## 许可证

Copyright © 2026 Github Lzhiyong，Apache License 2.0。

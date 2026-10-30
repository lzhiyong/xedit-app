# TreeView

一个基于**单个 `RecyclerView`** 实现的 Android 树形视图。行高固定，内容宽度随节点的展开与折叠而变化，列表可同时进行垂直和水平滚动，无需嵌套滚动容器。节点是泛型的：同一个库可以用来展示文件系统、组织架构图、评论楼层或任何其他层级结构。

```
treeview
├── TreeNode<T>              数据模型：值、父/子节点、展开状态与加载状态
├── TreeAdapter<T, VH>       将树扁平化为行；负责展开、折叠、插入、删除、加载
├── TreeLayoutManager        固定行高、双向滚动、内容宽度、滚动轴锁定
├── RowWidthCalculator<T>    可选：无需测量视图即可计算行宽（精确的滚动范围）
├── ChildLoader<T>           可选：在首次展开时懒加载子节点
├── ContentWidthProvider     适配器与布局管理器之间的桥梁（由 TreeAdapter 实现）
└── OnNodeClickListener<T> / OnNodeLongClickListener<T>
```

## 特性

- 在同一个 RecyclerView 内同时支持垂直**和**水平滚动，并可选开启**按手势锁定滚动轴**（一次拖动及其后续惯性滑动只沿一个方向移动）。
- **精确的水平滚动范围**，会随节点的展开与折叠而增大或缩小。
- **细粒度的变更通知**：展开或折叠时只插入或删除受影响的行，因此条目动画可以正常工作，未受影响的行也会被保留。
- 子节点**懒加载**，支持加载中 / 加载失败状态、重试与刷新。
- **单子节点链式展开**（`x › github › module › treeview` 一次点击即可全部展开）。
- 每一行都支持**点击和长按**监听。
- 泛型节点、用于构建树的 Kotlin DSL、对 Java 友好的 API（`@JvmOverloads`、`fun interface`），并启用了 Kotlin 显式 API 模式。

## 文档

详细内容见本模块的文档：

- [使用指南](../docs/treeview_usage_cn.md)：使用方法、API 概览
- [设计与原理](../docs/treeview_architecture_cn.md)：设计
- [测试](../docs/treeview_testing_cn.md)：测试

## 环境要求

- minSdk 21
- `androidx.recyclerview:recyclerview` 1.4.0（以 `api` 依赖形式暴露）

## 安装

将该模块加入构建并添加依赖：

```kotlin
// settings.gradle.kts
include(":treeview")

// app/build.gradle.kts
dependencies {
    implementation(project(":treeview"))
}
```

## 局限性

- RecyclerView 必须具有确定的尺寸；不支持自动测量（`wrap_content`）。
- 所有行共用同一个高度（`rowHeight`，包含 decoration 的 inset 和外边距）。如果它 `<= 0`，则会对第一个条目测量一次。
- 不支持从右到左（RTL）布局；行总是从左边缘开始。
- 不支持预测性（predictive）条目动画；常规的添加、删除和移动动画可以正常运行。
- `expandAll()` 只展开子节点已加载的节点；它不会触发懒加载。

## 许可证

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

# treeview：测试

> [treeview](../treeview/README_CN.md) 模块文档 · [English](treeview_testing.md)

`TreeNodeTest`，在 JVM 上运行（`./gradlew :treeview:testDebugUnitTest`）。覆盖内容：子节点的顺序；父节点与深度（包括把子树移到别处之后）；祖先与后代；`addChild` 返回调用它的那个节点；叶子节点，以及"已知为空"的判断。依赖 `RecyclerView` 的部分（适配器、布局管理器）不在测试范围内。

# treeview: Testing

> Part of the documentation of the [treeview](../treeview/README.md) module · [中文](treeview_testing_cn.md)

`TreeNodeTest`, on the JVM (`./gradlew :treeview:testDebugUnitTest`): children in order,
parents and depths (also of a subtree moved elsewhere), ancestors and descendants, `addChild`
returning the node it is called on, leaves and what is known to be empty. What needs a
`RecyclerView` (the adapter, the layout manager) is not covered.

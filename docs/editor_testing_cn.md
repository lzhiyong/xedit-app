# editor：测试

> [editor](../editor/README_CN.md) 模块文档 · [English](editor_testing.md)

```bash
./gradlew :editor:testDebugUnitTest          # 电脑上运行
./gradlew :editor:connectedDebugAndroidTest  # 需要设备或模拟器
```

构建脚本需要 `useJUnitPlatform()` 和 `junit-platform-launcher`，详见 `tests/README.md`。

| 范围 | 测试类 |
|---|---|
| 模型 | `EditorDocumentTest` `EditHistoryTest` `UndoManagerTest` `SearchResultsTest` |
| 布局 | `UnwrappedLayoutTest` `WordwrapLayoutTest` `FoldedLayoutTest` `TabStopsTest` `LineBreaksTest` `LayoutCoordinatorTest` |
| 代码结构 | `StructureProvidersTest` `FoldingModelTest` |
| 输入 | `InputComposerTest` `InputMethodBridgeTest` `IndentEditsTest` `BackspaceRepeatTest` `CaretNavigatorTest` `TouchUpFilterTest` |
| 工具 | `RangeSearchTest` `TextBoundariesTest` |
| 整体（设备） | `EditorViewTest` |

两个布局测试各包含一万两千次随机编辑，把增量更新的结果与全量重排逐行对比。

设备测试里会把编辑器画到位图上，检查普通文本、折行文本和带样式的文本确实画了出来。除此之外，触摸手势、滚动、内边距换算、软键盘遮挡没有自动化测试，依赖手动验证；无障碍只验证了交给屏幕阅读器的信息，没有用真实的屏幕阅读器测试过。

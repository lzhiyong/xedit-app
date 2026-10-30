# editor: Testing

> Part of the documentation of the [editor](../editor/README.md) module · [中文](editor_testing_cn.md)

```bash
./gradlew :editor:testDebugUnitTest          # on the computer
./gradlew :editor:connectedDebugAndroidTest  # needs a device or an emulator
```

The build script needs `useJUnitPlatform()` and `junit-platform-launcher`; see `tests/README.md`.

| Scope | Test classes |
|---|---|
| Model | `EditorDocumentTest` `EditHistoryTest` `UndoManagerTest` `SearchResultsTest` |
| Layout | `UnwrappedLayoutTest` `WordwrapLayoutTest` `FoldedLayoutTest` `TabStopsTest` `LineBreaksTest` `LayoutCoordinatorTest` |
| Code structure | `StructureProvidersTest` `FoldingModelTest` |
| Input | `InputComposerTest` `InputMethodBridgeTest` `IndentEditsTest` `BackspaceRepeatTest` `CaretNavigatorTest` `TouchUpFilterTest` |
| Utilities | `RangeSearchTest` `TextBoundariesTest` |
| Whole widget (device) | `EditorViewTest` |

The two layout tests each run twelve thousand random edits and compare the incrementally updated result with a full relayout, line by line.

The device test draws the editor into a bitmap and checks that plain, wrapped and styled text was actually drawn. Beyond that, touch gestures, scrolling, padding conversion and keyboard overlap have no automated tests and are verified by hand; accessibility is tested only for what the screen reader is handed, never with a real screen reader.

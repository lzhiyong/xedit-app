# document：行为与性能

> [document](../document/README_CN.md) 模块文档 · [English](document_behavior.md)

## 行为说明

### 元数据快照

`SingleDocumentFile` 和 `TreeDocumentFile` 只读取一次元数据并缓存。如果文档可能已经发生变化，请调用 `refresh()`：

```kotlin
document.refresh()          // 重新读取元数据；对于目录，还会重新统计子项数量
val size = document.length()
```

`exists()` 每次都会查询 provider。`RawDocumentFile` 不做任何缓存。

### 用返回值代替异常

| 情况 | 结果 |
|---|---|
| 文档已删除、权限被撤销、provider 出错 | `exists()` / `delete()` → `false`；`listFiles()` / `createFile()` / `renameTo()` → `null`；元数据 → `""` / `0` |
| 对非目录调用 `listFiles()` | `null` |
| 对空目录调用 `listFiles()` | 空列表 |
| 对 `SingleDocumentFile` 调用 `createFile()` / `createDirectory()` | `UnsupportedOperationException` |

也就是说，`listFiles()` 返回 `null` 表示“没有可显示的内容”，返回空列表则表示“目录是空的”。失败信息会以 `DocumentFile` 为标签输出到日志。

### 不可变性

实例的 Uri 永远不会改变。`renameTo()` 返回一个新实例，原实例保持不变；重命名之后，原实例通常 `exists()` 会返回 `false`。

### `fromTreeUri()`

- 既接受 `ACTION_OPEN_DOCUMENT_TREE` 返回的 tree Uri，也接受树内的文档 Uri。
- 如果 Uri 不是 tree Uri，返回 `null`。
- 如果 Uri 指向文件，返回 `SingleDocumentFile`；否则返回 `TreeDocumentFile`。
- 恰好执行一次查询。

### 磁盘文件

`RawDocumentFile.createFile()` 会追加该 MIME 类型对应的扩展名，除非名称已经以该扩展名结尾；如果没有注册对应的扩展名，则直接使用原名称。`createFile()`、`createDirectory()` 和 `renameTo()` 会拒绝包含 `/` 的名称以及 `.` 和 `..`。`renameTo()` 在目标文件已存在时会失败，而不是覆盖它。`delete()` 会递归删除目录，但对于符号链接只删除链接本身，绝不删除其指向的目标。

## 性能

对于一个包含 **F** 个文件和 **D** 个子目录的目录，provider 查询次数如下：

| 操作 | 旧版本 | 现在 |
|---|---|---|
| `fromTreeUri()` | 2 | 1 |
| `fromSingleUri()` | 1（在构造函数中） | 调用 getter 之前为 0 |
| `listFiles()` | 1 + F + 2·D | **1** |
| 读取已列出子项的元数据 | 0 | 0 |
| `find(name)` | 1 + F + 2·D | **1** |
| `getChildCount()` | 每创建一个目录实例就立即统计一次 | 首次调用时 1 次，之后使用缓存 |

其中 `2·D` 这一项代价尤其高：每个被列出的子目录都要把自己的子项完整枚举一遍，只为了得到数量。

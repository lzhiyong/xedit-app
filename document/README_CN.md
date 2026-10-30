# DocumentFile

AndroidX `DocumentFile` 的一个更快、更安全的重写版本，适用于通过存储访问框架（SAF）或直接在磁盘上浏览文件的应用。一套 API 覆盖三种文档：

| 类 | 底层 | 创建方式 |
|---|---|---|
| `RawDocumentFile` | `java.io.File` | `DocumentFile.fromFile()` |
| `SingleDocumentFile` | 单个 SAF 文档 | `DocumentFile.fromSingleUri()`，或树中的文件子项 |
| `TreeDocumentFile` | SAF 树中的目录 | `DocumentFile.fromTreeUri()`，或树中的目录子项 |

```
document
├── DocumentFile          公开 API 与工厂方法
├── RawDocumentFile       基于 java.io.File 的实现
├── SingleDocumentFile    单个 SAF 文档
├── TreeDocumentFile      SAF 树中的目录
├── DocumentInfo          （internal）从一行游标数据读取的元数据快照
├── DocumentQueries       （internal）从不抛出异常的 provider 调用
└── DocumentFlags         （internal）可读 / 可写 / 虚拟文档的判断规则
```

## 特性

- **列目录只需一次查询。** `listFiles()` 用一次 provider 查询读取所有子项及其元数据，而不是为每个子项再额外查询一到两次。
- **构造函数中不做 I/O。** 元数据在首次访问时懒加载，或直接来自列目录的查询结果，并缓存到调用 `refresh()` 为止。
- **不会因存储状态而崩溃。** 文档已删除、权限被撤销、provider 出错等情况都以 `null` / `false` / `0` 的形式返回，而不是抛出异常。
- **实例不可变。** 文档的 Uri 永远不变；`renameTo()` 返回一个新实例。
- **`fromUri()` 支持任意 Uri**：`file://`、tree Uri 和单文档 Uri。
- **安全的文件操作**：会逃出所在目录的名称会被拒绝，重命名绝不覆盖已有文件，递归删除绝不跟随符号链接。
- **基于 Uri 的 `equals()` / `hashCode()`**，文档可以作为 Map 的键，也可以直接用于 `DiffUtil`。

## 文档

详细内容见本模块的文档：

- [使用指南](../docs/document_usage_cn.md)：使用方法、API 概览、从旧版本迁移
- [行为与性能](../docs/document_behavior_cn.md)：行为说明、性能
- [测试](../docs/document_testing_cn.md)：测试

## 环境要求

- minSdk 24
- `androidx.annotation:annotation`

## 安装

引入模块并添加依赖：

```kotlin
// settings.gradle.kts
include(":document")

// app/build.gradle.kts
dependencies {
    implementation(project(":document"))
}
```

## 局限性

- `TreeDocumentFile` 和 `SingleDocumentFile` 报告的是最近一次读取时的元数据；如果文档可能已经变化，请调用 `refresh()`。
- `getParent()` 反映的是该实例是如何被访问到的；由工厂方法创建的文档没有父节点。
- `TreeDocumentFile` 的 `getChildCount()` 会被缓存；通过同一个实例创建子项时缓存会自动失效，但在其他地方发生的修改需要调用 `refresh()`。
- 返回值不区分“provider 出错”和“未找到”；具体原因请查看日志。

## 许可证

基于 AndroidX `DocumentFile`，Copyright © The Android Open Source Project。

```
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

# 架构

> [X Editor](../README_cn.md) 文档 · [English](architecture.md)

## 目录结构

```
xedit-app/
├── app/                        应用（x.editor.app）
│   └── src/main/java/x/editor/app/
│       ├── activity/           EditorActivity、SettingsActivity、CrashActivity
│       ├── controller/         文件列表、搜索面板、跳转到行
│       ├── highlight/          TreeSitterEngine、CaptureStore、StructureQuery、
│       │                       TreeSitterInjections、InjectionPrefilter、QueryPredicates
│       ├── service/            EditorService：文档读写、编辑会话、下载
│       ├── view/               CodeEditorView（接入 tree-sitter 的 EditorView）、抽屉、insets 处理
│       └── ...                 设置、下载、数据库、查找、会话、工具类
├── editor/                     编辑器视图（x.github.module.editor）
│   └── src/main/java/.../editor/
│       ├── view/               EditorView、FloatingBar、CodeStructure
│       ├── render/             TextRenderer、RowCache、FrameBudget、Gutter
│       ├── layout/             不折行、折行、折叠三种排版
│       ├── input/              手势、选择、输入法、自动配对
│       ├── structure/          代码折叠、缩进线、括号
│       └── model/              文档、编辑历史、查找结果
├── piecetable/                 文本缓冲区
├── treesitter/                 tree-sitter 绑定（Kotlin + C++）、get_sources.py
├── document/  treeview/  crash/  lsp/  bypass/
├── libs.versions.toml          插件和依赖库的版本
└── settings.gradle.kts
```

## 文档与编辑会话

当前打开哪个文档，由 `EditorActivity` 决定：

- 同一时间只加载一个文档。选择另一个文档时，会先取消正在进行的加载，等它完全停止后再开始新的加载。
- 只有完整加载过的文档才会被写回，因此一个文件的内容不可能被写进另一个文件。
- 保存操作依次执行，打开其他文档也不会取消它们。加载新文档前，会先等待尚未完成的保存。
- 如果写回上一个文档失败，该文档保持打开，修改内容不会丢失，同时显示错误信息。

每个文档的编辑会话，都和它所对应文件内容的 SHA-256 一起保存。打开文档时，文本总是从文件读取；只有哈希仍然一致时，才会恢复编辑会话。

# app

X Editor 的应用模块：各个界面、设置、tree-sitter 语法高亮、语言服务器与代码补全，以及控制台。它建立在以下库模块之上：[`editor`](../editor)、[`piecetable`](../piecetable/README_CN.md)、[`treesitter`](../treesitter/README_CN.md)、[`document`](../document/README_CN.md)、[`treeview`](../treeview/README_CN.md)、[`crash`](../crash/README_CN.md) 和 [`lsp`](../lsp/README_CN.md)。

English version: [README.md](README.md).

## 文档

详细内容见本模块的文档：

- [设计与原理](../docs/app_architecture_cn.md)：包结构、各部分如何工作
- [资源命名](../docs/app_resources_cn.md)：资源命名
- [测试](../docs/app_testing_cn.md)：测试

## 读写的文件

| 内容 | 位置 |
| --- | --- |
| 高亮资源（语法库、查询文件、主题） | 按 [`assets/treesitter.json`](src/main/assets/treesitter.json) 中的地址下载，保存在应用的私有目录中 |
| 语言服务器配置 | `servers.json`：有"所有文件访问权限"时在共享存储的 `xedit/lsp/` 中，否则在 `Android/data/x.editor.app/files/lsp/` 中；可从菜单"语言服务器配置"打开 |
| 由应用启动的服务器的二进制 | `servers.json` 旁边的 `bin/` 和 `lib/`，运行前复制到应用的私有存储中 |
| 语言服务器日志 | `servers.json` 旁边的 `lsp.log`，以及控制台 |
| 编译运行命令 | 应用的设置中，按文件扩展名分别保存（长按工具栏上的运行按钮设置） |

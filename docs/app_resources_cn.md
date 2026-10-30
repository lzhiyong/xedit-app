# app：资源命名

> [app](../app/README_CN.md) 模块文档 · [English](app_resources.md)

`res/` 中的名称说明资源的用途，按 `<种类>_<功能>_<部分>` 的顺序；values 中的资源按
`<功能>_<部分>_<作用>`。除样式外，一律小写，以 `_` 分隔。

| 种类 | 规则 | 示例 |
| --- | --- | --- |
| 布局 | `activity_`、`dialog_`、`sheet_`（底部面板）、`view_`（嵌入界面的面板）、`item_`（列表的一行）、`pref_`（设置项的外观） | `dialog_goto_line`、`item_extra_key`、`pref_card_top`、`pref_widget_switch` |
| 菜单 | `<界面>_<位置>` | `editor_toolbar`、`editor_tab_popup`、`crash_toolbar` |
| Drawable | `ic_` 图标（与 Material 一样按图形命名），`bg_` 背景或形状；`ic_picker_` 是由选择面板自行着色的白色图标 | `ic_content_copy`、`bg_pref_card_top`、`bg_download_badge` |
| 颜色（状态列表） | `<功能>_<部分>_<属性>` | `pref_title_text`、`search_option_icon_tint` |
| 设置页 | `xml/pref_<界面>` | `pref_app`、`pref_editor` |
| 尺寸 | `<功能>_<部分>_<属性>` | `pref_card_corner_outer`、`extra_key_height` |
| 样式 | `Theme.XEdit`、`ThemeOverlay.XEdit.*`、`Widget.XEdit.<控件>.*`、`ShapeAppearanceOverlay.XEdit.*`；主题放在 `themes.xml`，其余放在 `styles.xml` | `Widget.XEdit.SearchPanel.OptionButton` |

字符串以所属功能开头（`search_`、`file_tree_`、`lsp_`、`console_`、`toolchain_` 等），
作用不明显时以作用结尾：`_title`、`_summary`、`_hint`、`_helper`、`_message`、
`_description`（无障碍描述）、`_empty`（无内容）、`_failed` / `_error`、`_done`。
有两类沿用已有的名称：

- 菜单项的标题与该菜单项的 id 同名（`action_save_file`）；
- 设置项的字符串以它的 key 开头，再加作用：`pref_editor_tab_size_title`、
  `pref_editor_tab_size_entries` 等；`bools.xml` 和 `strings.xml` 中的默认值以 `_default`
  结尾（`pref_editor_wordwrap_default`）。

`strings.xml` 按功能分节，与 `values-zh/strings.xml` 的顺序一致；新字符串要在两个文件的
对应小节中同时添加。应用不再使用的字符串，在两个文件中一并删除。

`res/font` 中的字体保留原名：字体设置以 `res:<名称>` 的形式保存它们，列表中也显示这个名称。

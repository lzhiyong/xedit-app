# app: Resource naming

> Part of the documentation of the [app](../app/README.md) module · [中文](app_resources_cn.md)

Names in `res/` say what a resource is for, in this order: `<kind>_<feature>_<part>`, or
`<feature>_<part>_<role>` for values. All lower case with `_`, styles excepted.

| Kind | Pattern | Examples |
| --- | --- | --- |
| Layout | `activity_`, `dialog_`, `sheet_` (bottom sheet), `view_` (a panel included in a screen), `item_` (a row of a list), `pref_` (the look of a preference) | `dialog_goto_line`, `item_extra_key`, `pref_card_top`, `pref_widget_switch` |
| Menu | `<screen>_<where>` | `editor_toolbar`, `editor_tab_popup`, `crash_toolbar` |
| Drawable | `ic_` an icon (named after what it shows, as Material's are), `bg_` a background or shape; `ic_picker_` are white icons that the picker tints itself | `ic_content_copy`, `bg_pref_card_top`, `bg_download_badge` |
| Color (state list) | `<feature>_<part>_<attribute>` | `pref_title_text`, `search_option_icon_tint` |
| Preference screen | `xml/pref_<screen>` | `pref_app`, `pref_editor` |
| Dimension | `<feature>_<part>_<property>` | `pref_card_corner_outer`, `extra_key_height` |
| Style | `Theme.XEdit`, `ThemeOverlay.XEdit.*`, `Widget.XEdit.<Widget>.*`, `ShapeAppearanceOverlay.XEdit.*`; themes in `themes.xml`, the rest in `styles.xml` | `Widget.XEdit.SearchPanel.OptionButton` |

Strings start with the feature they belong to (`search_`, `file_tree_`, `lsp_`, `console_`,
`toolchain_`, ...) and end with their role where it is not obvious: `_title`, `_summary`,
`_hint`, `_helper`, `_message`, `_description` (for accessibility), `_empty` (nothing to
show), `_failed` / `_error`, `_done`. Two kinds follow a name that is already there:

- the title of a menu item has the name of the item's id (`action_save_file`);
- the strings of a preference have the name of its key, then the role:
  `pref_editor_tab_size_title`, `pref_editor_tab_size_entries`, and so on; the default values
  in `bools.xml` and `strings.xml` end with `_default` (`pref_editor_wordwrap_default`).

`strings.xml` is in sections, one per feature, in the same order as `values-zh/strings.xml`;
a new string goes into its section in both. A string the app no longer uses is deleted, in
both.

The fonts in `res/font` keep their names: the font setting stores them (`res:<name>`), and
the list shows them.

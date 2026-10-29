# Feature guide

This guide describes **0.4.16** and its current Settings layout.

Open **Settings → Appearance & Behavior → Project Tab Icons**.
The preview uses three or five fictional projects: Frontend, Backend, MobileApp, Tools and pet_projects. It updates
immediately; changes to real project windows are applied through **Apply**.
The page applies at IDE level. The enable switch, **Presets** and **User presets**
stay above **Appearance / Projects / Project groups**, outside section scrolling.

## Appearance

| Control | Choices |
| --- | --- |
| Preset | Custom, Default, Filled, Bold, Git, Relax, plus up to 10 named user presets |
| Icon size | Small 20 px, Medium 24 px, Large 28 px |
| Icon position | Left or right |
| Close button | Left or right, independently of the icon |
| Tab corners | Auto, Square or IDEA theme; Auto follows the theme on macOS and uses square corners on Windows |
| Outline | Bottom only, Rounded corners, Full outline |
| Outline thickness | 1–6 px |
| Height | 30–60 px |
| Tab fill | 0–100% intensity, in one-percent steps; an independent project color |
| Fill inset | 0–6 px |
| Active tab highlight | 0–100%, default 25% |
| Inactive tab dimming | 0–100%, default 75% |
| Content → Show | Name only, Project path, Branch, Compact branch, Changed lines |
| Git info position | Before or after the project name |

Active highlight and inactive dimming apply to regular tabs, both group variants
and Preview. Hover highlighting stays at or below the selected level. Inactive
dimming affects each inactive tab's fill and outline, including tabs in the active
group. Zero disables the corresponding adjustment.

Tab and group bodies, including fill insets, use an opaque base above the IDE
project gradient. Fill intensity mixes the chosen color with that base; the native
gradient remains outside the tab contours. Disabling customization restores native
state colors.

**Tab layout** places **Content** and **Selection** in the left column and
**Size & position** on the right. **Use IDE font size** selects automatic sizing;
clear it for a fixed value. Pixel dimensions use compact numeric fields. Percentage
sliders have synchronized exact numeric inputs. Narrow windows use one column.
Within **Appearance**, switch between **Style / Tab layout / Group layout**;
each top-level section retains its own scrolling position.

Choose a preset, then adjust any appearance value to switch to **Custom**.
Click **Custom** to return to the last manual appearance. Apply remembers it across
IDE sessions; Cancel discards draft changes to that memory. Filled uses Small
20 px icons, a full 1 px outline, 12% fill intensity, a 3 px inset and 34 px height.
Existing settings default to 50% intensity; other presets and Restore defaults use 50%.
Changing the preview theme or a project's icon assignment does not change the preset.
Undoing all manual appearance edits restores the original preset selection and
disables **Save as new preset** again.
After a manual appearance edit, **Save as new preset** lets you name the current
Style, Tab layout and Group layout settings. Names must be unique and at most 32 characters.
User presets appear in a separate **User presets** row with the standard text color.
Both rows share one radio-button group, so only one preset can be selected at a time.
Right-click one, or press **Shift+F10** while focused, to rename or delete it.
At 10 user presets, delete one to make room for another. Apply saves preset
creation, renaming, deletion and selection; Cancel discards the draft.
Editing a user preset switches to Custom without overwriting the named original.
The enable switch, preview theme and individual project assignments are excluded.
**Restore defaults** changes the draft and takes effect after Apply; Cancel abandons
the draft. The enable switch and project icon assignments are preserved by a reset.

**Save as new preset** sits beside the Appearance sub-tabs.
**Manage user presets…** in the shared header renames, duplicates, deletes and
reorders user presets. **Import styles… / Export styles…** transfers
selected presets, SVG categories and editable text icons in portable JSON bundles.
Import resolves matching names explicitly; project memberships and paths are not exported.

## Project groups

Open **Projects** or **Project groups** using the top-level selector. Both use
matching icon and color columns and independent section scrolling. Projects shows
the path below each name and its group in a separate column (**—** when ungrouped).
Grouped rows show **Inherited from …** and readable values; **Edit group** opens
the matching group. Use **Ungroup** to remove selected projects from their groups.
The **+** in a group's **Actions** opens a searchable multi-project chooser. It starts
with an empty selection on every opening, independent of checkboxes in Projects.

The first group row is **+ Add group**. Edit a name, select members and set
optional positions directly in the table, then use **Add / Save**.
Paths appear under member names; long lists use the section scrollbar. **Cancel**
closes the inline draft. Settings **Apply** or **OK** also validates and commits
an unfinished group edit; duplicate names keep the draft open for correction.
Groups have their own icon and three colors. Members inherit them dynamically,
including newly assigned and reopened projects. Removing a member or deleting its
group restores that project's personal appearance. Group styling does not overwrite
its personal icon files. Inherited values are edited in Project groups.

Groups' open members are placed together on the left when project windows
are merged, followed by ungrouped projects. **↑ / ↓** sets group order. Each member can use **Auto** or a distinct
fixed position within the group. For Tools = 1, Tools comes before Auto members;
Auto members retain their relative order. Closed members remain editable and do not
leave gaps while absent. Existing groups follow the same ordering without an extra grouping switch.

Order is applied on merge. Later manual rearrangements remain available.
**Arrange Tabs by Group** in a window tab's context menu reapplies the saved order.
**Open All Grouped Projects**, also in **Tools → Project Tab Icons**, opens missing
group members, merges them into the current window and arranges the tabs.

Under **Group layout → Group highlight → Variant**, choose **Shared background**
or **Joined tabs**. Both inherit regular tab dimensions, theme corners, Style
settings, active/hover colors and **Style → Icon → Size**. Group controls cover
active-card inset, internal dividers, shared-icon visibility, hiding member
icons, group spacing and boundary pipes. Group Tab color supplies the fill; Outline
color supplies the contour, with icon color as the Automatic fallback. A split group's
shared decoration remains hidden until **Arrange Tabs by Group** restores it.

Reordering within a group keeps its original frame, icon, spacing and separators
stable. Moving a tab outside the original group bounds previews the split; returning
before release restores the group. The final appearance is committed when the drag
ends. The dragged background follows its visible shape, leaving its margins transparent.

## Quick actions

**Tools → Project Tab Icons** and **Find Action** expose icon, icon color, outline
color, tab color, preset switching and Settings. Configure shortcuts in **Keymap**.
Quick editors apply their own changes immediately; inherited member styling is
edited through its group.

## Icons and colors

The bundled library has **100+ icons**, including language symbols, animals,
tools and navigation icons. Search by name or keyword and filter by category.
The library also includes digits, Roman numerals, Latin and Greek letters, and AI/Ai monograms.
Additional silhouettes cover characters, food, science, games, art, weather and
travel, with English names and Russian search keywords.
Use the native IDE color picker, a HEX value, or 14 swatches.

Import a standalone SVG or browse the SVG icons used by open projects. Existing
custom SVG/PNG artwork retains its original colors. Display settings do not rewrite
those images. Generated initials offer one letter, two initials or mixed-case
initials, with normal or bold text.

Use **Icon library → Import folder…** to add SVG files and subfolders as a shared
category marked **★**. Imported artwork keeps its original colors and is searchable.
The library stores copies, so moving the source folder does not remove the icons.
Invalid files are reported and skipped; identical SVGs within a set are merged.
Import up to 500 SVGs per folder, within a 16 MiB library and 50 imported categories.

The **Text icon** tab creates labels of 1–6 characters, such as `DB`, `QA`, `API`
or `αβ`. Choose regular, bold, italic or bold italic lettering; sans serif, serif
or monospace fonts; and a palette, HEX or custom color. Text is saved as transparent
SVG paths, with metadata for editing it later. The project's Icon color action
also recolors text icons. **Choose icon → Apply** saves imported sets and icon
assignments; Cancel discards the draft.

The open-project table separates **Project**, **Group**, **Icon**, **Icon color**, **Outline color** and
**Tab color**. Keep colors matched to the icon or choose them independently.
Pending project changes are saved together with Apply. An icon changed outside
the settings is checked before replacement; reload the row when requested.
Small dots identify unsaved appearance fields and project icon/color values.
Apply or returning to a saved value clears the corresponding mark.

SVG import failures explain the cause and a recovery step, including empty or
oversized files, invalid XML, unsupported content and unavailable files. Error
messages wrap across lines and preserve the previous icon selection.

Choosing a new icon replaces that project's native light/dark icon variants.
The result remains a native project icon even without this plugin. Keep original
custom artwork separately if you want to restore it later.

## Git information

The optional Git4Idea integration supplies the branch for the relevant project
and repository. It follows the current file's repository when possible.

Choose these modes in **Tab layout → Content → Show**:

- **Branch:** displays the branch name and preserves the end of a long name.
- **Compact branch:** keeps the part after the last `/`, limited to 13 Unicode
  characters including a leading ellipsis. The full branch stays in the tooltip.
- **Changed lines:** shows added/removed-line counts such as `+128 -16`.
- **Also show changed lines** combines counts with either branch display mode.

**Git info position** places the branch and counters to the left or right of the name.
Outside a Git repository, tabs show **[No repo!]** without counters.

Counts cover saved tracked changes against `HEAD`, including staged and unstaged
changes. Untracked files, binary changes and unsaved editor text are excluded.
The clean state is `+0 -0`; loading uses an ellipsis and an unavailable result uses
a dash. Detached HEAD hides the branch while allowing an enabled line count.

The branch comes from the IDE's Git model. Counts use a local background Git diff
with cancellation, a delay and a timeout. This does not fetch or contact remotes.

## Preview and persistence

Preview starts compact. **Full window** adds file tabs and an editor fragment.
Choose **3 tabs** or **5 tabs** beside Preview and switch among eight IDE themes.
Click a sample tab, or use Space/Enter while it has focus, to compare active states.
The sample count never changes real windows, presets or Apply. Light uses light tab
surfaces and readable foregrounds while retaining its dark toolbar and light editor.

The preview theme is temporary and does not change the IDE's theme. Appearance
settings persist in `projectTabIcons.xml`; last Custom memory is saved in
`projectTabIconsCustom.xml`, and named user presets in `projectTabIconsPresets.xml`.
Groups and memberships persist locally in `projectTabIconsGroups.xml`.
Project SVG icons use IDEA's native
project icon storage. Turning customization off restores the native tab presentation
while keeping selected settings and saved project icons.

# Feature guide

Open **Settings → Appearance & Behavior → Project Tab Icons**.
The preview uses fictional Frontend, Backend and Tools projects. It updates
immediately; changes to real project windows are applied through **Apply**.

## Appearance

| Control | Choices |
| --- | --- |
| Preset | Custom, Default, Filled, Bold, Git, Relax, plus up to 10 named user presets |
| Icon size | Small 20 px, Medium 24 px, Large 28 px |
| Icon position | Left or right |
| Close button | Left or right, independently of the icon |
| Outline | Bottom only, Rounded corners, Full outline |
| Outline thickness | 1–6 px |
| Tab height | 30–60 px |
| Tab fill | 0–100% intensity, in one-percent steps; an independent project color |
| Fill inset | 0–6 px |
| Extra information | None, Project path, Git branch, Git Branch Compact, Git status |
| Git information position | Before or after the project name |

Choose a preset, then adjust any appearance value to switch to **Custom**.
Click **Custom** to return to the last manual appearance. Apply remembers it across
IDE sessions; Cancel discards draft changes to that memory. Filled uses Small
20 px icons, a full 1 px outline, 12% fill intensity, a 3 px inset and 34 px height.
Existing settings default to 50% intensity; other presets and Restore defaults use 50%.
Changing the preview theme or a project's icon assignment does not change the preset.
After a manual appearance edit, **Save as new preset** lets you name the current
Icon, Color and Tab layout settings. Names must be unique and at most 32 characters.
User presets appear in the same radio-button list with a star and accent color.
Right-click one, or press **Shift+F10** while focused, to rename or delete it.
At 10 user presets, delete one to make room for another. Apply saves preset
creation, renaming, deletion and selection; Cancel discards the draft.
Editing a user preset switches to Custom without overwriting the named original.
The enable switch, preview theme and individual project assignments are excluded.
**Restore defaults** changes the draft and takes effect after Apply; Cancel abandons
the draft. The enable switch and project icon assignments are preserved by a reset.

## Icons and colors

The bundled library has **100+ icons**, including language symbols, animals,
tools and navigation icons. Search by name or keyword and filter by category.
The library also includes digits, Roman numerals, Latin and Greek letters, and AI/Ai monograms.
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

The open-project table separates **Icon**, **Icon color**, **Outline color** and
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

- **Git branch:** displays the branch name and preserves the end of a long name.
- **Git Branch Compact:** keeps the part after the last `/`, limited to 13 Unicode
  characters including a leading ellipsis. The full branch stays in the tooltip.
- **Git status:** shows added/removed-line counts such as `+128 -16`.
- **Show Git status:** combines counts with either branch display mode.

Counts cover saved tracked changes against `HEAD`, including staged and unstaged
changes. Untracked files, binary changes and unsaved editor text are excluded.
The clean state is `+0 -0`; loading uses an ellipsis and an unavailable result uses
a dash. Detached HEAD hides the branch while allowing an enabled line count.

The branch comes from the IDE's Git model. Counts use a local background Git diff
with cancellation, a delay and a timeout. This does not fetch or contact remotes.

## Preview and persistence

The preview theme is temporary and does not change the IDE's theme. Appearance
settings persist in `projectTabIcons.xml`; last Custom memory is saved in
`projectTabIconsCustom.xml`, and named user presets in `projectTabIconsPresets.xml`.
Project SVG icons use IDEA's native
project icon storage. Turning customization off restores the native tab presentation
while keeping selected settings and saved project icons.

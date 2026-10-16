# Feature guide

Open **Settings → Appearance & Behavior → Project Tab Icons**.
The preview uses fictional Frontend, Backend and Tools projects. It updates
immediately; changes to real project windows are applied through **Apply**.

## Appearance

| Control | Choices |
| --- | --- |
| Preset | Custom, Default, Filled, Bold, Git, Relax |
| Icon size | Small 20 px, Medium 24 px, Large 28 px |
| Icon position | Left or right |
| Close button | Left or right, independently of the icon |
| Outline | Bottom only, Rounded corners, Full outline |
| Outline thickness | 1–6 px |
| Tab height | 30–60 px |
| Tab fill | 50% opacity; an independent project color |
| Fill inset | 0–6 px |
| Extra information | None, Project path, Git branch, Git Branch Compact, Git status |
| Git information position | Before or after the project name |

Choose a preset, then adjust any appearance value to switch to **Custom**.
Changing the preview theme or a project's icon assignment does not change the preset.
**Restore defaults** changes the draft and takes effect after Apply; Cancel abandons
the draft. The enable switch and project icon assignments are preserved by a reset.

## Icons and colors

The bundled library has **121 filled shapes**, including language symbols, animals,
tools and navigation icons. Search by name or keyword and filter by category.
Use the native IDE color picker, a HEX value, or 14 swatches.

Import a standalone SVG or browse the SVG icons used by open projects. Existing
custom SVG/PNG artwork retains its original colors. Display settings do not rewrite
those images. Generated initials offer one letter, two initials or mixed-case
initials, with normal or bold text.

The open-project table separates **Icon**, **Icon color**, **Outline color** and
**Tab color**. Keep colors matched to the icon or choose them independently.
Pending project changes are saved together with Apply. An icon changed outside
the settings is checked before replacement; reload the row when requested.

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
settings persist in `projectTabIcons.xml`; project SVG icons use IDEA's native
project icon storage. Turning customization off restores the native tab presentation
while keeping selected settings and saved project icons.

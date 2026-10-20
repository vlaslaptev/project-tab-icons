# Changelog

## 0.3.62 — 2026-10-20

- Replace bulk appearance actions with group styling, while keeping Assign and Ungroup.
- Show membership in a separate Group column, with a dash for ungrouped projects and project paths below names.
- Resize the inline member list with Settings and show a muted project path for every choice.
- Optionally group window tabs on merge, set fixed positions for members and reorder groups with up/down arrows.
- Restore the configured order from a window tab's context menu; preserve manual rearrangements between merges.

## 0.3.61 — 2026-10-20

- Give each project group its own icon, icon color, outline color and tab color, even before assigning projects.
- Members inherit the group's appearance automatically, including newly assigned and reopened projects.
- Edit inherited values in Project groups. Removing a project from a group restores its individual icon and colors.
- Keep group appearance in the Settings draft until Apply; Cancel discards pending changes.

## 0.3.60 — 2026-10-20

- Switch between Projects and Project groups with matching tabs and one visible table.
- Edit group names and membership directly in the group row with Save / Cancel and inline validation.
- Use matching icon and color columns in both tables. Group actions style its open local projects; differing values appear as Mixed.

## 0.3.59 — 2026-10-20

- Create groups directly in the table: + inserts a row with a name, project checkboxes and Add / Cancel.
- Show name validation in the same row and preserve unfinished input while switching cells or filters.

## 0.3.58 — 2026-10-20

- Show project groups in a table with a permanent first + row. Enter a name and choose projects to add a group.
- Edit group membership and names from row actions, assign selected projects, and show selection counts across filters.
- Keep group changes in the Settings draft until Apply.

## 0.3.57 — 2026-10-20

- Use Tools, Find Action and configurable keyboard shortcuts to edit the current project icon and colors or switch appearance presets.
- Organize open projects into groups, select multiple rows and apply icons, colors or an outline palette with Settings Apply.
- Import and export selected appearance presets, SVG categories and editable text icons as portable JSON bundles, with name conflict choices.
- Update user presets in place, duplicate them and change their display order with Manage presets.

## 0.3.56 — 2026-10-19

- Show user presets in a separate neutral radio-button row, sharing selection with built-in presets.

## 0.3.55 — 2026-10-19

- Import an SVG folder and its subfolders as a shared, searchable icon category, preserving original colors.
- Keep independent copies in the icon library; skip invalid files with an explanation and merge identical SVGs within each set.
- Create 1–6-character text icons such as DB, QA, API or αβ, with four styles, three font families and custom colors.
- Save text as transparent SVG paths with editable metadata; preserve text, font and style when recoloring from the project row.
- Keep imports and icon assignments in the Settings draft until Apply; Cancel leaves saved state unchanged.

## 0.3.54 — 2026-10-18

- Place smaller unsaved-change dots consistently after visible captions, including checkbox/radio controls and shortened project-row buttons.
- Reserve marker space during layout and respect UI scale, disabled controls and right-to-left layouts.

## 0.3.53 — 2026-10-18

- Save up to 10 named presets for Icon, Color and Tab layout in the existing radio-button list.
- Mark user presets with a star and accent color; offer Rename and Delete from their context menu.
- Preserve the named original when editing its values, and save preset changes only with Apply.

## 0.3.52 — 2026-10-18

- Return to the last manual appearance with Custom, including after restarting IDEA.
- Save Custom memory with Apply; Cancel discards changes to that memory.
- Mark unsaved appearance fields and project icon/color values with a small dot.
- Explain SVG import errors with recovery steps and wrapped messages while preserving the previous selection.

## 0.3.51 — 2026-10-18

- Update Filled to Small 20 px icons, 12% fill intensity and a 3 px inset.
- Keep its full 1 px outline and 34 px height; saved manual appearances remain unchanged until a preset is applied.

## 0.3.50 — 2026-10-18

- Add Fill intensity in Color, from 0% transparent to 100% solid in one-percent steps.
- Update the Settings preview and color-picker sample immediately; Apply updates window tabs.
- Preserve intensity when fill is disabled. Older settings default to 50%.

## 0.3.49 — 2026-10-18

- Prevent legacy icon backgrounds from returning during deferred loading or when the IDE window loses focus.
- Prepare SVG/PNG artwork in the background; keep the last prepared image during reloads or leave the slot empty until it is ready.
- Expand the 100+ icon library with digits, Roman numerals, Latin and Greek letters, and AI/Ai monograms.
- Draw capital I in AI with horizontal bars; use straight geometric strokes and a square dot in Ai.
- Improve preview loading, color-picker tab fills and preservation of explicit font sizes.
- Preserve existing project artwork, colors and appearance settings.
- Keep the README and the approved icon-library slide independent of the exact catalog size.

## 0.3.46 — 2026-10-16

Prepared as the initial public GitHub snapshot of Project Tab Icons.

- Customize native macOS project window tabs with icons, outlines and translucent fills.
- Choose Default, Filled, Bold, Git, Relax or Custom appearance.
- Browse 121 bundled icons, style initials, import SVGs and reuse open-project SVGs.
- Set independent icon, outline and tab colors; preview before Apply.
- Show project paths, Git branches, compact branches and saved tracked-line counts.
- Preserve the end of long branch names and the spacing before Git status.
- Use the Braces icon for Tools in the settings preview.
- Keep pending-change feedback in the shared status area; remove per-row Edited labels.

The public presentation, bilingual README, documentation and media do not change
the plugin implementation or its compatibility range. Distribution is a manually
installed, unsigned ZIP for IntelliJ IDEA 2026.2 on macOS.

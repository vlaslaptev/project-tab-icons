# Changelog

## 0.4.16 — 2026-10-29

Changes since the public 0.3.120 release:

- Ship separate macOS and Windows beta packages. Merge Windows projects into one window, separate individual projects, and restore the merged layout between sessions.
- Add automatic project-window tabs on both platforms and Open All Grouped Projects for opening and arranging saved groups.
- Add Auto, Square and IDEA theme tab corners. Preserve native macOS window tabs and use square corners by default on Windows.
- Match the Windows preview to the merged window, including top-row project tabs, native window controls, right-side close buttons and base tab styling.
- Keep Windows layouts synchronized during resize, maximize/restore and display changes. Preserve tab styling after toolbar gradient updates.
- Use a single Save as new preset action. Commit and validate unfinished group drafts through Settings Apply or OK.
- Resolve quick actions using the actual project path, including `.ipr` projects; avoid redundant repaints and batch icon refreshes.
- Remove the light outline around dark macOS preview headers.
- Exclude local agent instructions from Git and public release exports.

Both ZIPs have source and packaging checks. Earlier targeted GUI checks used
0.4.15 on macOS ARM64 and Windows 11 ARM64; no new GUI pass is claimed for 0.4.16.

## 0.3.120 — 2026-10-25

Changes since the public 0.3.107 release:

- Separate Appearance, Projects and Project groups, each with its own scrolling position. Keep built-in and user preset choices above all sections.
- Start with a compact Preview and offer Full window. Organize Tab layout into Content and Selection on the left and Size & position on the right.
- Use consistent pixel inputs and synchronized exact values beside percentage sliders. Add an explicit Use IDE font size option.
- Keep inherited values readable, show Inherited from and link Edit group to the matching group. Register Settings at IDE level, removing the current-project-only marker.
- Fit tables to their contents. Add projects through the Actions plus button with an independent, initially empty selection; use a trash icon for group deletion.
- Show [No repo!] without counters outside Git repositories.
- Refresh prepared icon previews while dialogs are open; reuse bounded group outline and text-icon geometry.
- Preserve concurrent icon/color edits, handle file changes, improve import/export recovery and preserve exact saved highlight values.
- Refresh the README and current Settings/Git/group screenshots while preserving the approved icon-library artwork.

## 0.3.107 — 2026-10-24

Changes since the published 0.3.99 release:

- Keep group boundaries, icons, spacing and separators fixed while reordering tabs inside a group.
- Preview a split only after leaving the original group bounds, and restore the group when the tab returns before release.
- Limit the dragged background to the visible tab shape; keep empty slots below tab content and drag margins transparent.
- Draw opaque tab and group bodies above the IDE project gradient, including fill insets, while preserving the gradient outside their contours.
- Add **Active tab highlight** and **Inactive tab dimming** in Tab layout, both with a 0–100% range and defaults of 25% and 75% respectively.
- Restore the original preset selection after undoing all manual appearance edits.
- Expand the 100+ icon library with characters, food, science, games, art, weather and travel, retaining source attribution and English/Russian search terms.

## 0.3.99 — 2026-10-23

Changes since the published 0.3.85 release:

- Combine icon, outline and fill controls in responsive **Style** sections; use compact segmented choices and pixel inputs.
- Keep **Save as new preset** and **Update preset** beside the three-section switcher, and clarify **Manage user presets…**.
- Keep active tabs lighter with fills and group styling; compact Tab layout and Group layout.
- Preserve concurrent settings edits when applying unrelated changes; report conflicts without discarding the draft.
- Normalize imported preset/category names before conflict resolution and keep category names within import limits.
- Recover tab decoration after temporary refresh errors and release cancelled icon reads so they can be retried.
- Keep Git counts updating during continuous file events; refresh affected icons without reloading other projects' artwork.
- Support style export without atomic file moves, preserve newer external icon changes on rollback, and recover unknown saved appearance values.
- Preserve emoji when duplicating presets and display user-provided names literally.
- Refresh the English README, installation flow, theme gallery and Settings screenshot. The gallery has four three-tab examples and a consistent seven-row editor crop.

## 0.3.86 — Local snapshot, 2026-10-21

- Keep active tabs lighter after applying fills, including both group variants and Preview.
- Compact Tab layout into an Info row and paired controls; size the settings block to the selected page.
- Refresh all ten theme examples and the appearance/Git screenshot slides from the installed build.
- Built and installed locally; not published.

## 0.3.85 — 2026-10-21

Changes since the published 0.3.62 release:

- Add Shared background and Joined tabs group variants with shared icons, internal dividers, dimming, spacing and boundary pipes.
- Inherit regular tab appearance and icon size in both variants; remove duplicate group appearance overrides.
- Place groups first on merge; keep shared decoration hidden after a group is split until Arrange Tabs by Group restores it.
- Move presets above Preview and add selectable three/five-tab samples across eight IDE themes.
- Correct Light preview tab surfaces and foregrounds while retaining its dark toolbar and light editor.
- Refresh the README with ten real Preview examples in one column, showing a selection from hundreds of appearance combinations.

## 0.3.84 — Development snapshot

- Switch the settings preview between three and five sample project tabs without changing real windows, presets or Apply.
- Use feature/WASD-3245 for Backend. The five-tab sample adds MobileApp on hotfix/hotfix-067 to the group and a separate pet_projects on B2B-SaaS-03.
- Keep preview selection when possible and update group decoration, toolbar branch, project count and accessibility descriptions for the selected sample set.

## 0.3.83 — Development snapshot

- Always inherit regular tab appearance and icon size in both group variants.
- Remove matching switches and duplicate group fill, roundness, contrast, active-card color, frame and icon-size controls, including stored overrides and rendering branches.
- Keep group-specific card inset, inactive dimming, dividers, shared-icon visibility, member-icon hiding, spacing and pipes.
- Allow the shared Icon → Size control when only the group icon is enabled; ignore obsolete fields in older settings and portable styles.

## 0.3.82 — Development snapshot

- Match both group variants to regular tab height, content spacing, theme corners, outline style/thickness, fill inset/intensity and native active/hover colors.
- Use Tab color for the shared fill and Outline color for the outer contour, with consistent Automatic colors.
- Link the shared icon size to tab icons; retain custom appearance and icon size when matching is disabled.
- Share outline geometry and rendering between grouped tabs, ordinary tabs and the interactive preview.

## 0.3.81 — Development snapshot

- Keep Shared background and Joined tabs with independently saved background intensity, roundness, active-tab contrast and inactive-group dimming.
- Add active-card inset and color to Shared background; add divider visibility, intensity, height, active-neighbor hiding, and an optional thin capsule frame to Joined tabs.
- Make preview project tabs selectable without changing real windows or saved settings.
- Preserve options in Custom, presets and portable styles; migrate Classic and Badge selections to Shared background.

## 0.3.80 — Development snapshot

- Add Shared background, Group badge and Joined tabs alongside Classic outline in **Group layout → Group highlight → Variant**.
- Give shared surfaces a distinct active tab; show group names and open tab counts in badges, with optional icons and adaptive shortening.
- Keep Preview and native tabs consistent. Save the variant in Custom, presets and portable styles; existing settings retain Classic.
- Preserve individual appearance outside groups and restore it when a group is split or highlighting is disabled.

## 0.3.79 — Development snapshot

- Remove the experimental Soft group outline and the Classic / Soft selector.
- Keep the original geometry, saved 1–6 px thickness, constant opacity and fixed 3 px gap.
- Remove the obsolete style field from settings and portable styles; older data keeps the remaining values.

## 0.3.78 — Development snapshot

- Add a **Classic / Soft** group outline style selector. Existing settings keep Classic and their original thickness.
- Give Soft a 1.5 px line, 8 px corners, and 80% opacity for the selected tab's group versus 35% for other groups.
- Keep the fixed 3 px gap and independent group icons, tab outlines and fills. Preserve the Classic thickness while Soft is selected.
- Save the style in Custom, presets and portable styles; repaint the group accent when the selected tab changes.

## 0.3.77 — Development snapshot

- Fix the group outline gap at 3 px and remove its setting; ignore legacy gap values in saved styles.
- Add **Hide tab icons inside the group** while a shared group icon is visible. Ungrouped tabs keep their global icon setting; member icons return if the group icon disappears.
- Save the new option in Custom, presets and portable styles, with matching native and Preview behavior.
- Move presets above Preview and separate Group layout into icon, outline, and spacing/separator sections that stack in narrow windows.

## 0.3.76 — Development snapshot

- Replace the shared bottom outline with a top line that wraps around the visible group icon.
- Preserve saved colors, thickness, gap and split-group behavior; use the same renderer in native tabs and Preview.
- Allow room for thick icon wraps and remove empty icon slots and misplaced pipes on narrow tabs.

## 0.3.75 — Development snapshot

- Hide all shared decoration and spacing of a split group, including its icon slot, outline track, pipes and extra icon height.
- Restore decoration only after a successful explicit Arrange Tabs by Group; preserve the hidden state across manual moves, settings, automatic merges and IDE restarts.
- Keep individual tabs' inherited icons and colors, and preserve other intact groups' appearance.

## 0.3.74 — Development snapshot

- Hide a split group's icon together with its outline and pipes; Arrange Tabs by Group restores it before the first tab.
- Remove the hidden icon's slot and extra height, preserving its saved setting and individual tab icons.

## 0.3.73 — Development snapshot

- Hide all pipes touching a split group until Arrange Tabs by Group restores contiguous members, matching the group outline.
- Remove pipe-only padding while its pipe is hidden, preserving explicit Group spacing and fragment icons.
- Resolve neutral separator colors consistently in native tabs and Preview, including separately selected preview themes.

## 0.3.72 — Development snapshot

- Add optional group pipes with 1–6 px thickness, 20–100% tab height and neutral/group color.
- Show the group's own icon before its tabs, with independent 20/24/28 px sizing.
- Enable group icons and tab icons separately or together; repeat the group icon for each contiguous fragment after manual splitting.
- Preview and store the new controls in Custom, named presets and portable styles; keep them off by default.

## 0.3.71 — Development snapshot

- Remove the group Corner radius slider and use fixed 10 px corners.
- Extend the straight sides halfway up the actual tab height, independently of the radius and bottom gap.
- Preserve 2 px outer side clearance; ignore obsolete saved radius values without changing other appearance settings.

## 0.3.70 — Development snapshot

- Default group corners to half the actual tab height, with manual 0–30 px radii capped at half the height.
- Keep 2 px clearance outside the first and last tab edges without changing the bottom gap.
- Share the same geometry between native tabs and Preview; preserve explicitly saved manual radii.

## 0.3.69 — Development snapshot

- Keep the straight group underline at the configured gap from the tab bottom, without adding the corner radius.
- Curve the outer ends upward and reserve only gap plus thickness below the tabs in both the IDE and Preview.

## 0.3.68 — Development snapshot

- Add **Outline gap** and **Corner radius** to Group layout, both from 0 to 12 px.
- Support a straight group underline at zero radius, including 1 px thickness with no gap.
- Preserve the new controls in built-in preset switches, Custom, user presets and portable styles.

## 0.3.67 — Development snapshot

- Draw group outlines on a separate track below the tabs, with independent 1–6 px thickness.
- Keep individual tab outlines and fills inside groups; built-in tab presets preserve Group layout settings.
- Fix preview membership: Frontend and Backend form a group; Tools is ungrouped and follows tab outline thickness.
- Cache group boundaries and outline geometry, reuse strokes and reduce redundant layout requests.

## 0.3.66 — Development snapshot

- Fix the doubled selected-tab background after a group when the IDE window loses focus.
- Preserve named theme colors for opaque backgrounds and zero-intensity fills, keeping native Islands gradient and inactive-window rendering.

## 0.3.65 — Development snapshot

- Add **Group layout** after Tab layout with 0–32 px spacing between groups.
- Optionally replace individual group-member outlines with one colored bottom line, rounded only at the group's outer ends.
- Hide the entire line when members are split; **Arrange Tabs by Group** restores it with the same rules as merge.
- Include group layout in Settings Preview, saved presets and portable style bundles; preserve the existing look by default.

## 0.3.64 — Development snapshot

- Place all saved groups together on the left after merging windows, in group-table order.
- Use **Arrange Tabs by Group** in the project-tab context menu to reapply the same rules.
- Remove the Group tabs switch; existing groups no longer depend on its legacy value.
- Show member counts in the Projects column and preserve fixed positions within each group.
- Copy native menu actions without the SDK 262 getChildren(null) error.

## 0.3.63 — Development snapshot

- Rename the project window-tab context command to **Разложить по группам** (Arrange Tabs by Group).
- Keep it directly beside standard tab actions and reconnect it after IDE popup replacement.

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

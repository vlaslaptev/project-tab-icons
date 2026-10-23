# Architecture

## Observe, decorate, restore

`ProjectTabIconsStartupActivity` connects the application service when a project
opens. The service coalesces window, theme, icon and appearance changes onto the
UI thread. It observes the relevant frame/tab components instead of walking the
editor and tool-window trees.

Each macOS merged window has its own representation of the project tab strip.
The decorator resolves the frame stored on a tab to the corresponding project,
so icons follow projects when windows are switched.

Original presentation values are tracked by identity. Disabling customization
removes observers and restores values still owned by the plugin. A compatibility
failure stops decoration and records a message in the IDE's local log.

## Project identity

The IDE's native project-icon provider supplies custom artwork. The plugin draws
generated initials with controlled padding and sizing. Native SVG/PNG artwork is
requested at its display size and pixel density.

`ProjectIconStore` reads project icon variants and applies explicit icon assignments.
It checks snapshots before writing, uses atomic file replacement and attempts to
restore already-written icons if a batch fails. Newer external changes are not
overwritten by rollback.

## Settings

Appearance is application state, persisted through the IntelliJ settings mechanism.
The settings page manages a draft, a preview and a table of open projects. Applying
appearance alone does not install or rewrite project SVG icons.

The last Custom appearance has separate application storage. Preset browsing updates
only the settings draft; Apply saves both the active appearance and Custom memory.
Named user presets have separate application storage in `projectTabIconsPresets.xml`.
The page drafts their creation, renaming and deletion until Apply; Cancel leaves
storage unchanged. Up to 10 presets hold normalized appearance snapshots, excluding
the enable switch and project assignments. A stable ID in the active appearance
restores the selected user radio button. Manual edits clear that ID and switch to
Custom while preserving the named snapshot. Names are unique, including against
built-in preset names, and limited to 32 Unicode characters.
Pending-change markers compare each field or project value with its saved baseline.
SVG import validation maps file and XML failures to actionable messages in the dialog.

`IconSetLibrary` stores shared imported categories in `projectTabIconSets.xml`, with
independent SVG copies encoded as Base64. Folder traversal and validation run in
the background with depth, file-count and size bounds. The picker and settings
page retain additions as a draft; only Apply writes application state. Cancel
does not change the library. Original SVG colors are preserved.

Text icons use AWT glyph outlines exported as SVG paths, with text, style, font
and color metadata. Reloading an icon restores its editor; changing its color
regenerates its paths. Ready-made paths render without depending on the active
machine's font. Both light and dark project variants use the chosen SVG.

The icon catalog and its licenses are bundled resources. Exported generated SVGs
carry the selected icon's attribution. Public asset provenance is retained in
`icon-provenance.json` and [third-party notices](../THIRD_PARTY_NOTICES.md).

## Optional Git integration

A plugin-owned branch-provider extension point isolates the optional Git4Idea
integration. Branch captions come from the IDE's repository model. Saved tracked
line counts use a local Git diff on a background worker, with debouncing,
cancellation and a timeout. No fetch is performed.

## Internal APIs

Window-tab decoration, project frame lookup, icon wrappers and project icon storage
depend on internal APIs in the target platform. The strict IDE build range is an
intentional part of the implementation. See [compatibility](COMPATIBILITY.md).

## Group appearance and window ordering

`ProjectGroups` persists group icons, independent colors, memberships and member positions in local application state. The settings page holds a draft;
Apply saves it. `GroupAppearanceIcons` prepares inherited SVGs off the UI thread
with bounded validation and caching. Effective group appearance overlays personal
project styling, so ungrouping does not need to rewrite project icon files.

`ProjectTabGroupOrdering` observes merged strip membership. A changed order alone
never triggers sorting. New merges are coalesced. All groups go on the left in table order; ungrouped tabs
retain relative order on the right. Fixed positions reserve slots within a group
and Auto members retain relative order. Arrange Tabs by Group uses the same
calculation with the current live strip and latest persisted group state. The legacy
groupTabs field remains readable for XML compatibility but no longer gates ordering.
Native menu actions are copied through DefaultActionGroup.childActionsOrStubs;
getChildren(null) is not used.

On the AppKit main thread, the implementation validates the native window set,
reorders it within its tab group and preserves the selected window. It then updates
each live Swing strip through the internal SDK 262 `reorderTabs(Map)` method,
including indexes used by the IDE's drag-and-drop support. Disposal cancels pending
work and restores the original context-menu groups. These internal APIs are part of
the narrow compatibility scope, rather than a promise of cross-version stability.

The table block follows the Settings viewport height. The member list retains a
minimum height so layout cannot collapse it in a short window; long content scrolls.

## Group layout

Since 0.3.68, Outline gap and Corner radius are independently configurable from
0 to 12 px, with defaults of 2 and 6 for older XML/JSON. A zero radius draws a
straight underline, including at 1 px thickness with no gap. Before 0.3.69, track
height included thickness, gap and radius; the geometry cache accounts for scaled
radius/bounds.
Since 0.3.69, reserved track height is only thickness plus gap, scaled separately.
The path starts one radius above that track so its ends curve upward; the straight
stroke starts exactly at tab bottom plus gap. Increasing radius no longer moves
the underline away from the tabs. Both controls are included in settings drafts,
Custom, user presets and JSON.
Built-in tab presets preserve all five Group layout parameters.

Since 0.3.66, opaque colors and zero-opacity blends retain the original Color
object. Islands uses `JBColor.name` to recognize gradient backgrounds and keep
background clearing opaque when a window is inactive. Converting an opaque named
color into a plain RGB Color lost that information and left the old selected-tab
shape visible behind the inset body. Only actual color mixtures create a new RGB
value. Since 0.3.107, background clearing still preserves that native behavior,
while tab and group bodies use the original Graphics2D delegate with an opaque
base. The inactive-window wrapper is bypassed only for those bodies; caller
graphics, text, icons and focus behavior are unchanged. Fill intensity blends
against the base rather than exposing the project gradient through the body.

The fourth appearance card stores group spacing and an optional shared outline in
`TabAppearanceState`, including preset drafts and JSON style bundles. Missing fields
preserve zero spacing and no group outline. `ProjectTabGroupLayout` splits extra
visual spacing between adjacent TabLabel insets; native tab slots, hit areas and
drop indexes stay intact. The border reconstructs the strip background in these
margins and the icon layout centers content within the remaining body.

A disposable glass-pane painter draws a rounded underline using the group's
outline color or icon color. Since 0.3.67, it has a separate track below the tab
bodies, a 2 px gap and independent 1–6 px thickness. The container layout reserves
space outside WindowTabsComponent and the painter is anchored to that container;
group decoration does not require repainting every tab's native background.
Individual tab outlines and
fills remain visible. The track stays reserved when a group is split to avoid
height changes during dragging. Built-in tab presets preserve Group layout;
user presets and JSON bundles still store the complete appearance.

Group ranges and neighboring boundaries are cached by TabInfo and rebuilt on
order/membership changes, refresh and relevant scale/settings changes. Tab move,
removal and selection events keep the snapshot current; unchanged selection
events skip layout. A split hides the entire group's line. Only open members in
that strip count, and merge/Arrange explicitly refresh every synchronized strip.
Group and tab outline paths/strokes are reused until geometry or style changes;
group painting shares one Graphics2D copy and border updates coalesce layout
requests.

Since 0.3.105, a drag session freezes the source group's geometry and defers the
final split state until release. Leaving the original bounds previews the split;
returning before release restores the grouped appearance. Since 0.3.106, source
drag margins stay transparent and empty slots are painted below tab content.
The native painter is restored when the session ends. The 0.3.107 body renderer
adds no image buffers, per-pixel work or extra graphics copies. Local rendering
measurements and their limits are recorded in [compatibility](COMPATIBILITY.md).

Disposal restores native borders/layouts and removes the painter/listener.
Settings Preview uses the same group path/track geometry; Frontend and Backend
form a group and Tools is ungrouped. Earlier 0.3.65 spacing/toggle checks passed;
the 0.3.67 changes were subsequently checked in installed 0.3.68: Tools and group
thickness at 1/6 px, Filled with independent tab/group outlines, and gap/radius at
0/12 px in Preview and native tabs. Installed JARs match the ZIP and original
settings were restored byte for byte. Manual drag splitting, the context command
and inactive-window transitions were skipped due to native-tab input limitations.

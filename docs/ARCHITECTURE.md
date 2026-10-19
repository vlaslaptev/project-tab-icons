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

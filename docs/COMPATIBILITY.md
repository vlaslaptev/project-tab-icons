# Compatibility

| Area | Scope for 0.3.55 |
| --- | --- |
| Operating system | macOS |
| IDE | IntelliJ IDEA 2026.2.3 and later 2026.2 updates within the declared build range |
| Since build | `262.10968.63` |
| Strict upper bound | `262.*` |
| UI | Local IDE project windows merged into macOS window tabs |
| Optional integration | Git4Idea for branch and changed-line captions |
| Installation | Unsigned ZIP through Install Plugin from Disk |
| Distribution | GitHub; no Marketplace publication is claimed |

This plugin decorates **project window tabs**. It does not decorate editor file
tabs. Windows, Linux, remote-development Split Mode and the 2026.3 platform branch
are outside the declared support scope. Other IntelliJ-based products have not
been established as supported by this release.

## Why the range is narrow

The platform does not expose a suitable public extension point for this window-tab
decoration. The implementation uses internal window-tab, frame, project-icon and
project-storage APIs. Those interfaces can change between IDE versions.

The descriptor keeps a strict `262.*` upper bound. Updating it is a compatibility
change, not a way to make an unreviewed build install on a newer IDE.

## Evidence and limits

The 0.3.55 release artifact was built and installed locally in IDEA 2026.2.3.
The installed version and both installed JAR hashes match the release ZIP.
Recorded checks cover shared folder imports, nested SVGs, search, original colors,
invalid files, duplicates, Apply/Cancel and persistence after restarting IDEA with
the source folder moved. Text-icon checks cover DB, QA, API and Greek letters,
all four styles and three font families, HEX validation, editing after reopening,
recoloring from the project row and persistence after restart.

The preceding UI checks cover user-preset creation, selection, name validation,
the 10-preset limit, Apply/Cancel, restart persistence and wrapping in a narrow
window. Pending-change markers were checked across Icon, Color and Tab layout,
disabled controls and all four project-row values, including Apply and Revert.
Earlier checks cover fill intensity and the Filled preset at 12%, Small 20 px and
a 3 px inset. Original appearance, theme and project icon files were restored.

User-preset Rename/Delete menu interactions were deferred at the user's request.
A manually confirmed focus-switching cycle and every transient icon-loading frame
were not individually reproduced.

That evidence is limited to the recorded local environment. It does not establish
every theme, UI scale, macOS release or IDE patch combination. No standalone JVM
tests, linters or Plugin Verifier run is claimed. The artifact is unsigned.

The packaged release metadata and SHA-256 are prepared alongside the GitHub release
assets. See [installation](INSTALLATION.md) and [architecture](ARCHITECTURE.md).

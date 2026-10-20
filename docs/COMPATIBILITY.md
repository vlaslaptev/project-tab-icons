# Compatibility

| Area | Scope for 0.3.62 |
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

The 0.3.62 release ZIP was built and installed in IDEA 2026.2.3. Both installed
JARs match the ZIP byte for byte. UI checks cover the two table tabs, inline group
creation/editing, member paths, fixed positions, group-order arrows, persistence
after restart and ordering of two groups on Merge All Project Windows. The final
large-window editor displays all nine available projects and Save/Cancel.

Earlier 0.3.61 checks cover a group's icon and independent colors, inheritance by
existing and newly assigned members, changes to the shared appearance, and restoration
of personal project styling after ungrouping. The original local group, presets and
appearance files were restored byte for byte after the 0.3.62 checks.

The right-click and drag paths could not be completed reliably through UI automation.
Manual rearrangement, Restore Group Tab Order and the final short-window minimum-height
fix are statically inspected but not claimed as fully exercised in this release.
Quick actions, portable style bundles and preset-manager operations are included;
they were not all re-exercised during the final installation pass. Earlier recorded
checks cover folder imports, text icons, presets, Apply/Cancel and restart persistence.

That evidence is limited to the recorded local environment. It does not establish
every theme, UI scale, macOS release or IDE patch combination. No standalone JVM
tests, linters or Plugin Verifier run is claimed. The artifact is unsigned.

The packaged release metadata and SHA-256 are prepared alongside the GitHub release
assets. See [installation](INSTALLATION.md) and [architecture](ARCHITECTURE.md).

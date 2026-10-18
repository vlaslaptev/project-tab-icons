# Compatibility

| Area | Scope for 0.3.49 |
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

The 0.3.49 release artifact was built and installed locally in IDEA 2026.2.3.
The recorded checks include the installed plugin version, matching source/artifact
hashes, icon reloads at 20, 24 and 28 px, and a dimmed window without added icon
backgrounds. Saved appearance, the IDE theme and project icon files were preserved.
A manually confirmed focus-switching cycle was deferred; every transient loading
frame and arbitrary imported artwork were not individually reproduced.

That evidence is limited to the recorded local environment. It does not establish
every theme, UI scale, macOS release or IDE patch combination. No standalone JVM
tests, linters or Plugin Verifier run is claimed. The artifact is unsigned.

The packaged release metadata and SHA-256 are prepared alongside the GitHub release
assets. See [installation](INSTALLATION.md) and [architecture](ARCHITECTURE.md).

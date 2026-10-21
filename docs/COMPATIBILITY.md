# Compatibility

| Area | Scope for 0.3.85 |
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

### Release 0.3.85

The 0.3.85 ZIP was built and installed in IntelliJ IDEA 2026.2.3 / 262.10968.63.
Installed JAR hashes match the packaged ZIP. Visual checks cover the Light preview
palette, three/five-tab samples, and the README gallery across all eight preview
themes. The High Contrast example uses larger text and icons on the right.
Earlier local 0.3.83 checks cover both group variants inheriting tab appearance
and icon size. Original settings were restored after the gallery captures.

The release keeps the same compatibility range. Manual drag-and-Arrange interactions,
quick actions, style import/export and preset-manager operations were not all repeated
in the final 0.3.85 pass. No standalone JVM tests, linters or Plugin Verifier were run.
The recorded checks do not establish every theme, display scale or macOS/IDE patch
combination. The ZIP is unsigned.

### Earlier 0.3.62 checks

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


## Local 0.3.69 follow-up

Installed in IDEA 262.10968.63; installed JARs match the packaged ZIP. The compact
group underline was checked after merging nine windows. Preview at 6/12 px corner
radius kept the same straight-line height with a 2 px gap. Four settings files
remain byte-identical and no new plugin errors were found. DnD, Arrange and focus
transitions were not rechecked; no standalone JVM verification tools were run.

## Local 0.3.68 follow-up

Packaged and installed in IDEA 262.10968.63; installed JARs match the ZIP. Checks
covered Tools and group thickness at 1/6 px, Filled with independent tab/group
outlines, and gap/radius at 0/12 px in Preview and native tabs after a nine-window
merge. All four plugin settings files were restored byte for byte, preserving
the user's pending appearance edits saved before installation. No new plugin
errors were found. Manual DnD, Arrange and inactive-window transitions were skipped
because native input is not reliably automated. No standalone JVM tests, linters
or Plugin Verifier were run; no timing benchmark is claimed.

## Local 0.3.67 follow-up

Compiled and packaged against SDK 262.10968.63. Group outlines now have a separate
track and independent thickness; tab outlines/fills remain independent. Static
inspection covers gutter/inset sizing, shared IDE/Preview geometry, cached boundary
invalidation on order/removal/selection/scale changes, XML/JSON defaults and preset
preservation. No installation, new runtime QA, timing benchmark, standalone JVM
tests, linters or Plugin Verifier run is claimed for this version.

## Local 0.3.65 follow-up

Group layout was compiled and packaged against the same SDK 262.10968.63.
Static API inspection covers `IdeGlassPaneUtil.installPainter`, `AbstractPainter`,
`TabsListener.tabsMoved`, live TabLabel bounds and disposal. The update was
installed and the installed JARs match the ZIP. Spacing at 0, 24 and 32 px,
the continuous outline after merging nine windows and restoration of individual
outlines when disabling it were visually checked. Original settings were restored
byte for byte. Manual drag splitting and restoration through Arrange Tabs by Group
remain unverified: native-tab input could not be automated and an alternative
input method was declined. No standalone JVM tests, linters or Plugin Verifier
were run.

## Local 0.3.64 follow-up

Local 0.3.64 was packaged and installed in the same IDEA build. The installed JARs
match the ZIP. Checks covered restart and merge of nine windows with one existing
three-member group, plus two-group table ordering and a fixed first member on merge.
All grouped tabs were placed on the left. Settings Apply preserved the current tab
order. Four plugin settings files were restored byte for byte after the checks.
The legacy grouping flag no longer gates ordering. Native popup actions now use
childActionsOrStubs instead of getChildren(null); no plugin stack traces were found
in the final restarted session.

The English context action and its shared ordering calculation were inspected
statically. Automated right-click and drag interaction with the macOS project tab
strip could not be completed reliably, so clicking Arrange Tabs by Group after a
manual rearrangement remains unverified at runtime. This follow-up is not a new
published release.

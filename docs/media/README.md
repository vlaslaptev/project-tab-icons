# GitHub media

The gallery combines original illustrations and **real Settings preview captures**
from Project Tab Icons 0.3.46 running in IntelliJ IDEA 2026.2.3 on macOS.
Frontend, Backend, Tools, example editor text, branch names and line counts are
built-in demonstration data from the plugin's preview.

| Asset | Size | Purpose |
| --- | --- | --- |
| `hero-dark.png` / `hero-light.png` | 1600 × 900 | README cover, selected by preferred color scheme |
| `appearance-presets.png` | 1600 × 1000 | Real Settings preview: Filled preset and Color controls |
| `icon-library.png` | 1600 × 1000 | A selection from 100+ bundled SVG icons |
| `git-context.png` | 1600 × 1000 | Real Settings preview: Git preset, compact branches, line counts and Tab layout controls |
| `social-preview.png` | 1280 × 640 | Repository social preview |
| `logo-512.png` | 512 × 512 | Transparent export of the existing plugin logo |

Corresponding SVG files are editable slide sources. The two screenshot slides embed
the original PNG crops, preserving the UI pixels and text. `manifest.json` records
dimensions, asset types and the source plugin version. `preview.html` displays the full gallery locally.
The logo concept is preserved: overlapping colored project windows and a shark.

The captures were taken on 16 October 2026 using the **Current · Islands Darcula**
preview theme. [Capture provenance](captures/provenance.json) records their hashes,
dimensions and selected controls. [Appearance crop](captures/appearance-settings.png)
and [Git crop](captures/git-settings.png) contain only Preview, presets and appearance
controls. Settings navigation, window title and the real open-project table are
excluded. UI labels and values were not redrawn or retouched. The slides add a
background, frame, headline and caption outside the captures.

The Filled and Git presets were selected in a temporary Settings draft and discarded
with Cancel after capture. The IDE's saved appearance was restored. The two slides
show those specific settings; other themes and combinations can look different.
The covers, icon-library slide and social preview remain illustrations. The icon
library shows a fixed selection of bundled SVG resources under the 100+ label.
Adding icons to the plugin does not require rebuilding this slide or updating its count.
Code and library attribution are in
[third-party notices](../../THIRD_PARTY_NOTICES.md).

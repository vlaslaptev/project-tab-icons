# GitHub media

The main README gallery, `appearance-gallery.png`, contains **ten real Preview
frames** from installed **0.3.97**, captured on **23 October 2026**. All eight IDE
themes are represented, with six dark and four light examples and three/five tabs.
Six frames have five tabs and four have three tabs. The first three appearance
setups are preserved; High Contrast has its icons on the right.
The slide is 1411 × 3380 px in one column with 2 px outer margins. All frames fill
the available width; captions contain only theme names. The README supplies the
gallery heading, so the image has no duplicate title.

Every editor fragment contains seven rows. Each frame ends after the seventh
line's descender, followed by the native 8 px window bottom. Five-tab windows are
1407 × 249–250 logical pixels. Three-tab windows are physically narrower, at
1110 × 245 logical pixels, and are scaled uniformly to 1407 × 311 px. Their
displayed tabs are about 38 px high with 15 px text, compared with 34–35 px tabs
and 14 px text in five-tab frames. Bold, rounded lower outlines,
maximum tab heights and strong fills are excluded; Filled uses 12% fill.

The installed plugin's own WindowTabsPreview component was hosted in visible native
Swing windows at these dimensions. The capture host appends five gallery-only
editor rows to the renderer's two built-in rows, using the same native font,
line spacing and theme palette. Production plugin code is unchanged. The native
host contents were exported at Retina 2× scale without a mouse pointer, then
uniformly reduced to the display width with integer pixel rounding. Controls,
icons and UI colors were not redrawn or retouched.
[Gallery metadata](appearance-gallery.json) records dimensions and hashes.

| Asset | Source and purpose |
| --- | --- |
| `appearance-gallery.png` | 0.3.97: ten Preview frames across eight themes, four three-tab examples, all ending after editor row seven |
| `appearance-presets.png` | 0.3.120: shared preset rows, three top-level sections, compact Preview and Style with exact percentage input |
| `git-context.png` | 0.3.120: compact Preview, Content and Selection on the left, Size & position on the right |
| `captures/groups-settings.png` | 0.3.120: shared presets, Project groups navigation, table header and Add group only |
| `icon-library.png` | Unchanged approved illustration with a fixed selection of 100+ bundled SVGs |
| `hero-dark.png` / `hero-light.png` | Preserved illustrated covers, not screenshots |
| `social-preview.png` | Preserved repository social preview |
| `logo-512.png` | Transparent export of the existing logo |

The three Settings crops were recaptured from installed **0.3.120** in IntelliJ IDEA
2026.2.3 on **25 October 2026**, using the current Islands Darcula theme. Appearance
shows the global preset header, navigation, compact Preview and Style. Git shows
Preview and the current Tab layout labels and numeric controls. Groups includes
the global preset header and ends after Add group, before any saved group rows;
it does not imply an empty group list.

Frontend, Backend and Tools, branches and counts are built-in demo fixtures.
`git_with_icon` and `group_git` are generic appearance preset labels. Window titles,
the IDE navigation tree, working project names, paths and saved group rows are
outside the crops. The pointer was moved outside the crop using native UI before
capture. No UI pixels or labels were redrawn, blurred or retouched. The supplied
native screenshot JPEG bytes were cropped and encoded as PNG; the two slides add
an illustrated frame and captions outside the UI. Four plugin settings files and
laf.xml remained byte-identical after capture.
[Capture provenance](captures/provenance.json) records source hashes, versions,
dates, dimensions and exact crop rectangles. The ten-example themes gallery stays
at its documented 0.3.97 capture version. The icon-library SVG/PNG remains byte-identical.

SVG files are the illustration and Settings slide sources; the themes gallery uses
the frame sizes and source hashes in its JSON metadata. `manifest.json` describes the generated slides;
`preview.html` displays them locally. The icon-library composition remains fixed.
Attribution is in [third-party notices](../../THIRD_PARTY_NOTICES.md).

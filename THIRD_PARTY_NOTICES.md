# Third-party notices

The plugin code and original compositions use the project's [MIT license](LICENSE).
Bundled artwork retains its own notices and provenance.

| Resource | Origin | License / retained notice |
| --- | --- | --- |
| Filled icons | [Phosphor Icons](https://github.com/phosphor-icons/core) | `licenses/Phosphor-MIT.txt` |
| Mushroom and crutch artwork | [Tabler Icons](https://github.com/tabler/tabler-icons) | `licenses/Tabler-MIT.txt` |
| Plain and smiling poop artwork | [Microsoft Fluent Emoji](https://github.com/microsoft/fluentui-emoji) | `licenses/Microsoft-Fluent-Emoji-MIT.txt` |
| Seven language icons | [Devicon](https://github.com/devicons/devicon) | `licenses/Devicon-MIT.txt` |
| Java artwork | [SVG Repo Java](https://www.svgrepo.com/svg/71269/java) | `licenses/CC0-1.0.txt` |
| Shark artwork | [SVG Repo Shark](https://www.svgrepo.com/svg/307542/shark-danger-predator-angry) | `licenses/CC0-1.0.txt` |
| Letter and numeral outlines | [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), Bold 2.304 | `licenses/JetBrains-Mono-OFL-1.1.txt` |
| AI and Ai monograms | Original project artwork | Project MIT license |
| Cat and logo composition | Original project artwork | Project MIT license |
| Gradle Wrapper | Official Gradle source | `licenses/Gradle-Wrapper-Apache-2.0.txt` |

Icon modifications include monochrome color handling, normalized bounds and
selected shape simplifications. The shark used in the logo is adapted from the
CC0 artwork above. The detailed upstream URLs, original and generated SHA-256
values, and modifications are in `docs/icon-provenance.json`.

The Tabler mushroom preserves the filled geometry; the crutch is a simplified,
rotated adaptation of the outline crutches icon. The Fluent Emoji pair uses the
High Contrast Pile of poo artwork: one removes the face, and the other retains its
eyes and smile. Both use monochrome color handling and a normalized 24×24 viewBox.
The complete Tabler and Microsoft MIT notices accompany the bundled resources and
individual exported SVGs.

Original supplied/source artwork is retained in `assets/sources`. Generated
project SVGs include the attribution for the chosen icon. The wrapper scripts
retain their upstream notices; provenance is in `docs/wrapper-provenance.json`.

Letter and numeral icons are stored as SVG outlines and do not require installed
fonts. The original font and its OFL notice are retained in `assets/sources/jetbrains-mono`.
The gallery's icons are rendered from bundled SVG resources. The IDE supplies IntelliJ Platform and Kotlin runtime libraries;
the plugin does not include its own copies of those libraries.

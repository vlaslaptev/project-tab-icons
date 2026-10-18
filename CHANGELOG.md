# Changelog

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

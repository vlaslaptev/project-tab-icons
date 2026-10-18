# Changelog

## 0.3.52 — 2026-10-18

- Return to the last manual appearance with Custom, including after restarting IDEA.
- Save Custom memory with Apply; Cancel discards changes to that memory.
- Mark unsaved appearance fields and project icon/color values with a small dot.
- Explain SVG import errors with recovery steps and wrapped messages while preserving the previous selection.

## 0.3.51 — 2026-10-18

- Update Filled to Small 20 px icons, 12% fill intensity and a 3 px inset.
- Keep its full 1 px outline and 34 px height; saved manual appearances remain unchanged until a preset is applied.

## 0.3.50 — 2026-10-18

- Add Fill intensity in Color, from 0% transparent to 100% solid in one-percent steps.
- Update the Settings preview and color-picker sample immediately; Apply updates window tabs.
- Preserve intensity when fill is disabled. Older settings default to 50%.

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

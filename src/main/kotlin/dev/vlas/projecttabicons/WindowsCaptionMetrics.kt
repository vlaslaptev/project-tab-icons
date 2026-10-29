package dev.vlas.projecttabicons

/** Logical Windows caption dimensions shared by the merged frame and its preview. */
internal object WindowsCaptionMetrics {
    const val DRAG_AREA_WIDTH = 56
    // The live frame obtains its native inset from JBR; Preview models Windows 11 controls.
    const val PREVIEW_BUTTON_WIDTH = 46
    const val PREVIEW_CONTROLS_WIDTH = 3 * PREVIEW_BUTTON_WIDTH
}

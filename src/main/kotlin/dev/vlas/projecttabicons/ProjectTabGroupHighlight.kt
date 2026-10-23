package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D

internal data class ProjectTabGroupColors(val outline: Color, val background: Color)

internal data class ProjectTabGroupSurfaceState(
    val appearance: TabAppearanceState,
    val geometry: ProjectTabGeometry,
    val body: Rectangle2D.Double,
    val colors: ProjectTabGroupColors,
    val first: Boolean,
    val last: Boolean,
    val activeGroup: Boolean,
    val nextSelected: Boolean,
) {
    val options: GroupHighlightOptions get() = appearance.groupHighlightOptions()
}

/** Геометрия, заливка и линия общие с обычными табами; различается форма активной области. */
internal object ProjectTabGroupSurface {
    private val outline = ProjectTabOutline()

    fun paint(graphics: Graphics2D, width: Int, height: Int, state: ProjectTabGroupSurfaceState,
              colors: ProjectTabSurfaceColors, selected: Boolean, hovered: Boolean = false) {
        val g = graphics.create() as Graphics2D
        try {
            g.clipRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val appearance = state.appearance
            val options = state.options
            val geometry = state.geometry
            val bounds = geometry.bounds
            val strength = if (state.activeGroup) 1f else 1f - options.inactiveDimming / 100f
            val fill = state.colors.background.takeIf { appearance.showTabBackground }
            val intensity = (appearance.tabFillIntensity * strength).toInt()
            val fillInset = appearance.tabFillInset
            val fillGeometry = geometry.inset(JBUI.scale(fillInset).toDouble())
            val idleColors = colors.copy(background = colors.idleBackground, state = ProjectTabSurfaceState.IDLE)
            val idlePaint = ProjectTabSurface.resolve(idleColors, fill, intensity)
            val idle = idlePaint.background
            val base = idlePaint.fill ?: idle
            ProjectTabSurface.paint(g, width, height, geometry, idleColors, fill, fillInset, intensity)
            if (bounds.width <= 0 || bounds.height <= 0) return
            if (selected || hovered) {
                val activeColors = colors.copy(state = ProjectTabSurfaceState.of(selected, hovered))
                val activePaint = ProjectTabSurface.resolve(activeColors, fill, intensity)
                if (appearance.groupHighlightStyle == GroupHighlightStyle.BACKGROUND) {
                    // Отступ карточки не меняет размер таба, расположение текста и область клика.
                    val cardBounds = Rectangle2D.Double(state.body.x, bounds.y,
                        state.body.width, bounds.height)
                    val card = ProjectTabGeometry(cardBounds,
                        minOf(geometry.radius, cardBounds.width / 2, cardBounds.height / 2))
                        .inset(JBUI.scale(options.cardInset).toDouble())
                    val cardGraphics = g.create() as Graphics2D
                    try {
                        cardGraphics.clip(geometry.shape())
                        cardGraphics.color = activePaint.background
                        cardGraphics.fill(card.shape())
                        if (activePaint.fill != null) {
                            cardGraphics.clip(fillGeometry.shape())
                            cardGraphics.color = activePaint.fill
                            cardGraphics.fill(card.shape())
                        }
                    } finally { cardGraphics.dispose() }
                } else {
                    val segment = g.create() as Graphics2D
                    try {
                        val left = if (state.first) bounds.x else state.body.x
                        val right = if (state.last) bounds.maxX else state.body.maxX
                        segment.clip(Rectangle2D.Double(left, bounds.y, (right - left).coerceAtLeast(0.0), bounds.height))
                        ProjectTabSurface.paint(segment, width, height, geometry,
                            activeColors, fill, fillInset, intensity)
                    } finally { segment.dispose() }
                }
            }
            if (appearance.groupHighlightStyle == GroupHighlightStyle.JOINED && !state.last &&
                options.showDividers && options.dividerIntensity > 0 &&
                !(options.hideDividersNearActive && (selected || state.nextSelected))) {
                val stroke = JBUI.scale(1).toFloat()
                val length = (bounds.height * options.dividerHeight / 100 - stroke).coerceAtLeast(0.0)
                val y = bounds.centerY - length / 2
                g.stroke = BasicStroke(stroke)
                g.color = ProjectTabSurface.blend(base, state.colors.outline, options.dividerIntensity / 100f * strength)
                g.draw(Line2D.Double(state.body.maxX - stroke / 2, y, state.body.maxX - stroke / 2, y + length))
            }
            if (appearance.showOutline) {
                outline.paint(g, geometry, ProjectTabSurface.blend(idle, state.colors.outline, strength),
                    appearance.outlineThickness, appearance.outlineStyle)
            }
        } finally { g.dispose() }
    }
}

package dev.vlas.projecttabicons

import com.intellij.util.ui.UIUtil
import com.intellij.ui.tabs.TabInfo
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import javax.swing.border.Border

/** Линия — часть border самого TabLabel: закрытие, hover и перетаскивание остаются штатными. */
internal class ProjectTabBorder(
    val original: Border?,
    var projectIcon: CenteredProjectIcon?,
    var outlineColor: Color?,
    var outlineThickness: Int = DEFAULT_OUTLINE_THICKNESS,
    var outlineStyle: TabOutlineStyle = TabOutlineStyle.ROUNDED,
    var tabColor: Color? = null,
    var tabFillInset: Int = 0,
    var tabFillIntensity: Int = DEFAULT_TAB_FILL_INTENSITY,
    private val geometryOverride: ((Int, Int) -> ProjectTabGeometry)? = null,
    private val surfaceColors: ((Component) -> ProjectTabSurfaceColors?)? = ::nativeProjectTabSurfaceColors,
    var groupLayout: ProjectTabGroupLayout? = null,
    var groupTab: TabInfo? = null,
) : Border {
    var sharedGroupSurfacePreview: Boolean = false
    var hideProjectIconPreview: Boolean = false
    fun groupMargins(): ProjectTabGroupMargins = groupTab?.let { groupLayout?.margins(it) } ?: ProjectTabGroupMargins.NONE
    fun visibleProjectIcon(): CenteredProjectIcon? = projectIcon?.takeUnless {
        hideProjectIconPreview || groupTab?.let { groupLayout?.hidesTabIcon(it) } == true
    }
    private val outline = ProjectTabOutline()

    override fun getBorderInsets(component: Component): Insets {
        val source = original?.getBorderInsets(component) ?: Insets(0, 0, 0, 0)
        val edges = protectedEdges()
        val margins = groupMargins()
        if (edges.top + edges.left + edges.bottom + edges.right == 0) {
            return Insets(source.top, source.left + margins.left, source.bottom, source.right + margins.right)
        }
        // Толстая линия и полная рамка не должны перекрывать иконку, подпись и крестик.
        val fontHeight = component.getFontMetrics(component.font ?: UIUtil.getLabelFont()).height
        val iconPadding = ((projectIcon?.iconHeight ?: 0) - fontHeight).coerceAtLeast(0).let { (it + 1) / 2 }
        return Insets(source.top.coerceAtLeast(0) + edges.top + iconPadding,
            source.left.coerceAtLeast(0) + edges.left + margins.left,
            source.bottom.coerceAtLeast(0) + edges.bottom + iconPadding,
            source.right.coerceAtLeast(0) + edges.right + margins.right)
    }

    override fun isBorderOpaque(): Boolean = false

    private fun protectedEdges(): Insets = projectTabOutlineInsets(outlineColor, outlineThickness, outlineStyle)

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        if (graphics !is Graphics2D || width <= 0 || height <= 0) {
            original?.paintBorder(component, graphics, x, y, width, height)
            return
        }
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            val margins = groupMargins()
            val bodyWidth = (width - margins.left - margins.right).coerceAtLeast(0)
            val body = geometryOverride?.invoke(bodyWidth, height) ?: projectTabGeometry(bodyWidth, height)
            val geometry = if (margins.left == 0) body else body.copy(bounds = java.awt.geom.Rectangle2D.Double(
                body.bounds.x + margins.left, body.bounds.y, body.bounds.width, body.bounds.height))
            val colors = surfaceColors?.invoke(component)
            val sharedSurface = sharedGroupSurfacePreview || (colors != null &&
                groupTab?.let { groupLayout?.paintTabSurface(it, g, width, height, colors) } == true)
            if (!sharedSurface && (tabColor != null || margins.left + margins.right > 0)) {
                colors?.let {
                    ProjectTabSurface.paint(g, width, height, geometry, colors, tabColor, tabFillInset, tabFillIntensity)
                    val edges = g.create(margins.left, 0, bodyWidth, height) as Graphics2D
                    try { paintNativeProjectTabEdges(component, edges, bodyWidth, height) }
                    finally { edges.dispose() }
                }
            }
            if (!sharedSurface) original?.paintBorder(component, g, margins.left, 0, bodyWidth, height)
            visibleProjectIcon()?.let { icon ->
                val bounds = ((component as? Container)?.layout as? ProjectTabIconLayout)?.iconBounds
                if (bounds != null) {
                    // Контур ограничивает только иконку; заливка и линия не проходят через hard clip.
                    val image = g.create() as Graphics2D
                    try {
                        image.clip(geometry.shape())
                        icon.paintIcon(component, image, bounds.x, bounds.y)
                    } finally { image.dispose() }
                }
            }
            if (sharedSurface) return
            val color = outlineColor ?: return
            outline.paint(g, geometry, color, outlineThickness, outlineStyle)
        } finally {
            g.dispose()
        }
    }

}

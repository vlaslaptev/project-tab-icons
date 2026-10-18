package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
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
) : Border {
    override fun getBorderInsets(component: Component): Insets {
        val source = original?.getBorderInsets(component) ?: Insets(0, 0, 0, 0)
        val edges = protectedEdges()
        if (edges.top + edges.left + edges.bottom + edges.right == 0) {
            return Insets(source.top, source.left, source.bottom, source.right)
        }
        // Толстая линия и полная рамка не должны перекрывать иконку, подпись и крестик.
        val fontHeight = component.getFontMetrics(component.font ?: UIUtil.getLabelFont()).height
        val iconPadding = ((projectIcon?.iconHeight ?: 0) - fontHeight).coerceAtLeast(0).let { (it + 1) / 2 }
        return Insets(source.top.coerceAtLeast(0) + edges.top + iconPadding,
            source.left.coerceAtLeast(0) + edges.left,
            source.bottom.coerceAtLeast(0) + edges.bottom + iconPadding,
            source.right.coerceAtLeast(0) + edges.right)
    }

    override fun isBorderOpaque(): Boolean = false

    private fun protectedEdges(): Insets {
        if (outlineColor == null) return Insets(0, 0, 0, 0)
        val thickness = outlineThickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)
        val edge = JBUI.scale(thickness)
        return when {
            outlineStyle == TabOutlineStyle.FULL -> Insets(edge, edge, edge, edge)
            thickness > DEFAULT_OUTLINE_THICKNESS -> Insets(0, 0, edge, 0)
            else -> Insets(0, 0, 0, 0)
        }
    }

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        if (graphics !is Graphics2D || width <= 0 || height <= 0) {
            original?.paintBorder(component, graphics, x, y, width, height)
            return
        }
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            val geometry = geometryOverride?.invoke(width, height) ?: projectTabGeometry(width, height)
            tabColor?.let { color ->
                surfaceColors?.invoke(component)?.let { colors ->
                    ProjectTabSurface.paint(g, width, height, geometry, colors, color, tabFillInset, tabFillIntensity)
                    paintNativeProjectTabEdges(component, g, width, height)
                }
            }
            original?.paintBorder(component, g, 0, 0, width, height)
            projectIcon?.let { icon ->
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
            val color = outlineColor ?: return
            val stroke = JBUI.scale(outlineThickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)).toFloat()
            val halfStroke = stroke / 2.0
            val left = geometry.bounds.x + halfStroke
            val right = geometry.bounds.maxX - halfStroke
            val top = geometry.bounds.y + halfStroke
            val bottom = geometry.bounds.maxY - halfStroke
            val radius = (geometry.radius - halfStroke).coerceAtLeast(0.0)
            if (right <= left || bottom <= top) return
            val control = radius * 0.5522847498307936
            val line: Shape = when (outlineStyle) {
                TabOutlineStyle.BOTTOM -> Line2D.Double(left + radius, bottom, right - radius, bottom)
                TabOutlineStyle.ROUNDED -> Path2D.Double().apply {
                    moveTo(left, bottom - radius)
                    curveTo(left, bottom - radius + control, left + radius - control, bottom, left + radius, bottom)
                    lineTo(right - radius, bottom)
                    curveTo(right - radius + control, bottom, right, bottom - radius + control, right, bottom - radius)
                }
                TabOutlineStyle.FULL -> RoundRectangle2D.Double(
                    left, top, right - left, bottom - top, radius * 2, radius * 2,
                )
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = color
            g.draw(line)
        } finally {
            g.dispose()
        }
    }
}

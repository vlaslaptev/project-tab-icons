package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

/** Одна форма линии и одинаковые защитные поля у обычных табов и групп. */
internal fun projectTabOutlineInsets(color: Color?, thickness: Int, style: TabOutlineStyle): Insets {
    if (color == null) return Insets(0, 0, 0, 0)
    val size = thickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)
    val edge = JBUI.scale(size)
    return when {
        style == TabOutlineStyle.FULL -> Insets(edge, edge, edge, edge)
        size > DEFAULT_OUTLINE_THICKNESS -> Insets(0, 0, edge, 0)
        else -> Insets(0, 0, 0, 0)
    }
}

internal class ProjectTabOutline {
    private var lastOutlineGeometry: ProjectTabGeometry? = null
    private var lastStyle: TabOutlineStyle? = null
    private var lastOutlineStroke = Float.NaN
    private var outlinePath: Shape? = null
    private var outlineStroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    fun paint(g: Graphics2D, geometry: ProjectTabGeometry, color: Color, thickness: Int, style: TabOutlineStyle) {
        val stroke = JBUI.scale(thickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)).toFloat()
        val line = cachedOutline(geometry, stroke, style) ?: return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = outlineStroke
        g.color = color
        g.draw(line)
    }

    private fun cachedOutline(geometry: ProjectTabGeometry, stroke: Float, outlineStyle: TabOutlineStyle): Shape? {
        if (lastOutlineGeometry != geometry || lastStyle != outlineStyle || lastOutlineStroke != stroke) {
            val halfStroke = stroke / 2.0
            val left = geometry.bounds.x + halfStroke
            val right = geometry.bounds.maxX - halfStroke
            val top = geometry.bounds.y + halfStroke
            val bottom = geometry.bounds.maxY - halfStroke
            val radius = (geometry.radius - halfStroke).coerceAtLeast(0.0)
            if (right <= left || bottom <= top) return null
            val control = radius * 0.5522847498307936
            outlinePath = when (outlineStyle) {
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
            if (lastOutlineStroke != stroke) outlineStroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lastOutlineGeometry = geometry
            lastStyle = outlineStyle
            lastOutlineStroke = stroke
        }
        return outlinePath
    }
}

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

/** Координаты вкладки не входят в ключ; размеры, радиус и stroke уже учитывают UI scale. Все вызовы — UI. */
internal class ProjectTabOutline(cacheLimit: Int = 1) {
    private data class Key(val width: Double, val height: Double, val radius: Double,
                           val stroke: Float, val style: TabOutlineStyle)
    private data class Outline(val path: Shape, val stroke: BasicStroke)
    private var lastKey: Key? = null
    // Обычной вкладке достаточно последней формы; общий painter групп хранит ограниченную историю размеров.
    private val cache = if (cacheLimit > 1) object : LinkedHashMap<Key, Outline>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Outline>?) = size > cacheLimit
    } else null
    private var outlinePath: Shape? = null
    private var outlineStroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    init { require(cacheLimit > 0) }

    fun paint(g: Graphics2D, geometry: ProjectTabGeometry, color: Color, thickness: Int, style: TabOutlineStyle) {
        val stroke = JBUI.scale(thickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)).toFloat()
        val line = cachedOutline(geometry, stroke, style) ?: return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = outlineStroke
        g.color = color
        val bounds = geometry.bounds
        if (bounds.x == 0.0 && bounds.y == 0.0) g.draw(line) else {
            // Перенос сохраняет device clip вкладки. Исходный transform восстанавливаем точно,
            // в том числе при дробном масштабе и отрицательных координатах общей поверхности.
            val transform = g.transform
            try {
                g.translate(bounds.x, bounds.y)
                g.draw(line)
            } finally { g.transform = transform }
        }
    }

    private fun cachedOutline(geometry: ProjectTabGeometry, stroke: Float, outlineStyle: TabOutlineStyle): Shape? {
        val bounds = geometry.bounds
        if (bounds.width <= stroke || bounds.height <= stroke) return null
        val previous = lastKey
        if (previous == null || previous.width != bounds.width || previous.height != bounds.height ||
            previous.radius != geometry.radius || previous.style != outlineStyle || previous.stroke != stroke) {
            val key = Key(bounds.width, bounds.height, geometry.radius, stroke, outlineStyle)
            val cached = cache?.get(key)
            if (cached != null) {
                lastKey = key
                outlinePath = cached.path
                outlineStroke = cached.stroke
                return cached.path
            }
            val halfStroke = stroke / 2.0
            val left = halfStroke
            val right = bounds.width - halfStroke
            val top = halfStroke
            val bottom = bounds.height - halfStroke
            val radius = (geometry.radius - halfStroke).coerceAtLeast(0.0)
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
            if (previous?.stroke != stroke) outlineStroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lastKey = key
            cache?.put(key, Outline(requireNotNull(outlinePath), outlineStroke))
        }
        return outlinePath
    }
}

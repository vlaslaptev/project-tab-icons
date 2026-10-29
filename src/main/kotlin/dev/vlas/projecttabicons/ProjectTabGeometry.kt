package dev.vlas.projecttabicons

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.IslandsState
import com.intellij.util.ui.JBUI
import java.awt.Shape
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/** Геометрия темы SDK 262 с общей политикой углов для фона, обводки, групп и preview. */
internal data class ProjectTabGeometry(
    val bounds: Rectangle2D.Double,
    val radius: Double,
    val squareCorners: Boolean = false,
) {
    // Только контур заливки: внешняя рамка и раскладка содержимого не меняются.
    fun inset(distance: Double): ProjectTabGeometry {
        val inset = distance.coerceAtLeast(0.0)
        if (inset == 0.0) return this
        val inner = Rectangle2D.Double(
            bounds.x + minOf(inset, bounds.width / 2),
            bounds.y + minOf(inset, bounds.height / 2),
            (bounds.width - 2 * inset).coerceAtLeast(0.0),
            (bounds.height - 2 * inset).coerceAtLeast(0.0),
        )
        return copy(bounds = inner, radius = minOf((radius - inset).coerceAtLeast(0.0), inner.width / 2, inner.height / 2))
    }

    fun shape(): Shape = if (radius == 0.0) bounds else RoundRectangle2D.Double(
        bounds.x, bounds.y, bounds.width, bounds.height, radius * 2, radius * 2)
}

internal fun projectTabGeometry(width: Int, height: Int, corners: TabCornerMode): ProjectTabGeometry {
    return projectTabGeometry(width, height, ExperimentalUI.isNewUI() && IslandsState.isEnabled(),
        JBUI.getInt("Island.arc", 10), corners)
}

internal fun projectTabGeometry(width: Int, height: Int, islands: Boolean, arc: Int,
                                corners: TabCornerMode = TabCornerMode.IDEA_THEME,
                                windows: Boolean = SystemInfoRt.isWindows): ProjectTabGeometry {
    val square = corners == TabCornerMode.SQUARE || corners == TabCornerMode.AUTO && windows
    val inset = if (windows && square) 0.0 else projectTabHorizontalInset(islands).toDouble()
    val bounds = Rectangle2D.Double(inset, 0.0, (width - 2 * inset).coerceAtLeast(0.0), height.toDouble())
    // fillRoundRect принимает диаметр дуги. В Classic UI и обычных темах фон прямоугольный.
    val radius = if (islands && !square) JBUI.scale(arc) / 2.0 else 0.0
    return ProjectTabGeometry(bounds, minOf(radius.coerceAtLeast(0.0), bounds.width / 2, bounds.height / 2), square)
}

internal fun projectTabHorizontalInset(
    islands: Boolean = ExperimentalUI.isNewUI() && IslandsState.isEnabled(),
): Int = if (islands) JBUI.scale(1) else 0

package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import javax.swing.Icon

/** Разделитель имеет минимальную свободную область даже при Group spacing = 0. */
internal fun groupBoundarySpacing(appearance: TabAppearanceState): Int = maxOf(appearance.groupSpacing,
    if (appearance.showGroupSeparator) appearance.groupSeparatorThickness + 12 else 0)

internal fun groupIconSlotWidth(appearance: TabAppearanceState): Int =
    JBUI.scale(appearance.iconSize.pixels + 12) + groupIconOutlineInsets(appearance).let { it.left + it.right }

internal fun groupIconMinimumHeight(appearance: TabAppearanceState): Int =
    JBUI.scale(appearance.iconSize.pixels) + groupIconOutlineInsets(appearance).let { it.top + it.bottom }

private fun groupIconOutlineInsets(appearance: TabAppearanceState) = projectTabOutlineInsets(
    Color.BLACK.takeIf { appearance.showOutline }, appearance.outlineThickness, appearance.outlineStyle)

/** IDE и Preview скрывают значок вместе с его слотом, если полный слот не помещается. */
internal fun visibleGroupIcon(icon: Icon?, availableWidth: Int, tabHeight: Int, appearance: TabAppearanceState): Icon? =
    icon?.takeIf { appearance.showGroupIcon && availableWidth >= groupIconSlotWidth(appearance) &&
        tabHeight >= maxOf(groupIconMinimumHeight(appearance), it.iconHeight) }

internal fun hasGroupBoundary(left: String?, right: String?): Boolean =
    left != right && (left != null || right != null)

/** Пайп не обозначает границы разорванной группы, даже рядом с целой группой. */
internal fun projectTabGroupSeparatorBoundaries(ids: List<String?>,
                                               ranges: List<IntRange> = contiguousProjectTabGroups(ids)): List<Int> {
    val intact = ranges.mapNotNull { ids[it.first] }.toSet()
    return (1 until ids.size).filter { index ->
        val left = ids[index - 1]
        val right = ids[index]
        hasGroupBoundary(left, right) && (left == null || left in intact) && (right == null || right in intact)
    }
}

/** Один набор ключей темы для native и Preview, включая отдельно выбранные темы образца. */
internal fun groupSeparatorNeutralColor(color: (String, Color) -> Color): Color =
    color("Label.infoForeground", color("ContextHelp.foreground", color("Label.foreground", Color(0xCED0D6))))

internal val neutralGroupSeparatorColor: Color = groupSeparatorNeutralColor { key, fallback -> JBColor.namedColor(key, fallback) }

/** Одна линия и один stroke на полосу, без подготовки графики или SVG внутри paint. */
internal class ProjectTabGroupSeparator {
    private var lastThickness = Float.NaN
    private var stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    private val line = Line2D.Double()

    fun paint(g: Graphics2D, x: Double, y: Double, tabHeight: Int, thickness: Int,
              heightPercent: Int, color: Color) {
        if (tabHeight <= 0) return
        val width = JBUI.scale(thickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)).toFloat()
        if (width != lastThickness) {
            stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lastThickness = width
        }
        // Учитываем круглые концы: 100% не выходит за высоту полосы.
        val length = (tabHeight * heightPercent.coerceIn(20, 100) / 100.0 - width).coerceAtLeast(0.0)
        val top = y + (tabHeight - length) / 2.0
        line.setLine(x, top, x, top + length)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = stroke
        g.color = color
        g.draw(line)
    }
}

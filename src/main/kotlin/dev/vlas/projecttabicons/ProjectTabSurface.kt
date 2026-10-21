package dev.vlas.projecttabicons

import com.intellij.ui.ExperimentalUI
import com.intellij.ui.IslandsState
import com.intellij.ui.JBColor
import com.intellij.ui.mac.WindowTabsComponent
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

internal data class ProjectTabSurfaceColors(
    val strip: Color,
    val background: Color,
    val idleBackground: Color = strip,
)

/** Сначала смешиваем цвета, затем один раз сглаживаем внешний контур. */
internal object ProjectTabSurface {
    fun paint(graphics: Graphics2D, width: Int, height: Int, geometry: ProjectTabGeometry,
              colors: ProjectTabSurfaceColors, tabColor: Color?, fillInset: Int,
              fillIntensity: Int = DEFAULT_TAB_FILL_INTENSITY) {
        if (width <= 0 || height <= 0) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Удаляем также прежние полупрозрачные краевые пиксели нативного фона.
            val strip = blend(UIUtil.getPanelBackground(), colors.strip)
            val base = blend(strip, colors.background)
            g.color = strip
            g.fillRect(0, 0, width, height)
            val inset = JBUI.scale(fillInset.coerceIn(MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET)).toDouble()
            val opacity = fillIntensity.coerceIn(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY) / 100f
            val filled = tabColor?.let { blend(base, it, opacity) }
            // При inset=0 фон и заливка попадают в единственную операцию fill с AA.
            g.color = if (inset == 0.0) filled ?: base else base
            g.fill(geometry.shape())
            if (filled != null && inset > 0.0) {
                // Внутренний край заливки отдельный; он не касается внешней дуги таба.
                g.color = filled
                g.fill(geometry.inset(inset).shape())
            }
        } finally { g.dispose() }
    }

    fun blend(background: Color, foreground: Color, opacity: Float = 1f): Color {
        // JBColor хранит не только RGB, но и имя цвета темы. Islands распознаёт
        // MainWindow.background по этому имени: рисует градиент и не уменьшает
        // его opacity у неактивного окна. Потеря имени оставляет старую подложку
        // вкладки под полупрозрачной очисткой, особенно в отступе после группы.
        if (foreground.alpha == 0 || opacity <= 0f) return background
        if (foreground.alpha == 255 && opacity >= 1f) return foreground
        val alpha = foreground.alpha / 255.0 * opacity
        fun channel(back: Int, front: Int) = (back + (front - back) * alpha).roundToInt().coerceIn(0, 255)
        return Color(channel(background.red, foreground.red), channel(background.green, foreground.green),
            channel(background.blue, foreground.blue))
    }
}

/** Состояние читается во время paint: наведение и активация окна не ждут refresh сервиса. */
internal fun nativeProjectTabSurfaceColors(component: Component): ProjectTabSurfaceColors? {
    val label = component as? TabLabel ?: return null
    val tabs = SwingUtilities.getAncestorOfClass(WindowTabsComponent::class.java, label) as? WindowTabsComponent ?: return null
    val window = SwingUtilities.getWindowAncestor(tabs)
    val selected = label.info.`object` === window
    val strip = tabs.tabPainter.getBackgroundColor()
    val islands = ExperimentalUI.isNewUI() && IslandsState.isEnabled()
    val inactive = window != null && !window.isActive
    val idle = if (islands) strip else JBUI.CurrentTheme.MainWindow.Tab.background(false, inactive, false)
    val background = if (islands) {
        // В SDK 262 Islands обычный таб прозрачен, выбранный/hover имеют свою подложку.
        when {
            selected -> JBColor.namedColor("MainWindow.Tab.selectedBackground", JBUI.CurrentTheme.ToolWindow.background())
            tabs.isHoveredOrWithPopup(label) -> JBColor.namedColor("MainWindow.Tab.hoverBackground",
                JBColor.namedColor("MainToolbar.Dropdown.transparentHoverBackground"))
            else -> strip
        }
    } else JBUI.CurrentTheme.MainWindow.Tab.background(selected, inactive, label.isHovered)
    return ProjectTabSurfaceColors(strip, background, idle)
}

/** Возвращаем линии Classic UI поверх собранной подложки, до пользовательской обводки. */
internal fun paintNativeProjectTabEdges(component: Component, g: Graphics2D, width: Int, height: Int) {
    if (ExperimentalUI.isNewUI() && IslandsState.isEnabled()) return
    val label = component as? TabLabel ?: return
    val tabs = SwingUtilities.getAncestorOfClass(WindowTabsComponent::class.java, label) as? WindowTabsComponent ?: return
    val index = tabs.getIndexOf(label.info)
    val last = tabs.tabCount - 1
    val edge = JBUI.scale(1)
    if (label.info.`object` === SwingUtilities.getWindowAncestor(tabs)) {
        g.color = JBUI.CurrentTheme.MainWindow.Tab.BORDER
        g.fillRect(0, 0, width, edge)
        if (index > 0) g.fillRect(0, 0, edge, height)
        if (index < last) g.fillRect(width - edge, 0, edge, height)
    } else if (last > 1 && index < last && index >= 0 && tabs.getTabAt(index + 1) !== tabs.selectedInfo) {
        g.color = JBUI.CurrentTheme.MainWindow.Tab.SEPARATOR
        g.fillRect(width - edge, 0, edge, height)
    }
}

package dev.vlas.projecttabicons

import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.Graphics2DDelegate
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
    val state: ProjectTabSurfaceState = ProjectTabSurfaceState.NATIVE,
)

internal enum class ProjectTabSurfaceState {
    NATIVE, IDLE, HOVERED, SELECTED;

    companion object {
        fun of(selected: Boolean, hovered: Boolean): ProjectTabSurfaceState = when {
            selected -> SELECTED
            hovered -> HOVERED
            else -> IDLE
        }
    }
}

/** Выбранная вкладка сохраняет цвет; наведение не отменяет приглушение остальных. */
internal fun inactiveTabStrength(selected: Boolean, dimming: Double): Float =
    if (selected) 1f else (1.0 - normalizedInactiveTabDimming(dimming) / 100.0).toFloat()

internal data class ProjectTabPaintColors(val strip: Color, val background: Color, val fill: Color?)

/** Сначала смешиваем цвета, затем один раз сглаживаем внешний контур. */
internal object ProjectTabSurface {
    private const val HOVER_BRIGHTNESS_GAP = 0.03
    private const val MAX_IDLE_BRIGHTNESS = 0.90

    /** Коррекция идёт после заливки: даже непрозрачный цвет не скрывает выбранный таб. */
    fun resolve(colors: ProjectTabSurfaceColors, tabColor: Color?, fillIntensity: Int,
                activeTabHighlight: Double = DEFAULT_ACTIVE_TAB_HIGHLIGHT): ProjectTabPaintColors {
        val strip = blend(UIUtil.getPanelBackground(), colors.strip)
        val idle = blend(strip, colors.idleBackground)
        val background = blend(strip, colors.background)
        val opacity = fillIntensity.coerceIn(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY) / 100f
        return ProjectTabPaintColors(strip, stateColor(background, idle, colors.state, activeTabHighlight), tabColor?.let {
            stateColor(blend(background, it, opacity), blend(idle, it, opacity), colors.state, activeTabHighlight)
        })
    }

    private fun stateColor(native: Color, idle: Color, state: ProjectTabSurfaceState, activeTabHighlight: Double): Color {
        if (state == ProjectTabSurfaceState.NATIVE) return native
        // У белой заливки и светлых тем тоже должно остаться место для выделения.
        val idleBrightness = brightness(idle)
        val base = if (idleBrightness > MAX_IDLE_BRIGHTNESS)
            blend(idle, Color.BLACK, (1.0 - MAX_IDLE_BRIGHTNESS / idleBrightness).toFloat()) else idle
        if (state == ProjectTabSurfaceState.IDLE) return base
        val baseBrightness = brightness(base)
        val selectedGap = normalizedActiveTabHighlight(activeTabHighlight) / 100.0
        val gap = if (state == ProjectTabSurfaceState.SELECTED) selectedGap
            else minOf(HOVER_BRIGHTNESS_GAP, selectedGap)
        val target = (baseBrightness + gap).coerceAtMost(1.0)
        // Штатный selected может быть ярче; дополнительный hover не превышает выбранный уровень,
        // в том числе при 0–3%. Ограничение белым сохраняет корректный цвет в светлой теме.
        if (state == ProjectTabSurfaceState.SELECTED && brightness(native) >= target) return native
        val opacity = ((target - baseBrightness) / (1.0 - baseBrightness)).toFloat()
        return blend(base, Color.WHITE, opacity)
    }

    private fun brightness(color: Color): Double =
        (color.red * 0.2126 + color.green * 0.7152 + color.blue * 0.0722) / 255.0

    fun paint(graphics: Graphics2D, width: Int, height: Int, geometry: ProjectTabGeometry,
              colors: ProjectTabSurfaceColors, tabColor: Color?, fillInset: Int,
              fillIntensity: Int = DEFAULT_TAB_FILL_INTENSITY,
              activeTabHighlight: Double = DEFAULT_ACTIVE_TAB_HIGHLIGHT, clearBackground: Boolean = true) {
        if (width <= 0 || height <= 0) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Удаляем также прежние полупрозрачные краевые пиксели нативного фона.
            val paint = resolve(colors, tabColor, fillIntensity, activeTabHighlight)
            if (clearBackground) {
                g.color = paint.strip
                g.fillRect(0, 0, width, height)
            }
            // Градиент нужен только снаружи фигуры. Тело, включая отступ заливки,
            // рисуем непрозрачно поверх него; именованный фон сохраняем для очистки.
            val body = solidBodyGraphics(g)
            val inset = JBUI.scale(fillInset.coerceIn(MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET)).toDouble()
            // При inset=0 фон и заливка попадают в единственную операцию fill с AA.
            body.color = if (inset == 0.0) paint.fill ?: paint.background else paint.background
            body.fill(geometry.shape())
            if (paint.fill != null && inset > 0.0) {
                // Внутренний край заливки отдельный; он не касается внешней дуги таба.
                body.color = paint.fill
                body.fill(geometry.inset(inset).shape())
            }
        } finally { g.dispose() }
    }

    /** Только для локальной копии Graphics: владелец освобождает её вместе с delegate. */
    fun solidBodyGraphics(graphics: Graphics2D): Graphics2D {
        val body = IdeBackgroundUtil.getOriginalGraphics(graphics)
        // SDK 262 отдельно приглушает заливки неактивного окна. Пропускаем только
        // эту обёртку, не меняя её состояния и не затрагивая текст/иконки. Класс
        // Kotlin-internal; сам delegate доступен через публичный Graphics2DDelegate.
        return if (body is Graphics2DDelegate &&
            body.javaClass.name == "com.intellij.openapi.application.impl.islands.IslandsInactiveFrameGraphics2D")
            body.delegate else body
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
    val hovered = tabs.isHoveredOrWithPopup(label)
    val strip = tabs.tabPainter.getBackgroundColor()
    val islands = ExperimentalUI.isNewUI() && IslandsState.isEnabled()
    val inactive = window != null && !window.isActive
    val idle = if (islands) strip else JBUI.CurrentTheme.MainWindow.Tab.background(false, inactive, false)
    val background = if (islands) {
        // В SDK 262 Islands обычный таб прозрачен, выбранный/hover имеют свою подложку.
        when {
            selected -> JBColor.namedColor("MainWindow.Tab.selectedBackground", JBUI.CurrentTheme.ToolWindow.background())
            hovered -> JBColor.namedColor("MainWindow.Tab.hoverBackground",
                JBColor.namedColor("MainToolbar.Dropdown.transparentHoverBackground"))
            else -> strip
        }
    } else JBUI.CurrentTheme.MainWindow.Tab.background(selected, inactive, label.isHovered)
    return ProjectTabSurfaceColors(strip, background, idle, ProjectTabSurfaceState.of(selected, hovered))
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

package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Одинаковые ширины и оси для каждой строки и закреплённой шапки. */
internal class ProjectColumns {
    private val metrics = JBLabel().let { it.getFontMetrics(it.font) }
    private val iconWidth = maxOf(JBUI.scale(132), metrics.stringWidth("Custom SVG") + JBUI.scale(52)) +
        SettingsPendingChange.reservedWidth
    private val colorWidth = maxOf(JBUI.scale(140), metrics.stringWidth("Dark purple") + JBUI.scale(44)) +
        SettingsPendingChange.reservedWidth
    private val groupWidth = JBUI.scale(132)
    private val actionWidth = JBUI.scale(168)
    private val gap = JBUI.scale(8)
    private val inset = JBUI.scale(12)
    private val rowHeight = maxOf(JBUI.scale(60), metrics.height * 2 + JBUI.scale(16))
    private val normalButtonBorder = JBUI.Borders.empty(4, 8)
    private val compactButtonBorder = JBUI.Borders.empty(4, 4)

    fun layout(header: Boolean = false): LayoutManager = object : LayoutManager {
        override fun addLayoutComponent(name: String?, component: Component?) = Unit
        override fun removeLayoutComponent(component: Component?) = Unit
        override fun preferredLayoutSize(parent: Container): Dimension =
            Dimension(JBUI.scale(760), if (header) metrics.height + JBUI.scale(16) else maxOf(rowHeight,
                (parent.components.maxOfOrNull { it.preferredSize.height } ?: 0) + JBUI.scale(12)))
        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, preferredLayoutSize(parent).height)
        override fun layoutContainer(parent: Container) {
            val compact = parent.width < JBUI.scale(760)
            val actualInset = if (compact) JBUI.scale(8) else inset
            val actualGap = if (compact) JBUI.scale(4) else gap
            val actualAction = actionWidth
            val available = (parent.width - actualInset * 2 - actualGap * 6 - actualAction).coerceAtLeast(0)
            val desiredValues = groupWidth + iconWidth + colorWidth * 3
            // Имя проекта остаётся читаемым; длинные подписи действий сокращает JButton.
            val identityMinimum = minOf(JBUI.scale(if (compact) 128 else 140), available / 4)
            val valueRatio = minOf(1.0, (available - identityMinimum).toDouble() / desiredValues)
            val icon = (iconWidth * valueRatio).toInt()
            val color = (colorWidth * valueRatio).toInt()
            val group = (groupWidth * valueRatio).toInt()
            val identity = (available - group - icon - color * 3).coerceAtLeast(0)
            var x = actualInset
            listOf(identity, group, icon, color, color, color, actualAction).forEachIndexed { index, width ->
                parent.components.getOrNull(index)?.let { view ->
                    if (view is SettingsValueButton) {
                        val targetBorder = if (compact) compactButtonBorder else normalButtonBorder
                        SettingsPendingChange.setContentBorder(view, targetBorder)
                    }
                    val height = minOf(view.preferredSize.height, parent.height)
                    val padding = if (header && index in 2..5) JBUI.scale(if (compact) 4 else 8) else 0
                    view.setBounds(x + padding, (parent.height - height) / 2, (width - padding).coerceAtLeast(0), height)
                }
                x += width + actualGap
            }
        }
    }
}

internal class ProjectRowPanel(layout: LayoutManager) : JPanel(layout) {
    private var hovered = false
    init { isOpaque = true }

    fun trackHover() {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(event: MouseEvent) {
                hovered = contains(SwingUtilities.convertPoint(event.component, event.point, this@ProjectRowPanel))
                repaint()
            }
        }
        fun attach(view: Component) {
            view.addMouseListener(listener)
            if (view is Container) view.components.forEach(::attach)
            if (view is SettingsValueButton) view.addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    scrollRectToVisible(Rectangle(0, 0, width, height))
                    repaint()
                }
                override fun focusLost(event: FocusEvent) = repaint()
            })
        }
        attach(this)
    }

    override fun getBackground(): java.awt.Color = if (isEnabled && hovered)
        JBColor.namedColor("Table.hoverBackground", JBColor(0xF5F6F8, 0x303237)) else UIUtil.getListBackground()
}

internal class ProjectRowsPanel : JPanel(GridBagLayout()) {
    init { background = UIUtil.getListBackground() }
}

/** Общие подписи и artwork для обеих таблиц. */
internal fun projectSettingsIconName(row: ProjectIconSettingsRow): String = row.textIcon?.let { "Text: ${it.text}" }
    ?: row.builder?.contourId?.let { id -> ContourCatalog.entries.firstOrNull { it.id == id }?.title }
    ?: if (row.activeChoice != null) "Custom SVG" else if (row.snapshot?.currentFile(!JBColor.isBright()) != null) "Custom icon" else "Default"

internal fun projectSettingsArtwork(row: ProjectIconSettingsRow, appearance: TabAppearanceState): Icon? =
    row.activeChoice?.let { projectSvgIcon(it.asset, 24) }
        ?: row.previewIcon(initials = appearance.iconInitials, letterWeight = appearance.iconLetterWeight)

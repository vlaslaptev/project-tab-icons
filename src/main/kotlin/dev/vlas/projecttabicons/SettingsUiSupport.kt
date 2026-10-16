package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicGraphicsUtils
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class ColorSwatch(private val color: Color, private val selected: Boolean = false) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(18)
    override fun getIconHeight(): Int = JBUI.scale(18)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            g.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(4), JBUI.scale(4))
            if (selected) {
                g.color = iconBackground(color)
                g.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat())
                g.drawLine(x + JBUI.scale(4), y + JBUI.scale(9), x + JBUI.scale(8), y + JBUI.scale(13))
                g.drawLine(x + JBUI.scale(8), y + JBUI.scale(13), x + JBUI.scale(14), y + JBUI.scale(5))
            }
        } finally {
            g.dispose()
        }
    }
}

/** Лёгкое действие в строке: системные шрифт/цвета, стандартная клавиатура JButton. */
internal class SettingsValueButton(text: String, icon: Icon? = null) : JButton(text, icon) {
    var hideWhenDisabled = false
    var muteDisabledIcon = true
    init {
        ui = object : BasicButtonUI() {
            override fun paintText(graphics: Graphics, component: JComponent, rect: Rectangle, text: String) {
                if (component.isEnabled) {
                    super.paintText(graphics, component, rect, text)
                    return
                }
                // BasicButtonUI иначе рисует тиснёную подпись, не подходящую теме IDEA.
                val g = graphics.create()
                try {
                    g.color = UIManager.getColor("Label.disabledForeground") ?: UIUtil.getContextHelpForeground()
                    BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, -1, rect.x, rect.y + g.fontMetrics.ascent)
                } finally { g.dispose() }
            }

            override fun paintIcon(graphics: Graphics, component: JComponent, rect: Rectangle) {
                if (component.isEnabled || !muteDisabledIcon) {
                    super.paintIcon(graphics, component, rect)
                    return
                }
                val image = icon ?: return
                val g = graphics.create() as Graphics2D
                try {
                    val alpha = (g.composite as? AlphaComposite)?.alpha ?: 1f
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.4f)
                    image.paintIcon(component, g, rect.x, rect.y)
                } finally { g.dispose() }
            }
        }
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        horizontalAlignment = SwingConstants.LEADING
        iconTextGap = JBUI.scale(8)
        border = JBUI.Borders.empty(4, 8)
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
        height = maxOf(height, JBUI.scale(32))
    }

    override fun paintComponent(graphics: Graphics) {
        if (hideWhenDisabled && !isEnabled) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isEnabled && (model.isRollover || model.isPressed || hasFocus())) {
                g.color = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xE8EBF0, 0x41444A))
                g.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            }
            if (isEnabled && hasFocus()) {
                g.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                g.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g.drawRoundRect(1, 1, width - 3, height - 3, JBUI.scale(8), JBUI.scale(8))
            }
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

internal class IconPreviewWell(size: Int = 56) : JBLabel() {
    var accent: Color? = null
        set(value) { field = value; repaint() }
    init {
        isOpaque = false
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = JBUI.size(size, size)
        minimumSize = preferredSize
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIUtil.getListBackground()
            g.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))
            accent?.let {
                g.color = it
                g.fillRoundRect(JBUI.scale(10), height - JBUI.scale(5), width - JBUI.scale(20), JBUI.scale(3), JBUI.scale(3), JBUI.scale(3))
            }
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

/** Нативный список обеспечивает стрелки, Home/End, Page Up/Down и видимость выбранной ячейки. */
internal class IconChoiceGrid<T>(
    private val title: (T) -> String,
    private val detail: ((T) -> String)? = null,
    private val artwork: (T) -> Icon?,
) : JBList<T>(DefaultListModel<T>()) {
    private var hoveredIndex = -1
    init {
        layoutOrientation = JList.HORIZONTAL_WRAP
        visibleRowCount = -1
        fixedCellWidth = JBUI.scale(108)
        fixedCellHeight = JBUI.scale(80)
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : JPanel(BorderLayout(0, JBUI.scale(6))), ListCellRenderer<T> {
            private val picture = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER }
            private val caption = settingsHint("").apply { horizontalAlignment = SwingConstants.CENTER }
            private val subtitle = settingsHint("").apply { horizontalAlignment = SwingConstants.CENTER }
            private var selectedCell = false
            private var focusedCell = false
            private var hoveredCell = false
            init {
                isOpaque = false
                border = JBUI.Borders.empty(10, 6)
                add(picture, BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(caption, BorderLayout.NORTH)
                    if (detail != null) add(subtitle, BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }
            override fun getListCellRendererComponent(list: JList<out T>, value: T, index: Int,
                                                      selected: Boolean, focused: Boolean): Component {
                val name = settingsLiteral(title(value))
                selectedCell = selected
                focusedCell = focused
                hoveredCell = index == hoveredIndex
                picture.icon = artwork(value)
                caption.text = name
                caption.foreground = if (selected) UIUtil.getListSelectionForeground(list.hasFocus()) else list.foreground
                subtitle.text = detail?.invoke(value)?.let(::settingsLiteral).orEmpty()
                subtitle.foreground = if (selected) caption.foreground else UIUtil.getContextHelpForeground()
                background = UIUtil.getListSelectionBackground(list.hasFocus())
                getAccessibleContext().accessibleName = listOf(name, subtitle.text).filter(String::isNotEmpty).joinToString(" · ")
                return this
            }
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (selectedCell || hoveredCell) {
                        g.color = if (selectedCell) background else
                            JBColor.namedColor("Table.hoverBackground", JBColor(0xF5F6F8, 0x303237))
                        g.fillRoundRect(JBUI.scale(3), JBUI.scale(3), width - JBUI.scale(6), height - JBUI.scale(6), JBUI.scale(8), JBUI.scale(8))
                    }
                    if (focusedCell) {
                        g.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                        g.drawRoundRect(JBUI.scale(3), JBUI.scale(3), width - JBUI.scale(6) - 1, height - JBUI.scale(6) - 1, JBUI.scale(8), JBUI.scale(8))
                    }
                } finally { g.dispose() }
                super.paintComponent(graphics)
            }
        }
        val hover = object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = locationToIndex(event.point).takeIf { it >= 0 && getCellBounds(it, it).contains(event.point) } ?: -1
                if (hoveredIndex != index) { hoveredIndex = index; repaint() }
            }
            override fun mouseExited(event: MouseEvent) { hoveredIndex = -1; repaint() }
        }
        addMouseMotionListener(hover)
        addMouseListener(hover)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = repaint()
            override fun focusLost(event: FocusEvent) = repaint()
        })
        // Регистрирует список в ToolTipManager; текст подсказки берётся из ячейки под курсором.
        toolTipText = ""
    }

    fun replaceItems(values: List<T>, selected: T?) {
        val items = model as DefaultListModel<T>
        valueIsAdjusting = true
        hoveredIndex = -1
        try {
            items.clear()
            values.forEach(items::addElement)
            selectedIndex = values.indexOf(selected)
        } finally {
            valueIsAdjusting = false
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val index = locationToIndex(event.point)
        return if (index >= 0 && getCellBounds(index, index)?.contains(event.point) == true)
            model.getElementAt(index).let { value ->
                settingsLiteral(listOf(title(value), detail?.invoke(value).orEmpty()).filter(String::isNotEmpty).joinToString(" · "))
            } else null
    }
}

internal fun colorName(color: Color): String {
    COLOR_PRESETS.forEach { preset ->
        if (color == preset.bright) return preset.name
        if (color == preset.dark) return "Dark ${preset.name.lowercase(java.util.Locale.ROOT)}"
    }
    return "Custom"
}

internal fun settingsHint(text: String): JBLabel = JBLabel(text).apply {
    foreground = UIUtil.getContextHelpForeground()
    font = font.deriveFont((font.size2D - JBUI.scale(1)).coerceAtLeast(JBUI.scale(11).toFloat()))
}

internal fun onTextChanged(changed: () -> Unit): DocumentListener = object : DocumentListener {
    override fun insertUpdate(event: DocumentEvent) = changed()
    override fun removeUpdate(event: DocumentEvent) = changed()
    override fun changedUpdate(event: DocumentEvent) = changed()
}

internal fun gridNavigation(buttons: List<JToggleButton>, columns: Int = 7) {
    val offsets = mapOf("LEFT" to -1, "RIGHT" to 1, "UP" to -columns, "DOWN" to columns,
        "HOME" to -buttons.size, "END" to buttons.size)
    buttons.forEachIndexed { index, button ->
        button.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = button.scrollRectToVisible(button.visibleRect)
        })
        offsets.forEach { (key, offset) ->
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key)
            button.actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    val target = buttons[(index + offset).coerceIn(0, buttons.lastIndex)]
                    target.requestFocusInWindow()
                    target.doClick()
                }
            })
        }
    }
}

internal fun settingsMessage(message: String): String =
    message.lineSequence().first().take(240).replace('<', '‹').replace('>', '›')

internal fun settingsLiteral(text: String): String = text.replace('<', '‹').replace('>', '›')

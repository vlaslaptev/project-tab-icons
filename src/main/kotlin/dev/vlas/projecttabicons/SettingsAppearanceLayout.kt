package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.IntelliJSpacingConfiguration
import com.intellij.ui.dsl.builder.SpacingConfiguration
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.components.SegmentedButtonComponent
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.border.AbstractBorder
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener

/** При нехватке высоты прокручивается вся страница; таблица сохраняет свой header и список. */
internal class SettingsPageBody : JPanel(BorderLayout(0, JBUI.scale(20))), Scrollable {
    override fun getMinimumSize(): Dimension = Dimension(0, 0)
    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
        val arrangement = layout as BorderLayout
        val top = arrangement.getLayoutComponent(BorderLayout.NORTH)?.preferredSize?.height ?: 0
        val table = arrangement.getLayoutComponent(BorderLayout.CENTER)?.minimumSize?.height ?: 0
        height = top + arrangement.vgap + table + insets.top + insets.bottom
    }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = preferredSize.height <= (parent?.height ?: 0)
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(32)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
        (visible.height - JBUI.scale(32)).coerceAtLeast(JBUI.scale(32))
}

internal data class AppearanceField(val label: JBLabel?, val control: JComponent)

/** Два поля в строке только когда оба помещаются полностью; иначе одна колонка. */
internal class SettingsAppearanceForm(
    private val master: JComponent,
    private val fields: List<AppearanceField>,
    private val labelWidthProvider: () -> Int,
    private val controlWidth: Int,
    private val rowHeight: Int,
    private val availableWidth: () -> Int,
    private val masterLabel: JBLabel? = null,
    private val singleColumn: Boolean = false,
) : JPanel(null) {
    private val fieldGap = JBUI.scale(12)
    private val columnGap = JBUI.scale(28)
    private val rowGap = JBUI.scale(10)
    private val labelWidth get() = labelWidthProvider()
    private val fieldWidth get() = labelWidth + fieldGap + controlWidth

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        masterLabel?.let(::add)
        add(master)
        fields.forEach { field -> field.label?.let(::add); add(field.control) }
        fun trackFocus(view: Component) {
            if (view is JComponent) view.addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    view.scrollRectToVisible(Rectangle(0, 0, view.width, view.height))
                }
            })
            if (view is Container) view.components.forEach(::trackFocus)
        }
        trackFocus(this)
    }

    private fun innerWidth(): Int = (availableWidth() - insets.left - insets.right).coerceAtLeast(1)
    private fun columnCount(width: Int): Int = if (!singleColumn && width >= fieldWidth * 2 + columnGap) 2 else 1
    private fun masterHeight(): Int = maxOf(rowHeight, master.preferredSize.height)

    override fun getPreferredSize(): Dimension {
        val rows = (fields.size + columnCount(innerWidth()) - 1) / columnCount(innerWidth())
        return Dimension(fieldWidth * 2 + columnGap + insets.left + insets.right,
            insets.top + masterHeight() + JBUI.scale(12) + rows * rowHeight +
                (rows - 1).coerceAtLeast(0) * rowGap + insets.bottom)
    }

    override fun doLayout() {
        val usable = (width - insets.left - insets.right).coerceAtLeast(0)
        val columns = columnCount(minOf(usable, innerWidth()))
        val cellWidth = minOf(fieldWidth, (usable - (columns - 1) * columnGap).coerceAtLeast(0) / columns)
        val topHeight = masterHeight()
        masterLabel?.let { label ->
            label.setBounds(insets.left, insets.top, labelWidth.coerceAtMost(usable), topHeight)
            master.setBounds(insets.left + labelWidth + fieldGap, insets.top,
                (usable - labelWidth - fieldGap).coerceIn(0, controlWidth), topHeight)
        } ?: master.setBounds(insets.left, insets.top, usable, topHeight)
        fields.forEachIndexed { index, field ->
            val x = insets.left + index % columns * (cellWidth + columnGap)
            val y = insets.top + topHeight + JBUI.scale(12) + index / columns * (rowHeight + rowGap)
            field.label?.let { label ->
                label.setBounds(x, y, labelWidth.coerceAtMost(cellWidth), rowHeight)
                field.control.setBounds(x + labelWidth + fieldGap, y,
                    (cellWidth - labelWidth - fieldGap).coerceAtLeast(0), rowHeight)
            } ?: field.control.setBounds(x, y, cellWidth, rowHeight)
        }
    }
}

/** Одна радиогруппа: пресеты идут строкой/списком, дополнительная информация может переноситься. */
internal class SettingsRadioRow(
    private val title: JComponent,
    private val choices: List<JComponent>,
    private val wrapChoices: Boolean = false,
    private val availableWidth: () -> Int,
) : JPanel(null) {
    private val gap = JBUI.scale(12)
    private val titleGap = JBUI.scale(20)
    private val rowHeight get() = (choices + title).maxOf { it.preferredSize.height }.coerceAtLeast(JBUI.scale(30))
    private val choicesWidth get() = choices.sumOf { it.preferredSize.width } + (choices.size - 1) * gap
    init { isOpaque = false; add(title); choices.forEach(::add) }
    private fun wrappedBounds(available: Int): List<Rectangle> {
        val indent = (title.preferredSize.width + titleGap).takeIf {
            available >= it + choices.maxOf { choice -> choice.preferredSize.width }
        } ?: 0
        var x = indent
        var y = if (indent == 0) rowHeight + JBUI.scale(4) else 0
        return choices.map { choice ->
            val choiceWidth = choice.preferredSize.width
            if (x > indent && x + choiceWidth > available) {
                x = indent
                y += rowHeight + JBUI.scale(4)
            }
            Rectangle(x, y, choiceWidth.coerceAtMost((available - indent).coerceAtLeast(0)), rowHeight).also {
                x += choiceWidth + gap
            }
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(title.preferredSize.width + titleGap + choicesWidth,
        if (wrapChoices) wrappedBounds(availableWidth()).lastOrNull()?.let { it.y + it.height } ?: rowHeight
        else when {
            availableWidth() >= title.preferredSize.width + titleGap + choicesWidth -> rowHeight
            availableWidth() >= choicesWidth -> rowHeight * 2 + JBUI.scale(4)
            else -> rowHeight * (choices.size + 1) + JBUI.scale(4)
        })
    override fun doLayout() {
        title.setBounds(0, 0, title.preferredSize.width, rowHeight)
        if (wrapChoices) {
            choices.zip(wrappedBounds(width)).forEach { (choice, bounds) -> choice.bounds = bounds }
            return
        }
        val sameRow = width >= title.preferredSize.width + titleGap + choicesWidth
        var x = if (sameRow) title.preferredSize.width + titleGap else 0
        var y = if (sameRow) 0 else rowHeight + JBUI.scale(4)
        choices.forEach { choice ->
            choice.setBounds(x, y, choice.preferredSize.width, rowHeight)
            if (width >= choicesWidth) x += choice.width + gap else y += rowHeight
        }
    }
}

/** Длинная подпись темы переносится вместе с контролом, без многоточий. */
internal class SettingsPreviewHeader(
    private val title: JComponent,
    private val themeLabel: JComponent,
    private val theme: JComponent,
    private val availableWidth: () -> Int,
) : JPanel(null) {
    private val gap = JBUI.scale(8)
    private val titleGap = JBUI.scale(24)
    private val rowHeight get() = maxOf(title.preferredSize.height, themeLabel.preferredSize.height, theme.preferredSize.height)
    private val themeWidth get() = themeLabel.preferredSize.width + gap + theme.preferredSize.width
    init { isOpaque = false; add(title); add(themeLabel); add(theme) }
    override fun getPreferredSize(): Dimension = Dimension(title.preferredSize.width + titleGap + themeWidth,
        when {
            availableWidth() >= title.preferredSize.width + titleGap + themeWidth -> rowHeight
            availableWidth() >= themeWidth -> rowHeight * 2 + gap
            else -> rowHeight * 3 + gap * 2
        })
    override fun doLayout() {
        title.setBounds(0, 0, title.preferredSize.width, rowHeight)
        when {
            width >= title.preferredSize.width + titleGap + themeWidth -> {
                val x = width - themeWidth
                themeLabel.setBounds(x, 0, themeLabel.preferredSize.width, rowHeight)
                theme.setBounds(x + themeLabel.width + gap, 0, theme.preferredSize.width, rowHeight)
            }
            width >= themeWidth -> {
                themeLabel.setBounds(0, rowHeight + gap, themeLabel.preferredSize.width, rowHeight)
                theme.setBounds(themeLabel.width + gap, rowHeight + gap, theme.preferredSize.width, rowHeight)
            }
            else -> {
                themeLabel.setBounds(0, rowHeight + gap, width, rowHeight)
                theme.setBounds(0, (rowHeight + gap) * 2, theme.preferredSize.width.coerceAtMost(width), rowHeight)
            }
        }
    }
}

/** Штатные состояния IDEA, просторные сегменты и общая граница переключаемой формы. */
internal class SettingsGroupSwitcher : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout).apply { isOpaque = false }
    private val titles = mutableListOf<String>()
    private val listeners = mutableListOf<ChangeListener>()
    private val selector = SegmentedButtonComponent<String> { title ->
        SegmentedButton.createPresentation(title)
    }.apply {
        val defaults = IntelliJSpacingConfiguration()
        spacing = object : SpacingConfiguration by defaults {
            override val segmentedButtonHorizontalGap: Int = 20
            override val segmentedButtonVerticalGap: Int = 6
        }
        accessibleContext.accessibleName = "Appearance settings group"
    }

    init {
        isOpaque = false
        border = object : AbstractBorder() {
            override fun getBorderInsets(component: Component): Insets = JBUI.insets(1)

            override fun getBorderInsets(component: Component, insets: Insets): Insets = insets.apply {
                val edge = JBUI.scale(1)
                set(edge, edge, edge, edge)
            }

            override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val line = JBUI.scale(1).toFloat()
                    val half = line / 2
                    g.color = settingsGroupBorderColor()
                    g.stroke = BasicStroke(line)
                    val arc = JBUI.scale(8).toFloat()
                    g.draw(java.awt.geom.RoundRectangle2D.Float(x + half, y + half, width - line, height - line, arc, arc))
                } finally { g.dispose() }
            }
        }
        add(object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(12, 12, 0, 12)
                add(selector, BorderLayout.WEST)
            }

            override fun paintChildren(graphics: Graphics) {
                super.paintChildren(graphics)
                // Между соседними неактивными сегментами у IDEA нет разделителя.
                // Используем реальные границы дочерних компонентов, не меняя их UI.
                val buttons = selector.components.filter { it.isVisible }.sortedBy { it.x }
                if (buttons.size != titles.size) return
                val selected = titles.indexOf(selector.selectedItem)
                val g = graphics.create()
                try {
                    g.color = settingsGroupBorderColor()
                    buttons.zipWithNext().forEachIndexed { index, (left, right) ->
                        if (index != selected && index + 1 != selected) {
                            val x = selector.x + (left.x + left.width + right.x) / 2
                            g.drawLine(x, selector.y + JBUI.scale(7), x, selector.y + selector.height - JBUI.scale(8))
                        }
                    }
                } finally { g.dispose() }
            }
        }, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        selector.addChangeListener {
            val selected = selector.selectedItem ?: return@addChangeListener
            cardLayout.show(cards, selected)
            val event = ChangeEvent(this)
            listeners.forEach { it.stateChanged(event) }
        }
    }

    fun addTab(title: String, content: JComponent) {
        val selected = selector.selectedItem
        cards.add(content, title)
        titles += title
        selector.items = titles.toList()
        selector.selectedItem = selected ?: titles.first()
    }

    fun addChangeListener(listener: ChangeListener) {
        listeners += listener
    }
}

private fun settingsGroupBorderColor() = JBColor.namedColor("Component.borderColor", JBColor(0xB3B8C2, 0x64666B))

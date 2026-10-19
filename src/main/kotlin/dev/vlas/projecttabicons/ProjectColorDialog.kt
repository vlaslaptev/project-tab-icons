package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColorPickerListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.plaf.basic.BasicToggleButtonUI

/** Компактная палитра: имя выбранного цвета, 14 образцов и штатный выбор произвольного цвета. */
internal class ProjectColorDialog(
    parent: JComponent,
    private val project: Project,
    private val row: ProjectIconSettingsRow,
    private val target: ProjectColorTarget,
    private val appearance: TabAppearanceState,
) : DialogWrapper(parent, true) {
    private val automaticAllowed = target != ProjectColorTarget.ICON || row.automaticIcon
    private val body = JPanel(BorderLayout(0, JBUI.scale(20)))
    private val hex = JBTextField((row.color(target) ?: COLOR_PRESETS.first().bright).hex(), 9)
    private val nativePicker = JButton("More colors…")
    private val autoColor = JRadioButton(if (target == ProjectColorTarget.ICON) "Use IDE color" else "Match icon color",
        automaticAllowed && row.colorOverrides.value(target) == null)
    private val customColor = JRadioButton("Custom color", !autoColor.isSelected)
    private val colorGroup = ButtonGroup()
    private val buttons = linkedMapOf<Color, JToggleButton>()
    private val preview = ProjectColorPreview(row.target.name, appearance)
    private val colorLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val colorDetail = settingsHint("")

    init {
        title = "${target.title} · ${row.target.name}"
        setOKButtonText("Choose color")
        body.border = JBUI.Borders.empty(8)
        body.add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(preview, BorderLayout.WEST)
            add(JPanel(GridBagLayout()).apply {
                add(colorLabel, GridBagConstraints().apply {
                    gridx = 0; gridy = 0; weightx = 1.0; anchor = GridBagConstraints.WEST
                })
                add(colorDetail, GridBagConstraints().apply {
                    gridx = 0; gridy = 1; weightx = 1.0; anchor = GridBagConstraints.WEST
                    insets = JBUI.insetsTop(4)
                })
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        val controls = JPanel(GridBagLayout())
        var line = 0
        fun row(view: JComponent, gap: Int = 8, indent: Int = 0) {
            controls.add(view, GridBagConstraints().apply {
                gridx = 0; gridy = line++; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(gap, indent, 0, 0)
            })
        }
        ButtonGroup().apply { add(autoColor); add(customColor) }
        if (automaticAllowed) {
            row(autoColor, 0)
            row(customColor, 4)
        }
        val indent = if (automaticAllowed) 22 else 0
        val palette = JPanel(GridBagLayout())
        fun paletteCell(view: JComponent, column: Int, row: Int) {
            palette.add(view, GridBagConstraints().apply {
                gridx = column; gridy = row
                anchor = if (column == 0) GridBagConstraints.WEST else GridBagConstraints.CENTER
                insets = JBUI.insets(if (row == 0) 0 else 6, if (column == 0) 0 else 4, 0, if (column == 0) 8 else 0)
                weightx = if (column == 0) 0.0 else 1.0
            })
        }
        COLOR_PRESETS.forEachIndexed { column, preset ->
            paletteCell(settingsHint(preset.name).apply {
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(0, 4)
            }, column + 1, 0)
        }
        listOf(false, true).forEachIndexed { index, dark ->
            paletteCell(settingsHint(if (dark) "Dark" else "Bright"), 0, index + 1)
            COLOR_PRESETS.forEachIndexed { column, preset ->
                val value = if (dark) preset.dark else preset.bright
                val label = "${colorName(value)} · ${value.hex()}"
                val button = ColorPresetButton(value).apply {
                    toolTipText = label
                    accessibleContext.accessibleName = label
                    addActionListener {
                        customColor.isSelected = true
                        hex.text = value.hex()
                        updateControls()
                    }
                }
                colorGroup.add(button)
                buttons[value] = button
                paletteCell(button, column + 1, index + 1)
            }
        }
        row(palette, if (automaticAllowed) 12 else 0, indent)
        gridNavigation(buttons.values.toList())
        val fieldHeight = maxOf(hex.preferredSize.height, nativePicker.preferredSize.height, JBUI.scale(30))
        // JTextField с columns > 0 пересчитывает ширину поверх preferredSize.
        hex.columns = 0
        hex.preferredSize = Dimension(maxOf(JBUI.scale(110),
            hex.getFontMetrics(hex.font).stringWidth("#FFFFFF") + hex.insets.left + hex.insets.right + JBUI.scale(16)), fieldHeight)
        nativePicker.preferredSize = Dimension(nativePicker.preferredSize.width, fieldHeight)
        row(JPanel(GridBagLayout()).apply {
            add(JBLabel("HEX:").apply { labelFor = hex }, GridBagConstraints().apply {
                gridx = 0; anchor = GridBagConstraints.WEST
            })
            hex.accessibleContext.accessibleName = "${target.title} (HEX)"
            hex.toolTipText = "Six hexadecimal digits, for example #60A5FA"
            add(hex, GridBagConstraints().apply { gridx = 1; insets = JBUI.insetsLeft(8) })
            add(nativePicker, GridBagConstraints().apply { gridx = 2; insets = JBUI.insetsLeft(8) })
            add(JPanel(), GridBagConstraints().apply { gridx = 3; weightx = 1.0 })
        }, 20, indent)
        body.add(controls, BorderLayout.CENTER)
        body.add(settingsHint(when (target) {
            ProjectColorTarget.ICON -> "Automatic outline and tab colors follow this icon."
            ProjectColorTarget.OUTLINE -> "Changes only the outline. Match icon color keeps it in sync."
            ProjectColorTarget.TAB -> if (appearance.showTabBackground) "Tab fill intensity: ${appearance.tabFillIntensity}%. Icon and outline colors stay unchanged."
                else "Fill intensity: ${appearance.tabFillIntensity}%. Enable Fill tab in Color to show it on window tabs."
        }), BorderLayout.SOUTH)
        autoColor.addActionListener { updateControls() }
        customColor.addActionListener { updateControls() }
        hex.document.addDocumentListener(onTextChanged { updateSelection() })
        nativePicker.addActionListener {
            // Режим Choose: нативный диалог меняет только черновик после подтверждения.
            val listener = object : ColorPickerListener {
                override fun colorChanged(color: Color) = Unit
                override fun closed(color: Color?) = Unit
            }
            ColorChooserService.getInstance().showDialog(project, body, target.title,
                parseIconColor(hex.text), enableOpacity = false, listeners = listOf(listener))?.let { color ->
                customColor.isSelected = true
                hex.text = color.hex()
                updateControls()
            }
        }
        init()
        updateControls()
        initValidation()
    }

    private fun updateSelection() {
        colorGroup.clearSelection()
        val automatic = automaticAllowed && autoColor.isSelected
        val value = if (automatic) {
            if (target == ProjectColorTarget.ICON) row.sourceColor else row.color
        } else parseIconColor(hex.text)
        if (!automatic) value?.let { buttons[it]?.isSelected = true }
        colorLabel.text = when {
            automatic -> "Automatic"
            value != null -> colorName(value)
            else -> "Custom color"
        }
        colorDetail.text = if (automatic) {
            if (target == ProjectColorTarget.ICON) "Follows the IDE project color" else "Follows the icon color"
        } else value?.hex() ?: "Enter six HEX digits"
        val choice = row.activeChoice
        val image = if (target == ProjectColorTarget.ICON && value != null && choice?.asset?.textIcon != null) {
            RecentProjectIconHelper.createIcon(choice.asset.textIcon.copy(colorHex = value.hex()).asset().bytes, true, 24)
        } else if (target == ProjectColorTarget.ICON && value != null && choice?.asset?.builder != null) {
            val builder = choice.asset.builder
            ContourCatalog.entries.firstOrNull { it.id == builder.contourId }?.let {
                RecentProjectIconHelper.createIcon(it.svg(value), true, 24)
            }
        } else choice?.let {
            val bytes = it.asset.tabArtwork() ?: it.asset.bytes
            RecentProjectIconHelper.createIcon(bytes, true, 24)
        }
            ?: row.previewIcon(if (target == ProjectColorTarget.ICON) value else row.colorOverrides.icon,
                appearance.iconInitials, appearance.iconLetterWeight)
        val iconColor = if (target == ProjectColorTarget.ICON) value else row.color
        val outline = if (target == ProjectColorTarget.OUTLINE) value else row.colorOverrides.outline ?: iconColor
        val fill = if (target == ProjectColorTarget.TAB) value else row.colorOverrides.tab ?: iconColor
        preview.showColors(image, outline.takeIf { appearance.showOutline || target == ProjectColorTarget.OUTLINE },
            fill.takeIf { appearance.showTabBackground || target == ProjectColorTarget.TAB })
        setOKActionEnabled(automatic || value != null)
    }

    private fun updateControls() {
        hex.isEnabled = !autoColor.isSelected
        // Пресет и More colors сразу выбирают Custom, без лишнего переключения режима.
        updateSelection()
    }

    fun selection(): Color? = if (automaticAllowed && autoColor.isSelected) null else
        requireNotNull(parseIconColor(hex.text)) { "Enter a valid six-digit HEX color." }

    override fun doValidate(): ValidationInfo? =
        if (automaticAllowed && autoColor.isSelected || parseIconColor(hex.text) != null) null else
            ValidationInfo("Enter a six-digit HEX color, for example #60A5FA.", hex)

    override fun createCenterPanel(): JComponent = body
    override fun getPreferredFocusedComponent(): JComponent = if (autoColor.isSelected) autoColor else hex
    override fun getDimensionServiceKey(): String = "ProjectTabIcons.ProjectColor.${target.name}"
}

/** Образец вкладки показывает, какой именно слой меняет выбранный цвет. */
internal class ProjectColorPreview(projectName: String, private val appearance: TabAppearanceState) : JPanel(BorderLayout()) {
    private val baseBorder = JBUI.Borders.empty(8, 12)
    private var tabColor: Color? = null
    private val caption = JBLabel(projectName).apply {
        iconTextGap = JBUI.scale(10)
        horizontalAlignment = SwingConstants.CENTER
        foreground = JBUI.CurrentTheme.MainWindow.Tab.foreground(true, false)
    }

    init {
        isOpaque = false
        preferredSize = JBUI.size(208, 56)
        minimumSize = preferredSize
        add(caption, BorderLayout.CENTER)
        getAccessibleContext().accessibleName = "Project tab color preview"
    }

    fun showColors(icon: Icon?, outline: Color?, fill: Color?) {
        caption.icon = icon
        tabColor = fill
        border = ProjectTabBorder(baseBorder, null, outline, appearance.outlineThickness, appearance.outlineStyle,
            surfaceColors = null)
        revalidate()
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        ProjectTabSurface.paint(graphics as Graphics2D, width, height, projectTabGeometry(width, height),
            ProjectTabSurfaceColors(UIUtil.getPanelBackground(), JBUI.CurrentTheme.MainWindow.Tab.background(true, false, false)),
            tabColor, appearance.tabFillInset, appearance.tabFillIntensity)
    }
}

/** Размер клика больше образца; выбор обозначен и рамкой, и контрастной галочкой. */
private class ColorPresetButton(private val color: Color) : JToggleButton() {
    init {
        ui = BasicToggleButtonUI()
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        preferredSize = JBUI.size(40, 40)
        minimumSize = preferredSize
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(28)
            val x = (width - size) / 2
            val y = (height - size) / 2
            g.color = color
            g.fillRoundRect(x, y, size, size, JBUI.scale(8), JBUI.scale(8))
            if (isSelected || hasFocus() || model.isRollover) {
                g.color = if (hasFocus()) JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                    else if (isSelected) foreground else JBColor.border()
                g.stroke = BasicStroke(JBUI.scale(if (isSelected || hasFocus()) 2 else 1).toFloat())
                val padding = JBUI.scale(3)
                g.drawRoundRect(x - padding, y - padding, size + padding * 2, size + padding * 2, JBUI.scale(12), JBUI.scale(12))
            }
            if (isSelected) {
                g.color = iconBackground(color)
                g.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(x + JBUI.scale(7), y + JBUI.scale(14), x + JBUI.scale(12), y + JBUI.scale(19))
                g.drawLine(x + JBUI.scale(12), y + JBUI.scale(19), x + JBUI.scale(21), y + JBUI.scale(9))
            }
        } finally { g.dispose() }
    }
}

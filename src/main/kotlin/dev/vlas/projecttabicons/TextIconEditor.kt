package dev.vlas.projecttabicons

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class TextIconEditor(project: Project, initial: TextIconSpec?, color: Color, private val changed: () -> Unit) : JPanel(GridBagLayout()) {
    val textField = JBTextField(initial?.text ?: "DB", 12)
    private val style = ComboBox(TextIconStyle.entries.toTypedArray())
    private val fontFamily = ComboBox(TextIconFont.entries.toTypedArray())
    private val hex = JBTextField(initial?.colorHex ?: color.hex(), 10)
    private val errorLabel = JBLabel(" ").apply { foreground = JBColor.RED }
    private var choice: IconChoice? = null
    private var updatingColor = false
    var validationError: String? = null
        private set

    init {
        border = JBUI.Borders.empty(20, 8)
        style.selectedItem = initial?.style ?: TextIconStyle.BOLD
        fontFamily.selectedItem = initial?.font ?: TextIconFont.SANS
        textField.accessibleContext.accessibleName = "Text icon label"
        style.accessibleContext.accessibleName = "Text icon style"
        fontFamily.accessibleContext.accessibleName = "Text icon font"
        hex.accessibleContext.accessibleName = "Text icon color (HEX)"
        fun row(index: Int, title: String, view: JComponent) {
            add(JBLabel(title).apply { labelFor = view }, GridBagConstraints().apply {
                gridx = 0; gridy = index; anchor = GridBagConstraints.WEST; insets = JBUI.insets(0, 0, 12, 16)
            })
            add(view, GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST; insets = JBUI.insetsBottom(12)
            })
        }
        row(0, "Text:", textField)
        row(1, "Style:", style)
        row(2, "Font:", fontFamily)
        val more = JButton("More colors…").apply {
            addActionListener {
                ColorChooserService.getInstance().showDialog(project, this@TextIconEditor, "Text icon color",
                    parseIconColor(hex.text), enableOpacity = false)?.let(::chooseColor)
            }
        }
        row(3, "Color:", JPanel(java.awt.BorderLayout(JBUI.scale(8), 0)).apply {
            add(hex, java.awt.BorderLayout.CENTER); add(more, java.awt.BorderLayout.EAST)
        })
        val palette = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
            COLOR_PRESETS.forEach { preset ->
                add(JButton(ColorSwatch(preset.bright)).apply {
                    toolTipText = preset.name
                    accessibleContext.accessibleName = "Text icon color: ${preset.name}"
                    preferredSize = JBUI.size(36, 30)
                    addActionListener { chooseColor(preset.bright) }
                })
            }
        }
        row(4, "", palette)
        add(settingsHint("1–$MAX_TEXT_ICON_LENGTH characters · DB, QA, API, αβ · Transparent background"), GridBagConstraints().apply {
            gridx = 0; gridy = 5; gridwidth = 2; anchor = GridBagConstraints.WEST
        })
        add(errorLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 6; gridwidth = 2; fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST; insets = JBUI.insetsTop(12)
        })
        add(JPanel(), GridBagConstraints().apply { gridx = 0; gridy = 7; weighty = 1.0 })
        textField.document.addDocumentListener(onTextChanged { refresh() })
        hex.document.addDocumentListener(onTextChanged { if (!updatingColor) refresh() })
        style.addActionListener { refresh() }
        fontFamily.addActionListener { refresh() }
        refresh(notify = false)
    }

    private fun chooseColor(color: Color) {
        updatingColor = true
        try { hex.text = color.hex() } finally { updatingColor = false }
        refresh()
    }

    private fun refresh(notify: Boolean = true) {
        val value = textField.text.trim()
        val color = parseIconColor(hex.text)
        validationError = textIconError(value) ?: if (color == null) "Enter a six-digit HEX color, for example #60A5FA." else null
        choice = if (validationError != null || color == null) null else try {
            IconChoice(TextIconSpec(value, style.selectedItem as TextIconStyle, fontFamily.selectedItem as TextIconFont,
                color.hex()).asset(), color)
        } catch (error: IllegalArgumentException) {
            validationError = error.message ?: "Enter visible characters."
            null
        }
        errorLabel.text = validationError ?: " "
        if (notify) changed()
    }

    fun selection(): IconChoice? = choice
}

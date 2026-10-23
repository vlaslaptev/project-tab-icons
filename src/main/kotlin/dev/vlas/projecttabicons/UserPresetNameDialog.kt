package dev.vlas.projecttabicons

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class UserPresetNameDialog(
    parent: JComponent,
    private val presets: List<UserAppearancePreset>,
    private val existing: UserAppearancePreset? = null,
) : DialogWrapper(parent, true) {
    private val nameField = JBTextField(existing?.name.orEmpty(), 28)

    init {
        title = if (existing == null) "Save as new preset" else "Rename preset"
        setOKButtonText(if (existing == null) "Save preset" else "Rename")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel("Preset name:").apply { labelFor = nameField }, BorderLayout.NORTH)
        nameField.accessibleContext.accessibleName = "Preset name"
        add(nameField, BorderLayout.CENTER)
        add(settingsHint("Style, Tab layout and Group layout · Saved with Apply"), BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        if (existing == null && presets.size >= MAX_USER_APPEARANCE_PRESETS) {
            return ValidationInfo("You can save up to $MAX_USER_APPEARANCE_PRESETS user presets.", nameField)
        }
        return userPresetNameError(nameField.text, presets, existing?.id)?.let { ValidationInfo(it, nameField) }
    }

    fun presetName(): String = normalizeUserPresetName(nameField.text)
}

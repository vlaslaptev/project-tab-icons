package dev.vlas.projecttabicons

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class StyleBundleDialog(parent: JComponent, private val bundle: StyleBundle, importing: Boolean) : DialogWrapper(parent, true) {
    private val presets = bundle.presets.map { it to styleBundleCheckBox(it.name) }
    private val categories = bundle.categories.map { it to styleBundleCheckBox("${it.name} · ${it.icons.size} icons") }
    private val conflicts = ComboBox(StyleConflict.entries.toTypedArray()).apply { accessibleContext.accessibleName = "Import name conflicts" }
    private val importing = importing
    init {
        title = if (importing) "Import styles" else "Export styles"
        setOKButtonText(if (importing) "Import selected" else "Export selected")
        init()
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
        preferredSize = JBUI.size(490, 350)
        add(settingsHint(if (importing) "Choose items to add · Saved with Apply" else "Portable presets and SVGs · No project paths or assignments"), BorderLayout.NORTH)
        add(JBScrollPane(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (presets.isNotEmpty()) add(JBLabel("Appearance presets").apply { border = JBUI.Borders.empty(8) })
            presets.forEach { (_, check) -> add(check) }
            if (categories.isNotEmpty()) add(JBLabel("Icon categories (including editable text icons)").apply { border = JBUI.Borders.empty(8) })
            categories.forEach { (_, check) -> add(check) }
        }), BorderLayout.CENTER)
        if (importing) add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JBLabel("Matching names:").apply { labelFor = conflicts }, BorderLayout.WEST)
            add(conflicts, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }
    override fun doValidate(): ValidationInfo? = if (selected().empty) ValidationInfo("Select at least one item.") else null
    fun selected() = StyleBundle(presets.filter { it.second.isSelected }.map { it.first }, categories.filter { it.second.isSelected }.map { it.first })
    fun conflict() = conflicts.selectedItem as StyleConflict
}

package dev.vlas.projecttabicons

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/** Изменения менеджера становятся частью черновика Settings только после Done. */
internal class UserPresetManagerDialog(parent: JComponent, presets: List<UserAppearancePreset>) : DialogWrapper(parent, true) {
    private val model = DefaultListModel<UserAppearancePreset>().apply { presets.forEach(::addElement) }
    private val list = JBList(model).apply {
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "User presets in display order"
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean) =
                super.getListCellRendererComponent(list, (value as? UserAppearancePreset)?.name, index, selected, focus)
        }
    }
    private val rename = JButton("Rename…")
    private val duplicate = JButton("Duplicate")
    private val delete = JButton("Delete")
    private val up = JButton("Move up")
    private val down = JButton("Move down")
    init {
        title = "Manage user presets"
        setOKButtonText("Done")
        list.addListSelectionListener { updateButtons() }
        rename.addActionListener {
            val index = list.selectedIndex
            val preset = list.selectedValue ?: return@addActionListener
            val dialog = UserPresetNameDialog(list, result(), preset)
            if (dialog.showAndGet()) model.set(index, preset.copy(name = dialog.presetName()))
        }
        duplicate.addActionListener {
            val preset = list.selectedValue ?: return@addActionListener
            val names = result()
            var suffix = 2
            var name = preset.name.take(24) + " copy"
            while (userPresetNameError(name, names) != null) name = preset.name.take(22) + " copy ${suffix++}"
            model.add(list.selectedIndex + 1, preset.copy(id = UUID.randomUUID().toString(), name = name))
            list.selectedIndex += 1
            updateButtons()
        }
        delete.addActionListener {
            val index = list.selectedIndex
            if (index >= 0) { model.remove(index); list.selectedIndex = minOf(index, model.size() - 1) }
            updateButtons()
        }
        up.addActionListener { move(-1) }
        down.addActionListener { move(1) }
        init()
        if (!model.isEmpty) list.selectedIndex = 0
        updateButtons()
    }
    private fun move(delta: Int) {
        val index = list.selectedIndex
        val next = index + delta
        if (index < 0 || next !in 0 until model.size()) return
        val value = model.remove(index); model.add(next, value); list.selectedIndex = next
    }
    private fun updateButtons() {
        val index = list.selectedIndex
        rename.isEnabled = index >= 0; delete.isEnabled = index >= 0
        duplicate.isEnabled = index >= 0 && model.size() < MAX_USER_APPEARANCE_PRESETS
        up.isEnabled = index > 0; down.isEnabled = index >= 0 && index < model.size() - 1
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(8))).apply {
        preferredSize = JBUI.size(480, 300)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(java.awt.GridLayout(0, 1, 0, JBUI.scale(6))).apply {
            listOf(rename, duplicate, up, down, delete).forEach(::add)
        }, BorderLayout.EAST)
        add(settingsHint("Order is shared by Settings and quick actions · Saved with Apply"), BorderLayout.SOUTH)
    }
    override fun getPreferredFocusedComponent(): JComponent = list
    fun result(): List<UserAppearancePreset> = (0 until model.size()).map(model::get)
}

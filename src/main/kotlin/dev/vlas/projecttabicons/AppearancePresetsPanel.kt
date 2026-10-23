package dev.vlas.projecttabicons

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.util.UUID
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke

/** Именованные пресеты и память Custom живут в черновике до Apply. */
internal class AppearancePresetsPanel(
    private val dialogParent: JComponent,
    private val availableWidth: () -> Int,
    private val currentAppearance: () -> TabAppearanceState,
    private val displayedAppearance: () -> TabAppearanceState,
    private val applyAppearance: (TabAppearanceState) -> Unit,
    private val changed: () -> Unit,
    private val layoutChanged: () -> Unit,
) {
    private val presetTitle = JBLabel("Presets").apply { font = font.deriveFont(Font.BOLD) }
    private val userPresetTitle = JBLabel("User presets").apply { font = font.deriveFont(Font.BOLD) }
    private val presetButtons = TabAppearancePreset.entries.associateWith { JBRadioButton(it.title) }
    private val presetGroup = ButtonGroup()
    private val userPresetButtons = linkedMapOf<String, JBRadioButton>()
    private val userPresetMarkers = linkedMapOf<String, SettingsPendingChange>()
    private val presetRow = JPanel(BorderLayout(0, JBUI.scale(4))).apply { isOpaque = false }
    private val updatePreset = JButton("Update preset")
    private val managePresets = JButton("Manage user presets…")
    private var editingUserPresetId: String? = null
    private var presetManagerDialog: UserPresetManagerDialog? = null
    private val savePreset = JButton("Save as new preset")
    val actions: JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(savePreset)
        add(updatePreset)
    }
    private var lastCustomAppearance = TabAppearanceState.defaults().asCustomAppearance()
    private var customAppearanceBaseline = lastCustomAppearance
    private data class EditOrigin(val appearance: TabAppearanceState, val customAppearance: TabAppearanceState)
    private var editOrigin: EditOrigin? = null
    private var userPresetDraft = emptyList<UserAppearancePreset>()
    private var userPresetBaseline = emptyList<UserAppearancePreset>()
    private var canSaveAsPreset = false
    private var presetNameDialog: UserPresetNameDialog? = null
    private var disposed = false
    private var syncing = false
    private val presetMarker: SettingsPendingChange
    private val customMarker: SettingsPendingChange
    val component: JComponent get() = presetRow
    val selectedPreset: TabAppearancePreset get() =
        presetButtons.entries.firstOrNull { it.value.isSelected }?.key ?: TabAppearancePreset.CUSTOM
    val selectedUserPresetId: String? get() = userPresetButtons.entries.firstOrNull { it.value.isSelected }?.key
    val presetsModified: Boolean get() = userPresetDraft != userPresetBaseline
    val customModified: Boolean get() = lastCustomAppearance != customAppearanceBaseline
    val modified: Boolean get() = presetsModified || customModified

    init {
        presetButtons.values.forEach(presetGroup::add)
        presetButtons.forEach { (preset, button) ->
            button.accessibleContext.accessibleName = "Appearance preset: ${preset.title}"
            button.toolTipText = when (preset) {
                TabAppearancePreset.CUSTOM -> "Restore your last custom appearance. Editing a setting updates Custom; Apply saves it for the next session."
                TabAppearancePreset.DEFAULT -> "Medium icons, bottom-only 2 px outline, 33 px height, IDE font, Git branch on the right and no tab fill."
                TabAppearancePreset.FILLED -> "Small 20 px icons, full 1 px outline, 12% tab fill with a 3 px inset, and 34 px tab height."
                TabAppearancePreset.BOLD -> "Large icons, full 4 px outline, 16 px text and 38 px tab height."
                TabAppearancePreset.GIT -> "Default appearance without icons, with Git Branch Compact and changed-line counts on the right."
                TabAppearancePreset.DZEN -> "No icons or tab fill, bottom-only 1 px outline, 14 px text and 33 px height."
            }
            button.addActionListener {
                if (!syncing) {
                    rememberCurrentCustom()
                    editOrigin = null
                    editingUserPresetId = null
                    canSaveAsPreset = preset == TabAppearancePreset.CUSTOM
                    val next = if (preset == TabAppearancePreset.CUSTOM) lastCustomAppearance.copy(enabled = currentAppearance().enabled)
                        else TabAppearanceState.forPreset(preset, currentAppearance().enabled).withGroupLayoutFrom(displayedAppearance())
                    applyAppearance(next)
                    changed()
                }
            }
        }
        savePreset.addActionListener { saveUserPreset() }
        updatePreset.addActionListener {
            if (!updatePreset.isEnabled) return@addActionListener
            val id = editingUserPresetId ?: return@addActionListener
            val current = currentAppearance()
            userPresetDraft = userPresetDraft.map { if (it.id == id) it.copy(appearance = current.asCustomAppearance()) else it }
            editOrigin = null
            canSaveAsPreset = false
            rebuildUserPresetButtons()
            applyAppearance(current.copy(userPresetId = id))
            changed()
        }
        managePresets.addActionListener {
            val dialog = UserPresetManagerDialog(dialogParent, userPresetDraft)
            presetManagerDialog = dialog
            try { if (dialog.showAndGet() && !disposed) replaceUserPresets(dialog.result()) }
            finally { presetManagerDialog = null }
        }
        presetMarker = SettingsPendingChange(presetTitle)
        customMarker = SettingsPendingChange(presetButtons.getValue(TabAppearancePreset.CUSTOM))
        rebuildPresetRow()
    }

    private fun rebuildPresetRow() {
        presetRow.removeAll()
        val userChoices = userPresetButtons.values.toList()
        val titleWidth = { maxOf(presetTitle.preferredSize.width,
            if (userChoices.isNotEmpty()) userPresetTitle.preferredSize.width else 0) }
        presetRow.add(SettingsRadioRow(presetTitle, presetButtons.values.toList(), wrapChoices = true,
            availableWidth = availableWidth, titleWidth = titleWidth), BorderLayout.NORTH)
        if (userChoices.isNotEmpty()) {
            lateinit var userRow: SettingsActionRow
            val choices = SettingsRadioRow(userPresetTitle, userChoices, wrapChoices = true,
                availableWidth = { userRow.contentWidth() }, titleWidth = titleWidth)
            userRow = SettingsActionRow(choices, managePresets, availableWidth,
                minimumContentWidth = { maxOf(titleWidth(), userChoices.maxOf { it.preferredSize.width }) })
            presetRow.add(userRow, BorderLayout.CENTER)
        }
        presetRow.revalidate()
        presetRow.repaint()
        layoutChanged()
    }

    private fun refreshSavePresetAction(current: TabAppearanceState) {
        val canSave = canSaveAsPreset && current.preset == TabAppearancePreset.CUSTOM && current.userPresetId == null
        val editedPreset = userPresetDraft.firstOrNull { it.id == editingUserPresetId }
        updatePreset.isEnabled = current.enabled && canSave && editedPreset != null && editedPreset.appearance != current.asCustomAppearance()
        updatePreset.toolTipText = if (editedPreset == null) "Edit a user preset to update it · Saved with Apply"
            else "Replace ${editedPreset.name} with the current appearance · Saved with Apply"
        managePresets.isEnabled = current.enabled
        savePreset.isEnabled = current.enabled && canSave && userPresetDraft.size < MAX_USER_APPEARANCE_PRESETS
        savePreset.toolTipText = if (userPresetDraft.size >= MAX_USER_APPEARANCE_PRESETS)
            "All $MAX_USER_APPEARANCE_PRESETS user preset slots are used. Right-click a user preset to delete it, then save a new one."
        else "Save Style, Tab layout and Group layout as a named preset · ${userPresetDraft.size} of $MAX_USER_APPEARANCE_PRESETS slots used · Apply to keep it"
    }

    private fun rebuildUserPresetButtons() {
        userPresetButtons.values.forEach(presetGroup::remove)
        userPresetButtons.clear()
        userPresetMarkers.clear()
        userPresetDraft.forEach { preset ->
            val button = userPresetRadioButton(preset.name).apply {
                isOpaque = false
                accessibleContext.accessibleName = "User appearance preset: ${preset.name}"
                toolTipText = "User preset · ${settingsLiteral(preset.name)} · Right-click or Shift+F10 to rename or delete · Apply to save changes"
                addActionListener {
                    if (!syncing) {
                        rememberCurrentCustom()
                        editOrigin = null
                        canSaveAsPreset = false
                        editingUserPresetId = preset.id
                        applyAppearance(preset.appearance.copy(enabled = currentAppearance().enabled, userPresetId = preset.id))
                        changed()
                    }
                }
            }
            val menu = JPopupMenu().apply {
                add(JMenuItem("Rename preset…").apply { addActionListener { renameUserPreset(preset.id) } })
                add(JMenuItem("Delete preset").apply { addActionListener { deleteUserPreset(preset.id) } })
            }
            button.componentPopupMenu = menu
            button.inputMap.put(KeyStroke.getKeyStroke("shift F10"), "userPresetMenu")
            button.actionMap.put("userPresetMenu", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    if (button.isEnabled && button.isShowing) menu.show(button, 0, button.height)
                }
            })
            presetGroup.add(button)
            userPresetButtons[preset.id] = button
            userPresetMarkers[preset.id] = SettingsPendingChange(button)
        }
        rebuildPresetRow()
    }

    private fun saveUserPreset() {
        if (!savePreset.isEnabled || !savePreset.isVisible || userPresetDraft.size >= MAX_USER_APPEARANCE_PRESETS) return
        val name = askPresetName() ?: return
        val current = currentAppearance()
        val preset = UserAppearancePreset(UUID.randomUUID().toString(), name, current.asCustomAppearance())
        userPresetDraft = userPresetDraft + preset
        editOrigin = null
        editingUserPresetId = preset.id
        canSaveAsPreset = false
        rebuildUserPresetButtons()
        applyAppearance(current.copy(preset = TabAppearancePreset.CUSTOM, userPresetId = preset.id))
        changed()
    }

    private fun renameUserPreset(id: String) {
        val preset = userPresetDraft.firstOrNull { it.id == id } ?: return
        val name = askPresetName(preset) ?: return
        val current = currentAppearance()
        userPresetDraft = userPresetDraft.map { if (it.id == id) it.copy(name = name) else it }
        rebuildUserPresetButtons()
        applyAppearance(current)
        changed()
    }

    private fun deleteUserPreset(id: String) {
        val current = currentAppearance()
        userPresetDraft = userPresetDraft.filterNot { it.id == id }
        editOrigin = validEditOrigin(editOrigin)
        if (editingUserPresetId == id) editingUserPresetId = null
        val next = if (current.userPresetId == id) {
            lastCustomAppearance = current.asCustomAppearance()
            canSaveAsPreset = true
            lastCustomAppearance.copy(enabled = current.enabled)
        } else current
        rebuildUserPresetButtons()
        applyAppearance(next)
        changed()
    }

    private fun askPresetName(existing: UserAppearancePreset? = null): String? {
        if (disposed) return null
        val dialog = UserPresetNameDialog(dialogParent, userPresetDraft, existing)
        presetNameDialog = dialog
        return try {
            if (dialog.showAndGet() && !disposed) dialog.presetName() else null
        } finally { presetNameDialog = null }
    }

    private fun rememberCurrentCustom() {
        // При клике радиокнопка уже переключена; прежний пресет берём из отображённого состояния.
        val current = displayedAppearance()
        if (current.preset == TabAppearancePreset.CUSTOM && current.userPresetId == null) {
            lastCustomAppearance = current.asCustomAppearance()
        }
    }

    fun replaceUserPresets(value: List<UserAppearancePreset>) {
        val current = currentAppearance()
        userPresetDraft = value.toList()
        editOrigin = validEditOrigin(editOrigin)
        val active = value.firstOrNull { it.id == current.userPresetId }
        if (value.none { it.id == editingUserPresetId }) editingUserPresetId = null
        val next = if (current.userPresetId != null && active == null) {
            lastCustomAppearance = current.asCustomAppearance()
            canSaveAsPreset = true
            current.copy(userPresetId = null, preset = TabAppearancePreset.CUSTOM)
        } else active?.appearance?.copy(enabled = current.enabled, userPresetId = active.id) ?: current
        rebuildUserPresetButtons()
        applyAppearance(next)
        changed()
    }

    fun customAppearance(): TabAppearanceState = lastCustomAppearance
    fun userPresets(): List<UserAppearancePreset> = userPresetDraft.toList()
    fun select(value: TabAppearanceState) {
        syncing = true
        try {
            presetButtons.forEach { (preset, button) -> button.isSelected = value.userPresetId == null && preset == value.preset }
            userPresetButtons.forEach { (id, button) -> button.isSelected = id == value.userPresetId }
        } finally { syncing = false }
    }

    fun reset(saved: TabAppearanceState, presets: List<UserAppearancePreset>, rememberedCustom: TabAppearanceState?,
              preserveEditing: Boolean = false): TabAppearanceState {
        val keepEditing = preserveEditing && saved == currentAppearance().normalized()
        val previousCanSave = canSaveAsPreset
        val previousEditingId = editingUserPresetId
        val previousEditOrigin = editOrigin
        userPresetDraft = presets.toList()
        userPresetBaseline = userPresetDraft
        rebuildUserPresetButtons()
        val baseline = if (saved.userPresetId != null && userPresetDraft.none { it.id == saved.userPresetId })
            saved.copy(preset = TabAppearancePreset.CUSTOM, userPresetId = null) else saved
        lastCustomAppearance = if (baseline.preset == TabAppearancePreset.CUSTOM && baseline.userPresetId == null)
            baseline.asCustomAppearance() else rememberedCustom ?: baseline.asCustomAppearance()
        customAppearanceBaseline = lastCustomAppearance
        // Apply собственного оформления сохраняет доступность Save/Update; внешнее оформление её сбрасывает.
        canSaveAsPreset = keepEditing && previousCanSave
        editingUserPresetId = if (keepEditing) previousEditingId?.takeIf { id -> presets.any { it.id == id } }
            else baseline.userPresetId
        // После Apply память Custom уже сохранена: возврат к пресету не должен откатывать её.
        editOrigin = if (keepEditing) validEditOrigin(previousEditOrigin?.copy(customAppearance = lastCustomAppearance)) else null
        return baseline
    }

    private fun validEditOrigin(origin: EditOrigin?): EditOrigin? = origin?.takeIf {
        val id = it.appearance.userPresetId
        id == null || userPresetDraft.firstOrNull { preset -> preset.id == id }?.appearance == it.appearance.asCustomAppearance()
    }

    private fun appearanceForComparison(value: TabAppearanceState): TabAppearanceState =
        value.asCustomAppearance().let { it.copy(tabFontSize = it.tabFontSize.takeUnless { size -> size == 0 } ?: defaultTabFontSize()) }

    fun edited(current: TabAppearanceState) {
        val previous = displayedAppearance()
        if (previous.userPresetId != null || previous.preset != TabAppearancePreset.CUSTOM) {
            editOrigin = EditOrigin(previous, lastCustomAppearance)
        }
        val origin = validEditOrigin(editOrigin)
        if (origin != null && appearanceForComparison(current) == appearanceForComparison(origin.appearance)) {
            // Сравниваем все поля оформления, а не только последний изменённый контрол.
            lastCustomAppearance = origin.customAppearance
            editingUserPresetId = origin.appearance.userPresetId
            canSaveAsPreset = false
            editOrigin = null
            // Возвращаем и способ хранения шрифта: фиксированный размер может совпадать с IDE.
            applyAppearance(origin.appearance.copy(enabled = current.enabled))
            return
        }
        previous.userPresetId?.let { editingUserPresetId = it }
        presetButtons.getValue(TabAppearancePreset.CUSTOM).isSelected = true
        lastCustomAppearance = current.asCustomAppearance()
        canSaveAsPreset = true
    }

    fun restoreDefaults() {
        rememberCurrentCustom()
        editOrigin = null
        canSaveAsPreset = false
        editingUserPresetId = null
        applyAppearance(TabAppearanceState.defaults().copy(enabled = currentAppearance().enabled))
        changed()
    }

    fun refresh(current: TabAppearanceState) {
        presetTitle.isEnabled = current.enabled
        userPresetTitle.isEnabled = current.enabled
        presetButtons.values.forEach { it.isEnabled = current.enabled }
        userPresetButtons.values.forEach { it.isEnabled = current.enabled }
        refreshSavePresetAction(current)
    }

    fun refreshPendingChanges(current: TabAppearanceState, baseline: TabAppearanceState) {
        presetMarker.update(current.preset != baseline.preset || current.userPresetId != baseline.userPresetId || presetsModified)
        customMarker.update(customModified)
        userPresetMarkers.forEach { (id, marker) ->
            marker.update(userPresetDraft.firstOrNull { it.id == id } != userPresetBaseline.firstOrNull { it.id == id })
        }
    }

    fun disposeDialogs() {
        disposed = true
        presetNameDialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        presetNameDialog = null
        presetManagerDialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        presetManagerDialog = null
    }
}

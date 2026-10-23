package dev.vlas.projecttabicons

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/** Собирает форму, пресеты и таблицы; синхронизирует общий черновик и preview. */
internal class IconSettingsPanel(
    chooseIcon: (ProjectIconSettingsRow) -> Unit,
    chooseColor: (ProjectIconSettingsRow, ProjectColorTarget) -> Unit,
    reloadRow: (ProjectIconSettingsRow) -> Unit,
    private val modifiedChanged: () -> Unit,
    importStyles: () -> Unit,
    exportStyles: () -> Unit,
    chooseGroupIcon: (String) -> Unit,
    chooseGroupColor: (String, ProjectColorTarget) -> Unit,
) {
    val component = JPanel(BorderLayout(0, JBUI.scale(20)))
    private val page = SettingsPageBody()
    private val pageScroll = JBScrollPane(page).apply {
        border = JBUI.Borders.empty()
        minimumSize = JBUI.size(0, 0)
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(32)
        accessibleContext.accessibleName = "Tab customization settings page"
    }
    private val projectTables = SettingsGroupSwitcher("Project tables").apply {
        accessibleContext.accessibleName = "Projects and project groups"
    }
    private val importStylesButton = JButton("Import styles…").apply { addActionListener { importStyles() } }
    private val exportStylesButton = JButton("Export styles…").apply { addActionListener { exportStyles() } }
    private val status = settingsHint("Loading open projects…")
    private val preview = WindowTabsPreview()
    private val previewTabCounts = PreviewTabCount.entries.associateWith { count ->
        JBRadioButton(count.title, count == PreviewTabCount.THREE).apply {
            isOpaque = false
            accessibleContext.accessibleName = "Preview tab count: ${count.title}"
            toolTipText = "Changes only the example window. Does not change real project tabs or Apply."
        }
    }
    private val previewTheme = ComboBox(PreviewIdeTheme.entries.toTypedArray()).apply {
        preferredSize = JBUI.size(270, 30)
        accessibleContext.accessibleName = "IDE theme for this preview window"
        accessibleContext.accessibleDescription = "Changes only the example window. Does not change settings or the IDE theme."
        toolTipText = "Preview only. Does not change the IDE theme, appearance settings or Apply."
    }
    private val pluginEnabled = JBCheckBox("Enable tab customization", true).apply {
        font = font.deriveFont(Font.BOLD)
        toolTipText = "Turn tab customization on or off for all projects. Saved project icons are kept."
    }
    private val restoreDefaults = JButton("Restore defaults").apply {
        toolTipText = "Restore default tab appearance. Project icons, colors and the global switch stay unchanged."
    }
    private var syncing = false
    private var displayedAppearance = TabAppearanceState.defaults()
    private var appearanceBaseline = TabAppearanceState.defaults()
    private lateinit var sharedSettingsBaseline: SharedIconSettingsSnapshot
    private lateinit var sharedDraftBaseline: SharedIconSettingsSnapshot
    private var importedCategoryCount = 0
    private val pendingMarkers = mutableListOf<Pair<SettingsPendingChange, (TabAppearanceState) -> Boolean>>()
    private val groups = ProjectGroupsPanel(::pageWidth, ::refreshRows,
        { projectTables.selectTab("Projects") }, chooseGroupIcon, chooseGroupColor)
    private val appearanceForm = TabAppearanceForm(::pageWidth, { appearanceChanged() }, ::refreshLayout)
    private val presets = AppearancePresetsPanel(component, ::pageWidth, ::appearance,
        { displayedAppearance }, ::setAppearance, ::updateStatus, ::refreshLayout)
    private val projectTable = ProjectIconsTable(groups, chooseIcon, chooseColor, reloadRow)
    var rows: List<ProjectIconSettingsRow> = emptyList()
        private set

    val modified: Boolean get() = appearance() != appearanceBaseline || presets.modified ||
        groups.modified || rows.any { it.modified }
    val preferredFocus: JComponent get() = pluginEnabled

    init {
        appearanceForm.setPresetActions(presets.actions)
        ButtonGroup().apply { previewTabCounts.values.forEach(::add) }
        component.border = JBUI.Borders.empty(16)
        component.preferredSize = JBUI.size(880, 700)
        component.minimumSize = JBUI.size(360, 400)
        page.add(JPanel(BorderLayout(0, JBUI.scale(20))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
                add(presets.component, BorderLayout.NORTH)
                add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                    add(SettingsPreviewHeader(
                        SettingsRadioRow(JBLabel("Preview").apply { font = font.deriveFont(Font.BOLD) },
                            previewTabCounts.values.toList(), availableWidth = ::pageWidth),
                        JBLabel("IDE theme for this preview window:").apply { labelFor = previewTheme },
                        previewTheme, ::pageWidth,
                    ), BorderLayout.NORTH)
                    add(preview, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    add(pluginEnabled, BorderLayout.WEST)
                    add(JPanel(GridBagLayout()).apply {
                        add(JSeparator(SwingConstants.HORIZONTAL), GridBagConstraints().apply {
                            weightx = 1.0
                            fill = GridBagConstraints.HORIZONTAL
                        })
                    }, BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(appearanceForm.component, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        projectTables.addTab("Projects", projectTable.component)
        projectTables.addTab("Project groups", JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(0, 12, 12, 12)
            add(groups, BorderLayout.CENTER)
        })
        projectTables.addChangeListener { refreshLayout() }
        page.add(object : JPanel(BorderLayout()) {
            override fun getMinimumSize(): Dimension {
                // Единственная видимая таблица получает место для заголовка и строк;
                // длинный список и раскрытый редактор прокручиваются внутри вкладки.
                // Ориентируемся на viewport всей страницы, а не на фиксированные
                // 300 px preferredSize таблицы: редактор участников растёт вместе с Settings.
                return Dimension(0, maxOf(JBUI.scale(440), pageScroll.viewport.extentSize.height - JBUI.scale(80),
                    projectTable.headerHeight + JBUI.scale(220)))
            }
        }.apply {
            add(projectTables, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        component.add(pageScroll, BorderLayout.CENTER)
        pageScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                appearanceForm.component.revalidate()
                page.revalidate()
            }
        })
        component.add(JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(restoreDefaults); add(importStylesButton); add(exportStylesButton)
            }, BorderLayout.WEST)
            status.horizontalAlignment = SwingConstants.RIGHT
            status.minimumSize = Dimension(0, status.preferredSize.height)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        previewTheme.addActionListener { refreshPreviewTheme() }
        previewTabCounts.forEach { (count, button) ->
            button.addActionListener {
                preview.showTabCount(count)
                page.revalidate()
                page.repaint()
            }
        }
        pluginEnabled.addActionListener { appearanceChanged(manualEdit = false) }
        restoreDefaults.addActionListener { presets.restoreDefaults() }
        installAppearanceMarkers()
        resetAppearance()
    }

    fun refreshPreviewTheme() {
        try {
            preview.showTheme(previewTheme.selectedItem as PreviewIdeTheme)
            if (appearanceForm.defaultFontChanged()) {
                setAppearance(appearance())
            }
            previewTheme.repaint()
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Exception) {
            Logger.getInstance(IconSettingsPanel::class.java).warn("Cannot preview IDE theme", error)
            previewTheme.selectedItem = PreviewIdeTheme.CURRENT
            previewTheme.toolTipText = "This theme could not be previewed. Showing the current IDE theme."
        }
    }

    private fun pageWidth(): Int = pageScroll.viewport.extentSize.width.takeIf { it > 0 } ?: JBUI.scale(760)

    private fun refreshLayout() {
        page.revalidate()
        page.repaint()
    }

    private fun installAppearanceMarkers() {
        fun watch(component: JComponent, value: (TabAppearanceState) -> Any?) {
            pendingMarkers += SettingsPendingChange(component) to { current: TabAppearanceState -> value(current) != value(appearanceBaseline) }
        }
        watch(pluginEnabled) { it.enabled }
        appearanceForm.installMarkers(::watch)
    }

    fun projectGroups(): ProjectGroupsState = groups.draft
    fun updateGroup(id: String, transform: (ProjectTabGroup) -> ProjectTabGroup) = groups.updateGroup(id, transform)
    fun replaceUserPresets(value: List<UserAppearancePreset>) = presets.replaceUserPresets(value)
    fun customAppearance(): TabAppearanceState = presets.customAppearance()
    fun userPresets(): List<UserAppearancePreset> = presets.userPresets()
    fun showImportedCategories(count: Int) {
        importedCategoryCount = count
        updateStatus()
    }

    fun appearance(): TabAppearanceState = appearanceForm.value().copy(
        enabled = pluginEnabled.isSelected,
        preset = presets.selectedPreset,
        userPresetId = presets.selectedUserPresetId,
    )

    fun resetAppearance(preserveEditing: Boolean = false) {
        val saved = SharedIconSettingsStore().snapshot()
        if (preserveEditing && saved.projectGroups == groups.draft) groups.saved()
        else groups.reset(saved.projectGroups)
        appearanceBaseline = presets.reset(
            saved.appearance, saved.userPresets, saved.customAppearance,
            preserveEditing = preserveEditing,
        )
        setAppearance(appearanceBaseline)
        sharedSettingsBaseline = saved
        sharedDraftBaseline = sharedDraft()
    }

    private fun sharedDraft() = SharedIconSettingsSnapshot(appearance(), customAppearance(), userPresets(), projectGroups())

    fun sharedSettingsChanges() = SharedIconSettingsChanges(sharedSettingsBaseline, sharedDraftBaseline, sharedDraft())

    private fun setAppearance(value: TabAppearanceState) {
        syncing = true
        try {
            pluginEnabled.isSelected = value.enabled
            presets.select(value)
            appearanceForm.setValue(value)
        } finally {
            syncing = false
        }
        updateAppearanceEnabled()
        refreshAppearancePreview()
        refreshPendingChanges()
    }

    private fun updateAppearanceEnabled() {
        val current = appearance()
        presets.refresh(current)
        appearanceForm.refreshEnabled(current.enabled)
        projectTable.setEditingEnabled(current.enabled)
        restoreDefaults.isEnabled = current != TabAppearanceState.defaults().copy(enabled = current.enabled)
    }

    private fun refreshAppearancePreview() {
        val next = appearance()
        val lettersChanged = next.iconInitials != displayedAppearance.iconInitials ||
            next.iconLetterWeight != displayedAppearance.iconLetterWeight
        displayedAppearance = next
        preview.showAppearance(next)
        if (lettersChanged) refreshRows()
    }

    private fun appearanceChanged(manualEdit: Boolean = true) {
        if (syncing) return
        // Пресет — отправная точка: только реальное ручное изменение делает его Custom.
        // Повторный выбор того же значения и начало перетаскивания слайдера — не изменение.
        val current = appearance()
        if (manualEdit && current != displayedAppearance) presets.edited(current)
        updateAppearanceEnabled()
        refreshAppearancePreview()
        updateStatus()
    }

    fun loading() {
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Loading open projects…"
        status.toolTipText = status.text
    }

    fun loaded(value: List<ProjectIconSettingsRow>, artwork: PreviewArtwork) {
        rows = value
        preview.showArtwork(artwork)
        refreshRows()
    }

    fun refreshRows() {
        projectTable.showRows(rows, appearance())
        updateStatus()
    }

    private fun updateStatus() {
        refreshPendingChanges()
        val conflict = rows.any { it.conflicted }
        status.foreground = if (conflict) JBColor.RED else UIUtil.getContextHelpForeground()
        val changed = rows.count { it.modified }
        status.text = when {
            conflict -> "Reload the affected projects before applying changes."
            changed > 0 -> "$changed project${if (changed == 1) "" else "s"} edited · Save with Apply"
            importedCategoryCount > 0 -> "$importedCategoryCount icon categor${if (importedCategoryCount == 1) "y" else "ies"} imported · Save with Apply"
            appearance() != appearanceBaseline -> "Window tabs edited · Save with Apply"
            groups.modified -> "Project groups edited · Save with Apply"
            presets.presetsModified -> "User presets edited · Save with Apply"
            presets.customModified -> "Custom appearance edited · Save with Apply"
            !pluginEnabled.isSelected -> "Tab customization is disabled."
            else -> "Click an icon or color to change it."
        }
        status.toolTipText = status.text
        modifiedChanged()
    }

    fun saved() {
        // Подхватываем сохранённые другим окном разделы, которые этот черновик не менял.
        resetAppearance(preserveEditing = true)
        refreshRows()
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Changes saved."
        status.toolTipText = status.text
    }

    private fun refreshPendingChanges() {
        val current = appearance()
        pendingMarkers.forEach { (marker, changed) -> marker.update(changed(current)) }
        presets.refreshPendingChanges(current, appearanceBaseline)
    }

    fun disposeDialogs() {
        groups.disposeDialogs()
        presets.disposeDialogs()
    }

    fun failed(message: String) {
        status.foreground = JBColor.RED
        status.text = settingsMessage(message)
        status.toolTipText = status.text
    }
}

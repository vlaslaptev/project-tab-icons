package dev.vlas.projecttabicons

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.Timer

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
    private val appearancePage = SettingsPageBody().apply { border = JBUI.Borders.empty(12) }
    private val projectsPage = SettingsPageBody()
    private val groupsPage = SettingsPageBody()
    private fun pageScroll(page: SettingsPageBody, name: String) = JBScrollPane(page).apply {
        border = JBUI.Borders.empty()
        minimumSize = JBUI.size(0, 0)
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(32)
        accessibleContext.accessibleName = name
    }
    private val appearanceScroll = pageScroll(appearancePage, "Tab appearance settings")
    private val projectsScroll = pageScroll(projectsPage, "Open project settings")
    private val groupsScroll = pageScroll(groupsPage, "Project group settings")
    private val presetsHeader = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12)
    }
    private val sections = SettingsGroupSwitcher("Settings sections", availableWidth = {
        component.width - component.insets.left - component.insets.right
    }).apply {
        accessibleContext.accessibleName = "Project Tab Icons settings sections"
    }
    private var selectedSection = "Appearance"
    private val importStylesButton = JButton("Import styles…").apply { addActionListener { importStyles() } }
    private val exportStylesButton = JButton("Export styles…").apply { addActionListener { exportStyles() } }
    private val status = settingsHint("Loading open projects…")
    private val preview = WindowTabsPreview()
    private val fullPreview = JBCheckBox("Full window", false).apply {
        isOpaque = false
        accessibleContext.accessibleName = "Full window preview"
        toolTipText = "Expand the example to include file tabs and the editor. Preview only; does not change Apply."
    }
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
    private val windowOpeningHelp = "Add new project windows to the tabbed window, independently of tab customization. " +
        "Existing separate windows are kept. Choose New Window if IDEA asks where to open a project."
    private val automaticallyOpenInTabs = JBCheckBox("Automatically open projects in tabs", false).apply {
        toolTipText = windowOpeningHelp
    }
    private val windowOpeningMarker = if (SystemInfoRt.isWindows || SystemInfoRt.isMac) SettingsPendingChange(automaticallyOpenInTabs) else null
    private var windowOpeningBaseline = ProjectWindowOpeningState()
    private var macWindowOpeningDraft = false
    private val macPreferenceTimer = Timer(1_000) { if (component.isShowing) refreshWindowOpening() }
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
    private val groups = ProjectGroupsPanel({ pageWidth(projectsScroll) }, ::refreshRows,
        { sections.selectTab("Projects") }, chooseGroupIcon, chooseGroupColor, ::updateStatus)
    private val appearanceForm = TabAppearanceForm({ pageWidth(appearanceScroll) }, { appearanceChanged() }, ::refreshLayout)
    private val presets = AppearancePresetsPanel(component, ::presetsWidth, ::appearance,
        { displayedAppearance }, ::setAppearance, ::updateStatus, ::refreshLayout)
    private val projectTable = ProjectIconsTable(groups, chooseIcon, chooseColor, reloadRow) { id ->
        sections.selectTab("Project groups")
        groups.focusGroup(id)
    }
    var rows: List<ProjectIconSettingsRow> = emptyList()
        private set

    val modified: Boolean get() = appearance() != appearanceBaseline || presets.modified ||
        windowOpening() != windowOpeningBaseline || groups.modified || rows.any { it.modified }
    val preferredFocus: JComponent get() = pluginEnabled

    init {
        appearanceForm.setPresetActions(presets.actions)
        ButtonGroup().apply { previewTabCounts.values.forEach(::add) }
        component.border = JBUI.Borders.empty(16)
        component.preferredSize = JBUI.size(880, 700)
        component.minimumSize = JBUI.size(360, 400)
        component.add(JPanel(BorderLayout(0, JBUI.scale(16))).apply {
            isOpaque = false
            add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
                isOpaque = false
                if (SystemInfoRt.isWindows || SystemInfoRt.isMac) {
                    add(automaticallyOpenInTabs, BorderLayout.NORTH)
                    add(JSeparator(), BorderLayout.CENTER)
                    add(pluginEnabled, BorderLayout.SOUTH)
                } else add(pluginEnabled, BorderLayout.NORTH)
            }, BorderLayout.NORTH)
            presetsHeader.add(presets.component, BorderLayout.CENTER)
            add(presetsHeader, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        appearancePage.add(JPanel(BorderLayout(0, JBUI.scale(20))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(SettingsPreviewHeader(
                    SettingsRadioRow(JBLabel("Preview").apply { font = font.deriveFont(Font.BOLD) },
                        previewTabCounts.values.toList() + fullPreview, wrapChoices = true,
                        availableWidth = { pageWidth(appearanceScroll) }),
                    JBLabel("Preview theme:").apply { labelFor = previewTheme },
                    previewTheme, { pageWidth(appearanceScroll) },
                ), BorderLayout.NORTH)
                add(preview, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(appearanceForm.component, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        projectsPage.add(projectTable.component, BorderLayout.NORTH)
        groupsPage.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 12, 12)
            add(groups, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        sections.addTab("Appearance", appearanceScroll)
        sections.addTab("Projects", projectsScroll)
        sections.addTab("Project groups", groupsScroll)
        sections.addChangeListener {
            selectedSection = sections.selectedTab ?: "Appearance"
            restoreDefaults.isVisible = selectedSection == "Appearance"
            refreshLayout()
            updateStatus()
        }
        component.add(sections, BorderLayout.CENTER)
        presetsHeader.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                invalidateLayout(presetsHeader)
                refreshLayout()
            }
        })
        listOf(appearanceScroll, projectsScroll, groupsScroll).forEach { scroll ->
            scroll.viewport.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    // Вложенные BorderLayout кешируют высоту форм. После переноса
                    // радиокнопок им тоже нужно заново измерить содержимое.
                    (scroll.viewport.view as? Container)?.let(::invalidateLayout)
                    refreshLayout()
                }
            })
        }
        component.add(JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(restoreDefaults); add(importStylesButton); add(exportStylesButton)
            }, BorderLayout.WEST)
            status.horizontalAlignment = SwingConstants.RIGHT
            status.minimumSize = Dimension(0, status.preferredSize.height)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        previewTheme.addActionListener { refreshPreviewTheme() }
        fullPreview.addActionListener {
            preview.showFullWindow(fullPreview.isSelected)
            refreshLayout()
        }
        previewTabCounts.forEach { (count, button) ->
            button.addActionListener {
                preview.showTabCount(count)
                refreshLayout()
            }
        }
        pluginEnabled.addActionListener { appearanceChanged(manualEdit = false) }
        automaticallyOpenInTabs.addActionListener {
            if (!syncing) {
                if (SystemInfoRt.isMac) macWindowOpeningDraft = automaticallyOpenInTabs.isSelected
                updateStatus()
            }
        }
        restoreDefaults.addActionListener { presets.restoreDefaults() }
        installAppearanceMarkers()
        resetAppearance()
        if (SystemInfoRt.isMac) macPreferenceTimer.start()
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

    private fun presetsWidth(): Int {
        val width = presetsHeader.width.takeIf { it > 0 }
            ?: (component.width - component.insets.left - component.insets.right).takeIf { it > 0 }
            ?: JBUI.scale(760)
        return (width - presetsHeader.insets.left - presetsHeader.insets.right).coerceAtLeast(1)
    }

    private fun pageWidth(scroll: JBScrollPane): Int {
        val width = scroll.viewport.extentSize.width.takeIf { it > 0 }
            ?: (sections.width - sections.insets.left - sections.insets.right).takeIf { it > 0 } ?: JBUI.scale(760)
        val insets = (scroll.viewport.view as? JComponent)?.insets
        return (width - (insets?.left ?: 0) - (insets?.right ?: 0)).coerceAtLeast(1)
    }

    private fun refreshLayout() {
        presetsHeader.revalidate()
        appearanceForm.component.revalidate()
        listOf(appearancePage, projectsPage, groupsPage).forEach { it.revalidate(); it.repaint() }
    }

    private fun invalidateLayout(view: Container) {
        view.components.filterIsInstance<Container>().forEach(::invalidateLayout)
        view.invalidate()
    }

    private fun installAppearanceMarkers() {
        fun watch(component: JComponent, value: (TabAppearanceState) -> Any?) {
            pendingMarkers += SettingsPendingChange(component) to { current: TabAppearanceState -> value(current) != value(appearanceBaseline) }
        }
        watch(pluginEnabled) { it.enabled }
        appearanceForm.installMarkers(::watch)
    }

    fun projectGroups(): ProjectGroupsState = groups.draft
    fun commitPendingGroupEdit() {
        groups.commitPendingEdit()?.let { error ->
            sections.selectTab("Project groups")
            throw com.intellij.openapi.options.ConfigurationException(error)
        }
    }
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

    private fun windowOpening(): ProjectWindowOpeningState = when {
        SystemInfoRt.isWindows -> windowOpeningBaseline.copy(automaticallyOpenInTabs = automaticallyOpenInTabs.isSelected)
        SystemInfoRt.isMac -> windowOpeningBaseline.copy(automaticallyOpenInTabsOnMac = macWindowOpeningDraft)
        else -> windowOpeningBaseline.copy()
    }

    private fun refreshWindowOpening() {
        if (!SystemInfoRt.isMac) return
        val preference = service<MacTabbingPreference>()
        preference.refresh()
        // A system-forced checkmark is presentation only, never a saved local opt-in.
        automaticallyOpenInTabs.isSelected = macWindowOpeningDraft || preference.mode == MacTabbingMode.ALWAYS
        automaticallyOpenInTabs.isEnabled = preference.mode != MacTabbingMode.UNKNOWN && preference.mode != MacTabbingMode.ALWAYS
        automaticallyOpenInTabs.toolTipText = when (preference.mode) {
            MacTabbingMode.UNKNOWN -> "Reading macOS window tabbing preference…"
            MacTabbingMode.ALWAYS -> "Enabled by macOS. Your local preference is kept."
            else -> windowOpeningHelp
        }
    }

    fun resetAppearance(preserveEditing: Boolean = false) {
        val saved = SharedIconSettingsStore().snapshot()
        if (preserveEditing && saved.projectGroups == groups.draft) groups.saved()
        else groups.reset(saved.projectGroups)
        appearanceBaseline = presets.reset(
            saved.appearance, saved.userPresets, saved.customAppearance,
            preserveEditing = preserveEditing,
        )
        windowOpeningBaseline = saved.windowOpening
        macWindowOpeningDraft = saved.windowOpening.automaticallyOpenInTabsOnMac
        automaticallyOpenInTabs.isSelected = saved.windowOpening.automaticallyOpenInTabs
        refreshWindowOpening()
        setAppearance(appearanceBaseline)
        sharedSettingsBaseline = saved
        sharedDraftBaseline = sharedDraft()
    }

    private fun sharedDraft() = SharedIconSettingsSnapshot(appearance(), customAppearance(), userPresets(), projectGroups(), windowOpening())

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
            windowOpening() != windowOpeningBaseline -> "Project window opening edited · Save with Apply"
            groups.modified -> "Project groups edited · Save with Apply"
            presets.presetsModified -> "User presets edited · Save with Apply"
            presets.customModified -> "Custom appearance edited · Save with Apply"
            !pluginEnabled.isSelected -> "Tab customization is disabled."
            selectedSection == "Projects" -> "Grouped projects inherit icons and colors · Use Edit group to change them."
            selectedSection == "Project groups" -> "Group icons and colors are inherited by all members."
            else -> "Preview changes here · Save with Apply"
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
        windowOpeningMarker?.update(windowOpening() != windowOpeningBaseline)
        presets.refreshPendingChanges(current, appearanceBaseline)
    }

    fun disposeDialogs() {
        macPreferenceTimer.stop()
        groups.disposeDialogs()
        presets.disposeDialogs()
    }

    fun failed(message: String) {
        status.foreground = JBColor.RED
        status.text = settingsMessage(message)
        status.toolTipText = status.text
    }
}

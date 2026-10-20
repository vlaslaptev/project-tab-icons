package dev.vlas.projecttabicons

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ActionEvent
import java.util.UUID
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JSeparator
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.JSlider
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.SwingConstants

/** Иконка и три независимых цвета остаются прямо в строке проекта. Геометрия заголовка и строк общая. */
internal class IconSettingsPanel(
    private val chooseIcon: (ProjectIconSettingsRow) -> Unit,
    private val chooseColor: (ProjectIconSettingsRow, ProjectColorTarget) -> Unit,
    private val reloadRow: (ProjectIconSettingsRow) -> Unit,
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
    private val appearanceTabs = SettingsGroupSwitcher().apply {
        accessibleContext.accessibleName = "Window tab settings groups"
        isOpaque = false
    }
    private val projects = ProjectRowsPanel()
    private val projectScroll = JBScrollPane(projects)
    private val projectTables = SettingsGroupSwitcher("Project tables").apply {
        accessibleContext.accessibleName = "Projects and project groups"
    }
    private val columns = ProjectColumns()
    private data class ProjectAction(val button: SettingsValueButton, val available: Boolean,
                                     val preserveIconWhenDisabled: Boolean)
    private val projectActions = mutableListOf<ProjectAction>()
    private var projectsEnabled = true
    private val groups = ProjectGroupsPanel(::pageWidth, ::refreshRows,
        { projectTables.selectTab("Projects") }, chooseGroupIcon, chooseGroupColor)
    private val importStylesButton = JButton("Import styles…").apply { addActionListener { importStyles() } }
    private val exportStylesButton = JButton("Export styles…").apply { addActionListener { exportStyles() } }
    private val countLabel = JBLabel("Open projects").apply { font = font.deriveFont(Font.BOLD) }
    private val status = settingsHint("Loading open projects…")
    private val preview = WindowTabsPreview()
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
    private val presetTitle = JBLabel("Presets").apply { font = font.deriveFont(Font.BOLD) }
    private val userPresetTitle = JBLabel("User presets").apply { font = font.deriveFont(Font.BOLD) }
    private val presetButtons = TabAppearancePreset.entries.associateWith { JBRadioButton(it.title) }
    private val presetGroup = ButtonGroup()
    private val userPresetButtons = linkedMapOf<String, JBRadioButton>()
    private val userPresetMarkers = linkedMapOf<String, SettingsPendingChange>()
    private val presetRow = JPanel(BorderLayout(0, JBUI.scale(4))).apply { isOpaque = false }
    private val updatePreset = JButton("Update preset").apply { isVisible = false }
    private val managePresets = JButton("Manage…")
    private var editingUserPresetId: String? = null
    private var presetManagerDialog: UserPresetManagerDialog? = null
    private val savePreset = JButton("Save as new preset").apply { isVisible = false }
    private val showIcons = JBCheckBox("Show icons", true)
    private val showOutline = JBCheckBox("Show outline", true)
    private val showTabBackground = JBCheckBox("Fill tab", false)
    private val iconSize = ComboBox(TabIconSize.entries.toTypedArray())
    private val iconPosition = ComboBox(TabIconPosition.entries.toTypedArray()).apply {
        // Короткие подписи в UI не меняют прежние значения, которые XMLB уже записал в XML.
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                                     isSelected: Boolean, cellHasFocus: Boolean): Component =
                super.getListCellRendererComponent(list, when (value) {
                    TabIconPosition.LEFT -> "Left"
                    TabIconPosition.RIGHT -> "Right"
                    else -> value
                }, index, isSelected, cellHasFocus)
        }
    }
    private val iconInitials = ComboBox(TabIconInitials.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                                     isSelected: Boolean, cellHasFocus: Boolean): Component =
                super.getListCellRendererComponent(list,
                    if (value == TabIconInitials.NAME_PREFIX) "Two initials · Ab" else value,
                    index, isSelected, cellHasFocus)
        }
    }
    private val iconLetterWeight = ComboBox(TabIconLetterWeight.entries.toTypedArray())
    private val initialsLabel = JBLabel("Initials:").apply { labelFor = iconInitials }
    private val letterWeightLabel = JBLabel("Letter weight:").apply { labelFor = iconLetterWeight }
    private val sizeLabel = JBLabel("Size:").apply { labelFor = iconSize }
    private val positionLabel = JBLabel("Position:").apply { labelFor = iconPosition }
    private val outlineStyle = ComboBox(TabOutlineStyle.entries.toTypedArray())
    private val outlineThickness = JSlider(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS, DEFAULT_OUTLINE_THICKNESS).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val styleLabel = JBLabel("Style:").apply { labelFor = outlineStyle }
    private val thicknessLabel = JBLabel("Thickness:").apply { labelFor = outlineThickness }
    private val thicknessValue = JBLabel("$DEFAULT_OUTLINE_THICKNESS px", SwingConstants.RIGHT)
    private val tabFillInset = JSlider(MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET, 0).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val fillInsetLabel = JBLabel("Fill inset:").apply { labelFor = tabFillInset }
    private val fillInsetValue = JBLabel("0 px", SwingConstants.RIGHT)
    private val tabFillIntensity = JSlider(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY, DEFAULT_TAB_FILL_INTENSITY).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val fillIntensityLabel = JBLabel("Fill intensity:").apply { labelFor = tabFillIntensity }
    private val fillIntensityValue = JBLabel("$DEFAULT_TAB_FILL_INTENSITY%", SwingConstants.RIGHT)
    private val closeButtonPosition = ComboBox(TabCloseButtonPosition.entries.toTypedArray())
    private val closeButtonLabel = JBLabel("Close button:").apply { labelFor = closeButtonPosition }
    private val fontSizeSelection = TabFontSizeSelection()
    private val tabFontSize = JSlider(minOf(MIN_TAB_FONT_SIZE, defaultTabFontSize()),
        maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2), defaultTabFontSize()).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val fontSizeLabel = JBLabel("Font size:").apply { labelFor = tabFontSize }
    private val fontSizeValue = JBLabel("${defaultTabFontSize()} px", SwingConstants.RIGHT)
    private val tabHeight = JSlider(DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT, DEFAULT_TAB_HEIGHT).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val tabHeightLabel = JBLabel("Tab height:").apply { labelFor = tabHeight }
    private val tabHeightValue = JBLabel("$DEFAULT_TAB_HEIGHT px", SwingConstants.RIGHT)
    private val moreInfoLabel = JBLabel("Show more info:")
    private val moreInfoButtons = TabMoreInfo.entries.associateWith { JBRadioButton(it.title) }
    private val branchPosition = ComboBox(TabBranchPosition.entries.toTypedArray())
    private val branchPositionLabel = JBLabel("Git info position:").apply { labelFor = branchPosition }
    private val branchStatus = JBCheckBox("Show Git status")
    private var syncing = false
    private var displayedAppearance = TabAppearanceState.defaults()
    private var appearanceBaseline = TabAppearanceState.defaults()
    private var lastCustomAppearance = TabAppearanceState.defaults().asCustomAppearance()
    private var customAppearanceBaseline = lastCustomAppearance
    private var userPresetDraft = emptyList<UserAppearancePreset>()
    private var userPresetBaseline = emptyList<UserAppearancePreset>()
    private var canSaveAsPreset = false
    private var presetNameDialog: UserPresetNameDialog? = null
    private var disposed = false
    private var importedCategoryCount = 0
    private val pendingMarkers = mutableListOf<Pair<SettingsPendingChange, (TabAppearanceState) -> Boolean>>()
    var rows: List<ProjectIconSettingsRow> = emptyList()
        private set

    val modified: Boolean get() = appearance() != appearanceBaseline ||
        lastCustomAppearance != customAppearanceBaseline || userPresetDraft != userPresetBaseline || groups.modified || rows.any { it.modified }
    val preferredFocus: JComponent get() = pluginEnabled

    init {
        presetButtons.values.forEach(presetGroup::add)
        ButtonGroup().apply { moreInfoButtons.values.forEach(::add) }
        (listOf(showIcons, showOutline, showTabBackground) + moreInfoButtons.values).forEach { it.isOpaque = false }
        appearanceTabs.background = component.background
        component.border = JBUI.Borders.empty(16)
        component.preferredSize = JBUI.size(880, 700)
        component.minimumSize = JBUI.size(360, 400)
        page.add(JPanel(BorderLayout(0, JBUI.scale(20))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
                add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                    add(SettingsPreviewHeader(
                        JBLabel("Preview").apply { font = font.deriveFont(Font.BOLD) },
                        JBLabel("IDE theme for this preview window:").apply { labelFor = previewTheme },
                        previewTheme, ::pageWidth,
                    ), BorderLayout.NORTH)
                    add(preview, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
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
            add(appearancePanel(), BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        projectScroll.apply {
            border = JBUI.Borders.customLine(JBColor.border())
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = JBUI.size(660, 300)
            setColumnHeaderView(JPanel(columns.layout(header = true)).apply {
                isOpaque = false
                listOf("Project", "Group", "Icon", "Icon color", "Outline color", "Tab color", "").forEach { add(settingsHint(it)) }
                border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            })
            viewport.background = UIUtil.getListBackground()
        }
        val projectHeader = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(countLabel, BorderLayout.NORTH)
            add(groups.projectControls, BorderLayout.CENTER)
        }
        projectTables.addTab("Projects", JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false; border = JBUI.Borders.empty(0, 12, 12, 12)
            add(projectHeader, BorderLayout.NORTH)
            add(projectScroll, BorderLayout.CENTER)
        })
        projectTables.addTab("Project groups", JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(0, 12, 12, 12)
            add(groups, BorderLayout.CENTER)
        })
        projectTables.addChangeListener { page.revalidate(); page.repaint() }
        page.add(object : JPanel(BorderLayout()) {
            override fun getMinimumSize(): Dimension {
                // Единственная видимая таблица получает место для заголовка и строк;
                // длинный список и раскрытый редактор прокручиваются внутри вкладки.
                // Ориентируемся на viewport всей страницы, а не на фиксированные
                // 300 px preferredSize таблицы: редактор участников растёт вместе с Settings.
                return Dimension(0, maxOf(JBUI.scale(440), pageScroll.viewport.extentSize.height - JBUI.scale(80),
                    projectHeader.preferredSize.height + JBUI.scale(220)))
            }
        }.apply {
            add(projectTables, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        component.add(pageScroll, BorderLayout.CENTER)
        pageScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                appearanceTabs.revalidate()
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
        pluginEnabled.addActionListener { appearanceChanged(manualEdit = false) }
        savePreset.addActionListener { saveUserPreset() }
        updatePreset.addActionListener {
            val id = editingUserPresetId ?: return@addActionListener
            val current = appearance()
            userPresetDraft = userPresetDraft.map { if (it.id == id) it.copy(appearance = current.asCustomAppearance()) else it }
            canSaveAsPreset = false
            rebuildUserPresetButtons()
            setAppearance(current.copy(userPresetId = id))
            updateStatus()
        }
        managePresets.addActionListener {
            val dialog = UserPresetManagerDialog(component, userPresetDraft)
            presetManagerDialog = dialog
            try { if (dialog.showAndGet() && !disposed) replaceUserPresets(dialog.result()) }
            finally { presetManagerDialog = null }
        }
        restoreDefaults.addActionListener {
            rememberCurrentCustom()
            canSaveAsPreset = false
            editingUserPresetId = null
            setAppearance(TabAppearanceState.defaults().copy(enabled = pluginEnabled.isSelected))
            updateStatus()
        }
        resetAppearance()
    }

    fun refreshPreviewTheme() {
        try {
            preview.showTheme(previewTheme.selectedItem as PreviewIdeTheme)
            if (fontSizeSelection.storedSize == 0 && tabFontSize.value != defaultTabFontSize()) {
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

    private fun appearancePanel(): JPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
        rebuildPresetRow()
        add(presetRow, BorderLayout.NORTH)
        val rowHeight = maxOf(iconSize.preferredSize.height, outlineThickness.preferredSize.height, JBUI.scale(30))
        val gitInfoControl = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(branchPosition, BorderLayout.CENTER)
            add(branchStatus, BorderLayout.EAST)
        }
        val controls = listOf(iconSize, iconPosition, iconInitials, iconLetterWeight, outlineStyle, closeButtonPosition, gitInfoControl)
        val controlWidth = maxOf(controls.maxOf {
            it.preferredSize.width + if (it === gitInfoControl) SettingsPendingChange.reservedWidth else 0
        }, JBUI.scale(240))
        val labelWidth = { listOf(sizeLabel, positionLabel, initialsLabel, letterWeightLabel, styleLabel,
            thicknessLabel, fillInsetLabel, fillIntensityLabel, closeButtonLabel, fontSizeLabel, tabHeightLabel, branchPositionLabel)
            .maxOf { it.preferredSize.width } + JBUI.scale(12) }
        controls.forEach { it.preferredSize = Dimension(controlWidth, rowHeight) }
        fun sliderControl(slider: JSlider, value: JBLabel, maxValue: Int, unit: String = "px") = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            value.preferredSize = Dimension(value.getFontMetrics(value.font).stringWidth("$maxValue $unit"), rowHeight)
            add(slider, BorderLayout.CENTER)
            add(value, BorderLayout.EAST)
            preferredSize = Dimension(controlWidth, rowHeight)
        }
        val thicknessControl = sliderControl(outlineThickness, thicknessValue, MAX_OUTLINE_THICKNESS)
        val fillInsetControl = sliderControl(tabFillInset, fillInsetValue, MAX_TAB_FILL_INSET)
        val fillIntensityControl = sliderControl(tabFillIntensity, fillIntensityValue, MAX_TAB_FILL_INTENSITY, "%")
        val heightControl = sliderControl(tabHeight, tabHeightValue, MAX_TAB_HEIGHT)
        val fontControl = sliderControl(tabFontSize, fontSizeValue, maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2))
        fun form(master: JComponent, fields: List<AppearanceField>, masterLabel: JBLabel? = null,
                 singleColumn: Boolean = false) = SettingsAppearanceForm(
            master, fields, labelWidth, controlWidth, rowHeight,
            { (pageWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right).coerceAtLeast(1) },
            masterLabel, singleColumn,
        )
        appearanceTabs.addTab("Icon", form(showIcons, listOf(
            AppearanceField(sizeLabel, iconSize), AppearanceField(positionLabel, iconPosition),
            AppearanceField(initialsLabel, iconInitials), AppearanceField(letterWeightLabel, iconLetterWeight),
        )))
        appearanceTabs.addTab("Color", form(showOutline, listOf(
            AppearanceField(styleLabel, outlineStyle), AppearanceField(null, showTabBackground),
            AppearanceField(thicknessLabel, thicknessControl), AppearanceField(fillInsetLabel, fillInsetControl),
            AppearanceField(fillIntensityLabel, fillIntensityControl),
        )))
        val moreInfoRow = SettingsRadioRow(moreInfoLabel, moreInfoButtons.values.toList(), wrapChoices = true) {
            (pageWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right - JBUI.scale(24)).coerceAtLeast(1)
        }
        appearanceTabs.addTab("Tab layout", JPanel(BorderLayout()).apply {
            isOpaque = false
            add(form(heightControl, listOf(
                AppearanceField(fontSizeLabel, fontControl), AppearanceField(closeButtonLabel, closeButtonPosition),
            ), tabHeightLabel, singleColumn = true), BorderLayout.NORTH)
            add(form(moreInfoRow, listOf(
                AppearanceField(branchPositionLabel, gitInfoControl),
            )), BorderLayout.CENTER)
        })
        // Навигация локальна для открытой формы: не выбирает Custom и не меняет Apply.
        appearanceTabs.addChangeListener { page.revalidate(); page.repaint() }
        add(appearanceTabs, BorderLayout.CENTER)
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
                    editingUserPresetId = null
                    canSaveAsPreset = preset == TabAppearancePreset.CUSTOM
                    val next = if (preset == TabAppearancePreset.CUSTOM) lastCustomAppearance.copy(enabled = pluginEnabled.isSelected)
                        else TabAppearanceState.forPreset(preset, pluginEnabled.isSelected)
                    setAppearance(next)
                    updateStatus()
                }
            }
        }
        showTabBackground.accessibleContext.accessibleName = "Fill tab"
        showTabBackground.toolTipText = "Color the tab using Fill intensity and Fill inset. Matches the icon color unless a Tab color is assigned."
        showTabBackground.addActionListener { appearanceChanged() }
        tabFillInset.accessibleContext.accessibleName = "Tab fill inset in pixels"
        tabFillInset.accessibleContext.accessibleDescription = "From 0 to 6 pixels. Use arrow keys for one-pixel steps."
        tabFillInset.toolTipText = "Inset the fill from every tab edge. Outline, title and icon stay in place."
        tabFillInset.addChangeListener { appearanceChanged() }
        tabFillIntensity.accessibleContext.accessibleName = "Tab fill intensity in percent"
        tabFillIntensity.accessibleContext.accessibleDescription = "From 0% (transparent) to 100% (solid). Use arrow keys for one-percent steps."
        tabFillIntensity.toolTipText = "Adjust fill opacity. 0% hides the fill; 100% uses the solid tab color. Icon and outline colors stay unchanged."
        tabFillIntensity.addChangeListener { appearanceChanged() }
        showOutline.toolTipText = "Use each project's icon color or its custom outline color."
        iconSize.accessibleContext.accessibleName = "Project icon size"
        iconPosition.accessibleContext.accessibleName = "Project icon position"
        iconInitials.accessibleContext.accessibleName = "Generated icon initials"
        iconInitials.toolTipText = "Two initials uses IDE abbreviations, or the first two letters for a single word without inner capitals: Backend → BA. Two initials · Ab uses the same letters with mixed case: Ba. SVG/PNG artwork is unchanged."
        iconLetterWeight.accessibleContext.accessibleName = "Generated icon letter weight"
        iconLetterWeight.toolTipText = "Normal or bold letters in generated icons. SVG/PNG artwork is unchanged."
        outlineStyle.accessibleContext.accessibleName = "Outline style"
        outlineThickness.accessibleContext.accessibleName = "Outline thickness in pixels"
        outlineThickness.accessibleContext.accessibleDescription = "From 1 to 6 pixels. Use arrow keys for one-pixel steps."
        outlineThickness.toolTipText = "Outline thickness: 1–6 px"
        closeButtonPosition.accessibleContext.accessibleName = "Close button position"
        closeButtonPosition.toolTipText = "Place the window tab close button on the left or right."
        tabHeight.accessibleContext.accessibleName = "Window tab height in pixels"
        tabHeight.accessibleContext.accessibleDescription = "From $DEFAULT_TAB_HEIGHT to $MAX_TAB_HEIGHT pixels. Use arrow keys for one-pixel steps."
        tabHeight.toolTipText = "Tab height: $DEFAULT_TAB_HEIGHT px (default) to $MAX_TAB_HEIGHT px. Content and outlines remain visible."
        moreInfoButtons.forEach { (mode, button) ->
            button.accessibleContext.accessibleName = "Show more info: ${mode.title}"
            button.toolTipText = when (mode) {
                TabMoreInfo.NONE -> "Show only the project name."
                TabMoreInfo.PROJECT_PATH -> "Show the project path in window tabs."
                TabMoreInfo.GIT_BRANCH -> "Show the current Git branch. Long branches keep their ending with an ellipsis at the beginning. No branch is shown without a Git repository or in detached HEAD."
                TabMoreInfo.GIT_BRANCH_COMPACT -> "Show the part after the last slash, up to 13 characters including a leading ellipsis; keeps the end of long names. The full branch stays in the tooltip."
                TabMoreInfo.GIT_STATUS -> "Added and removed lines in saved tracked changes against HEAD, including staged and unstaged changes. Binary and untracked files are excluded."
            }
            button.addActionListener {
                if (!syncing && button.isSelected && displayedAppearance.moreInfo() != mode) {
                    branchStatus.isSelected = mode.isBranch
                    appearanceChanged()
                }
            }
        }
        branchPosition.accessibleContext.accessibleName = "Git info position"
        branchPosition.toolTipText = "Place the Git branch and changed-line count together to the left or right of the project name."
        branchPosition.addActionListener { appearanceChanged() }
        branchStatus.accessibleContext.accessibleName = "Show Git status with branch"
        branchStatus.toolTipText = "Show added and removed lines alongside Git branch or Git Branch Compact. Saved tracked changes against HEAD, staged and unstaged."
        branchStatus.addActionListener { appearanceChanged() }
        showIcons.addActionListener { appearanceChanged() }
        showOutline.addActionListener { appearanceChanged() }
        iconSize.addActionListener { appearanceChanged() }
        iconPosition.addActionListener { appearanceChanged() }
        iconInitials.addActionListener { appearanceChanged() }
        iconLetterWeight.addActionListener { appearanceChanged() }
        outlineStyle.addActionListener { appearanceChanged() }
        outlineThickness.addChangeListener { appearanceChanged() }
        closeButtonPosition.addActionListener { appearanceChanged() }
        tabHeight.addChangeListener { appearanceChanged() }
        tabFontSize.accessibleContext.accessibleName = "Window tab font size in pixels"
        tabFontSize.accessibleContext.accessibleDescription = "Use arrow keys for one-pixel steps."
        tabFontSize.toolTipText = "Adjust tab title text. The default size follows the IDE font."
        tabFontSize.addChangeListener {
            if (!syncing) fontSizeSelection.select(tabFontSize.value, defaultTabFontSize())
            appearanceChanged()
        }
        installAppearanceMarkers()
    }

    private fun rebuildPresetRow() {
        presetRow.removeAll()
        val userChoices = userPresetButtons.values.toList() + listOf(updatePreset, savePreset, managePresets).filter { it.isVisible }
        val titleWidth = { maxOf(presetTitle.preferredSize.width,
            if (userChoices.isNotEmpty()) userPresetTitle.preferredSize.width else 0) }
        presetRow.add(SettingsRadioRow(presetTitle, presetButtons.values.toList(), wrapChoices = true,
            availableWidth = ::pageWidth, titleWidth = titleWidth), BorderLayout.NORTH)
        if (userChoices.isNotEmpty()) {
            presetRow.add(SettingsRadioRow(userPresetTitle, userChoices, wrapChoices = true,
                availableWidth = ::pageWidth, titleWidth = titleWidth), BorderLayout.CENTER)
        }
        presetRow.revalidate()
        presetRow.repaint()
        page.revalidate()
    }

    private fun refreshSavePresetAction() {
        val current = appearance()
        val visible = canSaveAsPreset && current.preset == TabAppearancePreset.CUSTOM && current.userPresetId == null
        val updateVisible = visible && userPresetDraft.any { it.id == editingUserPresetId && it.appearance != current.asCustomAppearance() }
        val manageVisible = userPresetDraft.isNotEmpty()
        val visibilityChanged = visible != savePreset.isVisible || updateVisible != updatePreset.isVisible || manageVisible != managePresets.isVisible
        updatePreset.isVisible = updateVisible
        updatePreset.isEnabled = pluginEnabled.isSelected
        updatePreset.toolTipText = "Replace ${userPresetDraft.firstOrNull { it.id == editingUserPresetId }?.name.orEmpty()} with the current appearance · Saved with Apply"
        managePresets.isVisible = manageVisible
        managePresets.isEnabled = pluginEnabled.isSelected
        savePreset.isVisible = visible
        savePreset.isEnabled = pluginEnabled.isSelected && userPresetDraft.size < MAX_USER_APPEARANCE_PRESETS
        savePreset.toolTipText = if (userPresetDraft.size >= MAX_USER_APPEARANCE_PRESETS)
            "All $MAX_USER_APPEARANCE_PRESETS user preset slots are used. Right-click a user preset to delete it, then save a new one."
        else "Save Icon, Color and Tab layout as a named preset · ${userPresetDraft.size} of $MAX_USER_APPEARANCE_PRESETS slots used · Apply to keep it"
        if (visibilityChanged) rebuildPresetRow()
    }

    private fun rebuildUserPresetButtons() {
        userPresetButtons.values.forEach(presetGroup::remove)
        userPresetButtons.clear()
        userPresetMarkers.clear()
        userPresetDraft.forEach { preset ->
            val button = JBRadioButton(preset.name).apply {
                isOpaque = false
                accessibleContext.accessibleName = "User appearance preset: ${preset.name}"
                toolTipText = "User preset · ${settingsLiteral(preset.name)} · Right-click or Shift+F10 to rename or delete · Apply to save changes"
                addActionListener {
                    if (!syncing) {
                        rememberCurrentCustom()
                        canSaveAsPreset = false
                        editingUserPresetId = preset.id
                        setAppearance(preset.appearance.copy(enabled = pluginEnabled.isSelected, userPresetId = preset.id))
                        updateStatus()
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
        val current = appearance()
        val preset = UserAppearancePreset(UUID.randomUUID().toString(), name, current.asCustomAppearance())
        userPresetDraft = userPresetDraft + preset
        editingUserPresetId = preset.id
        canSaveAsPreset = false
        rebuildUserPresetButtons()
        setAppearance(current.copy(preset = TabAppearancePreset.CUSTOM, userPresetId = preset.id))
        updateStatus()
    }

    private fun renameUserPreset(id: String) {
        val preset = userPresetDraft.firstOrNull { it.id == id } ?: return
        val name = askPresetName(preset) ?: return
        val current = appearance()
        userPresetDraft = userPresetDraft.map { if (it.id == id) it.copy(name = name) else it }
        rebuildUserPresetButtons()
        setAppearance(current)
        updateStatus()
    }

    private fun deleteUserPreset(id: String) {
        val current = appearance()
        userPresetDraft = userPresetDraft.filterNot { it.id == id }
        val next = if (current.userPresetId == id) {
            lastCustomAppearance = current.asCustomAppearance()
            canSaveAsPreset = true
            lastCustomAppearance.copy(enabled = current.enabled)
        } else current
        rebuildUserPresetButtons()
        setAppearance(next)
        updateStatus()
    }

    private fun askPresetName(existing: UserAppearancePreset? = null): String? {
        if (disposed) return null
        val dialog = UserPresetNameDialog(component, userPresetDraft, existing)
        presetNameDialog = dialog
        return try {
            if (dialog.showAndGet() && !disposed) dialog.presetName() else null
        } finally { presetNameDialog = null }
    }

    fun disposeDialogs() {
        disposed = true
        groups.disposeDialogs()
        presetNameDialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        presetNameDialog = null
        presetManagerDialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        presetManagerDialog = null
    }

    private fun installAppearanceMarkers() {
        fun watch(component: JComponent, value: (TabAppearanceState) -> Any?) {
            pendingMarkers += SettingsPendingChange(component) to { current: TabAppearanceState -> value(current) != value(appearanceBaseline) }
        }
        watch(pluginEnabled) { it.enabled }
        pendingMarkers += SettingsPendingChange(presetTitle) to { current: TabAppearanceState ->
            current.preset != appearanceBaseline.preset || current.userPresetId != appearanceBaseline.userPresetId ||
                userPresetDraft != userPresetBaseline
        }
        watch(showIcons) { it.showIcons }
        watch(sizeLabel) { it.iconSize }
        watch(positionLabel) { it.iconPosition }
        watch(initialsLabel) { it.iconInitials }
        watch(letterWeightLabel) { it.iconLetterWeight }
        watch(showOutline) { it.showOutline }
        watch(styleLabel) { it.outlineStyle }
        watch(thicknessLabel) { it.outlineThickness }
        watch(showTabBackground) { it.showTabBackground }
        watch(fillInsetLabel) { it.tabFillInset }
        watch(fillIntensityLabel) { it.tabFillIntensity }
        watch(tabHeightLabel) { it.tabHeight }
        watch(fontSizeLabel) { it.tabFontSize }
        watch(closeButtonLabel) { it.closeButtonPosition }
        watch(moreInfoLabel) { it.moreInfo() }
        watch(branchPositionLabel) { it.branchPosition }
        watch(branchStatus) { it.showGitBranch && it.showGitStatus }
        pendingMarkers += SettingsPendingChange(presetButtons.getValue(TabAppearancePreset.CUSTOM)) to { _: TabAppearanceState ->
            lastCustomAppearance != customAppearanceBaseline
        }
    }

    private fun rememberCurrentCustom() {
        // При клике радиокнопка уже переключена; прежний пресет берём из отображённого состояния.
        if (displayedAppearance.preset == TabAppearancePreset.CUSTOM && displayedAppearance.userPresetId == null) {
            lastCustomAppearance = displayedAppearance.asCustomAppearance()
        }
    }

    fun projectGroups(): ProjectGroupsState = groups.draft
    fun updateGroup(id: String, transform: (ProjectTabGroup) -> ProjectTabGroup) = groups.updateGroup(id, transform)

    fun replaceUserPresets(value: List<UserAppearancePreset>) {
        val current = appearance()
        userPresetDraft = value.toList()
        val active = value.firstOrNull { it.id == current.userPresetId }
        if (value.none { it.id == editingUserPresetId }) editingUserPresetId = null
        val next = if (current.userPresetId != null && active == null) {
            lastCustomAppearance = current.asCustomAppearance()
            canSaveAsPreset = true
            current.copy(userPresetId = null, preset = TabAppearancePreset.CUSTOM)
        } else active?.appearance?.copy(enabled = current.enabled, userPresetId = active.id) ?: current
        rebuildUserPresetButtons()
        setAppearance(next)
        updateStatus()
    }

    fun customAppearance(): TabAppearanceState = lastCustomAppearance
    fun userPresets(): List<UserAppearancePreset> = userPresetDraft.toList()
    fun showImportedCategories(count: Int) {
        importedCategoryCount = count
        updateStatus()
    }

    fun appearance(): TabAppearanceState = TabAppearanceState(
        showIcons = showIcons.isSelected,
        showOutline = showOutline.isSelected,
        showTabBackground = showTabBackground.isSelected,
        tabFillInset = tabFillInset.value,
        tabFillIntensity = tabFillIntensity.value,
        iconSize = iconSize.selectedItem as TabIconSize,
        iconPosition = iconPosition.selectedItem as TabIconPosition,
        iconInitials = iconInitials.selectedItem as TabIconInitials,
        iconLetterWeight = iconLetterWeight.selectedItem as TabIconLetterWeight,
        outlineThickness = outlineThickness.value,
        outlineStyle = outlineStyle.selectedItem as TabOutlineStyle,
        closeButtonPosition = closeButtonPosition.selectedItem as TabCloseButtonPosition,
        tabHeight = tabHeight.value,
        preset = presetButtons.entries.firstOrNull { it.value.isSelected }?.key ?: TabAppearancePreset.CUSTOM,
        userPresetId = userPresetButtons.entries.firstOrNull { it.value.isSelected }?.key,
        tabFontSize = fontSizeSelection.storedSize,
        showProjectPath = moreInfoButtons.getValue(TabMoreInfo.PROJECT_PATH).isSelected,
        showGitBranch = moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected },
        gitBranchFormat = if (moreInfoButtons.getValue(TabMoreInfo.GIT_BRANCH_COMPACT).isSelected)
            TabGitBranchFormat.COMPACT else TabGitBranchFormat.FULL,
        showGitStatus = moreInfoButtons.getValue(TabMoreInfo.GIT_STATUS).isSelected ||
            (moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected } && branchStatus.isSelected),
        branchPosition = branchPosition.selectedItem as TabBranchPosition,
        enabled = pluginEnabled.isSelected,
    )

    fun resetAppearance() {
        val app = ApplicationManager.getApplication()
        userPresetDraft = app.getService(UserAppearancePresets::class.java).state.presets.toList()
        userPresetBaseline = userPresetDraft
        groups.reset(app.getService(ProjectGroups::class.java).state)
        rebuildUserPresetButtons()
        val saved = app.getService(TabAppearanceSettings::class.java).state
        appearanceBaseline = if (saved.userPresetId != null && userPresetDraft.none { it.id == saved.userPresetId })
            saved.copy(preset = TabAppearancePreset.CUSTOM, userPresetId = null) else saved
        lastCustomAppearance = if (appearanceBaseline.preset == TabAppearancePreset.CUSTOM && appearanceBaseline.userPresetId == null) {
            appearanceBaseline.asCustomAppearance()
        } else app.getService(LastCustomAppearanceSettings::class.java).state.appearance
            ?: appearanceBaseline.asCustomAppearance()
        customAppearanceBaseline = lastCustomAppearance
        canSaveAsPreset = false
        editingUserPresetId = appearanceBaseline.userPresetId
        setAppearance(appearanceBaseline)
    }

    private fun setAppearance(value: TabAppearanceState) {
        syncing = true
        try {
            pluginEnabled.isSelected = value.enabled
            presetButtons.forEach { (preset, button) -> button.isSelected = value.userPresetId == null && preset == value.preset }
            userPresetButtons.forEach { (id, button) -> button.isSelected = id == value.userPresetId }
            val size = fontSizeSelection.reset(value.tabFontSize, defaultTabFontSize())
            tabFontSize.maximum = maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2, size)
            tabFontSize.minimum = minOf(MIN_TAB_FONT_SIZE, defaultTabFontSize())
            tabFontSize.value = size
            showIcons.isSelected = value.showIcons
            showOutline.isSelected = value.showOutline
            showTabBackground.isSelected = value.showTabBackground
            tabFillInset.value = value.tabFillInset
            tabFillIntensity.value = value.tabFillIntensity
            iconSize.selectedItem = value.iconSize
            iconPosition.selectedItem = value.iconPosition
            iconInitials.selectedItem = value.iconInitials
            iconLetterWeight.selectedItem = value.iconLetterWeight
            outlineStyle.selectedItem = value.outlineStyle
            outlineThickness.value = value.outlineThickness
            closeButtonPosition.selectedItem = value.closeButtonPosition
            tabHeight.value = value.tabHeight
            moreInfoButtons.getValue(value.moreInfo()).isSelected = true
            branchStatus.isSelected = value.showGitBranch && value.showGitStatus
            branchPosition.selectedItem = value.branchPosition
        } finally {
            syncing = false
        }
        updateAppearanceEnabled()
        refreshAppearancePreview()
        refreshPendingChanges()
    }

    private fun updateAppearanceEnabled() {
        val enabled = pluginEnabled.isSelected
        presetTitle.isEnabled = enabled
        userPresetTitle.isEnabled = enabled
        presetButtons.values.forEach { it.isEnabled = enabled }
        userPresetButtons.values.forEach { it.isEnabled = enabled }
        refreshSavePresetAction()
        listOf(showIcons, showOutline, showTabBackground, closeButtonPosition, closeButtonLabel,
            tabHeight, tabHeightLabel, tabHeightValue, tabFontSize, fontSizeLabel, fontSizeValue, moreInfoLabel)
            .forEach { it.isEnabled = enabled }
        listOf(iconSize, iconPosition, iconInitials, iconLetterWeight,
            sizeLabel, positionLabel, initialsLabel, letterWeightLabel).forEach {
            it.isEnabled = enabled && showIcons.isSelected
        }
        listOf(outlineStyle, outlineThickness, styleLabel, thicknessLabel, thicknessValue).forEach {
            it.isEnabled = enabled && showOutline.isSelected
        }
        listOf(tabFillInset, fillInsetLabel, fillInsetValue, tabFillIntensity, fillIntensityLabel, fillIntensityValue).forEach {
            it.isEnabled = enabled && showTabBackground.isSelected
        }
        moreInfoButtons.values.forEach { it.isEnabled = enabled }
        listOf(branchPosition, branchPositionLabel).forEach {
            it.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isGit && it.value.isSelected }
        }
        branchStatus.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected }
        updateProjectsEnabled()
        restoreDefaults.isEnabled = appearance() != TabAppearanceState.defaults().copy(enabled = enabled)
        fillInsetValue.text = "${tabFillInset.value} px"
        fillIntensityValue.text = "${tabFillIntensity.value}%"
        thicknessValue.text = "${outlineThickness.value} px"
        tabHeightValue.text = "${tabHeight.value} px"
        fontSizeValue.text = "${tabFontSize.value} px"
    }

    private fun refreshAppearancePreview() {
        val next = appearance()
        val lettersChanged = next.iconInitials != displayedAppearance.iconInitials ||
            next.iconLetterWeight != displayedAppearance.iconLetterWeight
        displayedAppearance = next
        preview.showAppearance(next)
        if (lettersChanged) refreshRows()
    }

    private fun updateProjectsEnabled(force: Boolean = false) {
        val enabled = pluginEnabled.isSelected
        if (!force && projectsEnabled == enabled) return
        projectsEnabled = enabled
        countLabel.isEnabled = enabled
        groups.setEditingEnabled(enabled)
        fun update(view: Component) {
            if (view !is SettingsValueButton) view.isEnabled = enabled &&
                (view !is JComponent || view.getClientProperty("projectGroupSelectable") != false)
            if (view is Container) view.components.forEach(::update)
        }
        update(projects)
        projectScroll.columnHeader.view?.let(::update)
        projectActions.forEach { action ->
            action.button.isEnabled = enabled && action.available
            action.button.muteDisabledIcon = !enabled || !action.preserveIconWhenDisabled
            // Original сохраняет исходный цвет только при включённом плагине.
            action.button.disabledIcon = if (enabled && action.preserveIconWhenDisabled) action.button.icon else null
        }
        projectScroll.repaint()
    }

    private fun appearanceChanged(manualEdit: Boolean = true) {
        if (syncing) return
        // Пресет — отправная точка: только реальное ручное изменение делает его Custom.
        // Повторный выбор того же значения и начало перетаскивания слайдера — не изменение.
        if (manualEdit && appearance() != displayedAppearance) {
            displayedAppearance.userPresetId?.let { editingUserPresetId = it }
            presetButtons.getValue(TabAppearancePreset.CUSTOM).isSelected = true
            lastCustomAppearance = appearance().asCustomAppearance()
            canSaveAsPreset = true
        }
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
        val focusKey = (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JComponent)
            ?.getClientProperty("projectIconAction")
        val scrollY = projectScroll.verticalScrollBar.value
        var restoredFocus: JComponent? = null
        var fallbackFocus: JComponent? = null
        projects.removeAll()
        projectActions.clear()
        groups.updateRows(rows)
        val visibleRows = groups.visibleRows()
        countLabel.text = "Open projects (${rows.size})" + if (visibleRows.size != rows.size) " · ${visibleRows.size} shown" else ""
        visibleRows.forEachIndexed { index, row ->
            val line = ProjectRowPanel(columns.layout())
            val problem = when {
                row.conflicted -> "Changed outside Settings. Reload to continue."
                row.failure != null -> settingsMessage(row.failure.orEmpty())
                else -> null
            }
            val path = row.target.path?.toString() ?: "No local project directory"
            val identity = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBLabel(settingsLiteral(row.target.name)).apply {
                        toolTipText = settingsLiteral(row.target.name)
                    }, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(settingsHint(problem ?: settingsLiteral(shortProjectPath(path))).apply {
                    if (problem != null) foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                    toolTipText = problem ?: row.inheritedGroup?.let { "Icon and colors inherited from ${settingsLiteral(it.name)} · ${settingsLiteral(path)}" } ?: settingsLiteral(path)
                }, BorderLayout.SOUTH)
                minimumSize = Dimension(0, preferredSize.height)
            }
            line.add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(groups.selectionControl(row), BorderLayout.WEST)
                add(identity, BorderLayout.CENTER)
            })
            line.add(JBLabel(settingsLiteral(row.inheritedGroup?.name ?: "—")).apply {
                toolTipText = row.inheritedGroup?.let { "Icon and colors inherited from ${settingsLiteral(it.name)}" } ?: "No project group"
                accessibleContext.accessibleName = "Group for ${row.target.name}: ${row.inheritedGroup?.name ?: "—"}"
            })
            fun action(button: SettingsValueButton, name: String, preserveIconWhenDisabled: Boolean = false,
                       run: () -> Unit) {
                projectActions.add(ProjectAction(button, button.isEnabled, preserveIconWhenDisabled))
                button.putClientProperty("projectIconAction", "${row.key}/$name")
                if (name == "icon") SettingsPendingChange(button).update(row.svgModified || row.inheritedModified)
                ProjectColorTarget.entries.firstOrNull { name == it.name.lowercase() + "Color" }?.let {
                    SettingsPendingChange(button).update(row.colorModified(it) || row.inheritedModified)
                }
                if (button.getClientProperty("projectIconAction") == focusKey) restoredFocus = button
                if (name == "icon" && (focusKey as? String)?.startsWith("${row.key}/") == true) fallbackFocus = button
                button.addActionListener { if (pluginEnabled.isSelected) run() }
                line.add(button)
            }
            val iconName = projectSettingsIconName(row)
            val artwork = projectSettingsArtwork(row, appearance())
            action(SettingsValueButton(iconName, artwork).apply {
                isEnabled = row.inheritedGroup == null && row.ready && !row.conflicted
                toolTipText = row.inheritedGroup?.let { "Inherited from ${settingsLiteral(it.name)} · Edit in Project groups" }
                    ?: "Choose icon · ${settingsLiteral(row.activeChoice?.asset?.label ?: iconName)}"
                accessibleContext.accessibleName = "Change icon for ${row.target.name} · ${row.target.path}"
            }, "icon", preserveIconWhenDisabled = row.inheritedGroup != null) { chooseIcon(row) }
            ProjectColorTarget.entries.forEach { target ->
                val original = target == ProjectColorTarget.ICON && !row.editableIconColor
                val inherited = row.inheritedGroup
                val automatic = !original && (if (inherited == null) row.colorOverrides.value(target) == null else
                    (if (target == ProjectColorTarget.OUTLINE) inherited.outlineColor else inherited.tabColor).isBlank()) &&
                    (target != ProjectColorTarget.ICON || row.automaticIcon)
                val value = row.color(target)
                action(SettingsValueButton(when {
                    original -> "Original"
                    automatic -> "Automatic"
                    else -> value?.let(::colorName) ?: "Custom"
                }, value?.let { ColorSwatch(it) } ?: EmptyIcon.create(JBUI.scale(18))).apply {
                    isEnabled = inherited == null && row.ready && !row.conflicted && !original
                    toolTipText = inherited?.let { "Inherited from ${settingsLiteral(it.name)} · Edit in Project groups" } ?: when {
                        original -> "Original SVG/PNG colors are preserved"
                        automatic -> "${value?.hex() ?: "Automatic"} · " +
                            if (target == ProjectColorTarget.ICON) "Follows the IDE project color" else "Matches the icon color"
                        else -> "${value?.hex() ?: "Custom"} · Change ${target.title.lowercase()}"
                    } + if (target == ProjectColorTarget.TAB) " · Uses Fill intensity in Color" else ""
                    accessibleContext.accessibleName =
                        "${if (original) "Original" else "Change"} ${target.title.lowercase()} for ${row.target.name} · ${row.target.path}"
                }, target.name.lowercase() + "Color", preserveIconWhenDisabled = original || inherited != null) { chooseColor(row, target) }
            }
            val reload = row.conflicted || row.failure != null
            action(SettingsValueButton("", if (reload) AllIcons.Actions.Refresh else AllIcons.Actions.Rollback).apply {
                isEnabled = row.modified || reload
                hideWhenDisabled = true
                toolTipText = if (reload) "Reload project icon" else "Revert this project's pending changes"
                accessibleContext.accessibleName = "${if (reload) "Reload" else "Revert"} changes for ${row.target.name} · ${row.target.path}"
            }, "revert") { reloadRow(row) }
            line.trackHover()
            projects.add(line, GridBagConstraints().apply {
                gridx = 0; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
        }
        if (visibleRows.isEmpty()) projects.add(settingsHint(if (rows.isEmpty()) "Open a project to assign its icon and color." else "No open projects in this group.").apply {
            border = JBUI.Borders.empty(20, 12)
        }, GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = GridBagConstraints.NORTHWEST })
        projects.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = rows.size + 1; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
        })
        updateProjectsEnabled(force = true)
        projects.revalidate()
        projects.repaint()
        SwingUtilities.invokeLater {
            if (projects.isShowing) {
                projectScroll.verticalScrollBar.value = scrollY
                (restoredFocus?.takeIf { it.isEnabled } ?: fallbackFocus?.takeIf { it.isEnabled })?.requestFocusInWindow()
            }
        }
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
            userPresetDraft != userPresetBaseline -> "User presets edited · Save with Apply"
            lastCustomAppearance != customAppearanceBaseline -> "Custom appearance edited · Save with Apply"
            !pluginEnabled.isSelected -> "Tab customization is disabled."
            else -> "Click an icon or color to change it."
        }
        status.toolTipText = status.text
        modifiedChanged()
    }

    fun saved() {
        appearanceBaseline = appearance()
        customAppearanceBaseline = lastCustomAppearance
        userPresetBaseline = userPresetDraft.toList()
        groups.saved()
        refreshRows()
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Changes saved."
        status.toolTipText = status.text
    }

    private fun refreshPendingChanges() {
        val current = appearance()
        pendingMarkers.forEach { (marker, changed) -> marker.update(changed(current)) }
        userPresetMarkers.forEach { (id, marker) ->
            marker.update(userPresetDraft.firstOrNull { it.id == id } != userPresetBaseline.firstOrNull { it.id == id })
        }
    }

    fun failed(message: String) {
        status.foreground = JBColor.RED
        status.text = settingsMessage(message)
        status.toolTipText = status.text
    }
}

internal fun shortProjectPath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
    return if (parts.size > 3) "…/" + parts.takeLast(3).joinToString("/") else path
}

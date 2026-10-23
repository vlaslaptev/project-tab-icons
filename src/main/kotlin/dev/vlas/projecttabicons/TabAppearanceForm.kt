package dev.vlas.projecttabicons

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.ButtonGroup
import javax.swing.JSeparator
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants

/** Поля Style / Tab layout / Group layout; пресеты и сохранение остаются снаружи. */
internal class TabAppearanceForm(
    private val availableWidth: () -> Int,
    private val changed: () -> Unit,
    private val layoutChanged: () -> Unit,
) {
    private val appearanceTabs = SettingsGroupSwitcher(availableWidth = { availableWidth() - JBUI.scale(2) }).apply {
        accessibleContext.accessibleName = "Window tab settings groups"
        isOpaque = false
    }
    private val showIcons = JBCheckBox("Show tab icons", true)
    private val showOutline = JBCheckBox("Show outline", true)
    private val showTabBackground = JBCheckBox("Fill tab", false)
    private val iconSize = appearanceChoice(TabIconSize.entries) { "${it.pixels} px" }
    // Подписи UI не меняют значения enum, уже сохранённые в XML и пресетах.
    private val iconPosition = appearanceChoice(listOf(TabIconPosition.LEFT, TabIconPosition.RIGHT)) {
        if (it == TabIconPosition.LEFT) "Left" else "Right"
    }
    private val iconInitials = appearanceChoice(TabIconInitials.entries) {
        when (it) {
            TabIconInitials.ONE_LETTER -> "A"
            TabIconInitials.TWO_INITIALS -> "AB"
            TabIconInitials.NAME_PREFIX -> "Ab"
        }
    }
    private val iconLetterWeight = appearanceChoice(TabIconLetterWeight.entries)
    private val initialsLabel = JBLabel("Initials:").apply { labelFor = iconInitials }
    private val letterWeightLabel = JBLabel("Weight:").apply { labelFor = iconLetterWeight }
    private val sizeLabel = JBLabel("Size:").apply { labelFor = iconSize }
    private val positionLabel = JBLabel("Position:").apply { labelFor = iconPosition }
    private val outlineStyle = ComboBox(TabOutlineStyle.entries.toTypedArray())
    private val outlineThickness = pixelSpinner(DEFAULT_OUTLINE_THICKNESS, MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)
    private val styleLabel = JBLabel("Shape:").apply { labelFor = outlineStyle }
    private val thicknessLabel = JBLabel("Thickness:").apply { labelFor = outlineThickness }
    private val tabFillInset = pixelSpinner(0, MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET)
    private val fillInsetLabel = JBLabel("Inset:").apply { labelFor = tabFillInset }
    private val tabFillIntensity = JSlider(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY, DEFAULT_TAB_FILL_INTENSITY).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val fillIntensityLabel = JBLabel("Intensity:").apply { labelFor = tabFillIntensity }
    private val fillIntensityValue = JBLabel("$DEFAULT_TAB_FILL_INTENSITY%", SwingConstants.RIGHT)
    private val closeButtonPosition = appearanceChoice(TabCloseButtonPosition.entries)
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
    private val tabHeightLabel = JBLabel("Height:").apply { labelFor = tabHeight }
    private val tabHeightValue = JBLabel("$DEFAULT_TAB_HEIGHT px", SwingConstants.RIGHT)
    private val groupSpacing = JSlider(0, MAX_GROUP_SPACING, 0).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val groupSpacingLabel = JBLabel("Group spacing:").apply { labelFor = groupSpacing }
    private val groupSpacingValue = JBLabel("0 px", SwingConstants.RIGHT)
    private val showGroupOutline = JBCheckBox("Show group highlight")
    private val groupHighlightStyle = ComboBox(arrayOf(GroupHighlightStyle.BACKGROUND, GroupHighlightStyle.JOINED))
    private val groupVariantLabel = JBLabel("Variant:").apply { labelFor = groupHighlightStyle }
    private val backgroundHighlight = GroupHighlightControls(GroupHighlightStyle.BACKGROUND) { appearanceChanged() }
    private val joinedHighlight = GroupHighlightControls(GroupHighlightStyle.JOINED) { appearanceChanged() }
    private val groupVariantDetails = JPanel(BorderLayout()).apply { isOpaque = false }
    private lateinit var backgroundHighlightForm: JComponent
    private lateinit var joinedHighlightForm: JComponent
    private val showGroupSeparator = JBCheckBox("Show separator (pipe)")
    private val groupSeparatorThickness = JSlider(1, MAX_OUTLINE_THICKNESS, 1).apply {
        minorTickSpacing = 1; snapToTicks = true; isOpaque = false
    }
    private val separatorThicknessLabel = JBLabel("Thickness:").apply { labelFor = groupSeparatorThickness }
    private val separatorThicknessValue = JBLabel("1 px", SwingConstants.RIGHT)
    private val groupSeparatorHeight = JSlider(20, 100, DEFAULT_GROUP_SEPARATOR_HEIGHT).apply {
        minorTickSpacing = 1; snapToTicks = true; isOpaque = false
    }
    private val separatorHeightLabel = JBLabel("Height:").apply { labelFor = groupSeparatorHeight }
    private val separatorHeightValue = JBLabel("$DEFAULT_GROUP_SEPARATOR_HEIGHT%", SwingConstants.RIGHT)
    private val groupSeparatorColor = ComboBox(GroupSeparatorColor.entries.toTypedArray())
    private val separatorColorLabel = JBLabel("Color:").apply { labelFor = groupSeparatorColor }
    private val showGroupIcon = JBCheckBox("Show group icon")
    private val hideGroupedTabIcons = JBCheckBox("Hide tab icons inside the group")
    private val moreInfoLabel = JBLabel("Info:")
    private val moreInfoButtons = TabMoreInfo.entries.associateWith { JBRadioButton(it.title) }
    private val branchPosition = appearanceChoice(TabBranchPosition.entries)
    private val branchPositionLabel = JBLabel("Git position:").apply { labelFor = branchPosition }
    private val branchStatus = JBCheckBox("Show Git status")
    private var syncing = false
    private var displayedMoreInfo = TabAppearanceState.defaults().moreInfo()
    val component = createComponent()

    fun setPresetActions(actions: JComponent) = appearanceTabs.setHeaderActions(actions)

    init {
        ButtonGroup().apply { moreInfoButtons.values.forEach(::add) }
        (listOf(showIcons, showOutline, showTabBackground, showGroupOutline, showGroupIcon,
            hideGroupedTabIcons, showGroupSeparator) + moreInfoButtons.values).forEach { it.isOpaque = false }
        appearanceTabs.background = component.background
    }

    fun defaultFontChanged(): Boolean = fontSizeSelection.storedSize == 0 && tabFontSize.value != defaultTabFontSize()

    private fun appearanceChanged() {
        if (syncing) return
        displayedMoreInfo = moreInfoButtons.entries.first { it.value.isSelected }.key
        changed()
    }

    private fun createComponent(): JPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
        val rowHeight = maxOf(iconSize.preferredSize.height, outlineThickness.preferredSize.height, JBUI.scale(30))
        val gitInfoControl = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(branchPosition)
            add(branchStatus)
        }
        val controls = listOf(gitInfoControl, groupSeparatorColor, groupHighlightStyle)
        val controlWidth = maxOf(controls.maxOf {
            it.preferredSize.width + if (it === gitInfoControl) SettingsPendingChange.reservedWidth else 0
        }, JBUI.scale(240))
        fun sliderControl(slider: JSlider, value: JBLabel, maxValue: Int, unit: String = "px") = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            val metrics = value.getFontMetrics(value.font)
            value.preferredSize = Dimension(metrics.stringWidth("$maxValue $unit"), rowHeight)
            add(slider, BorderLayout.CENTER)
            add(value, BorderLayout.EAST)
            preferredSize = Dimension(controlWidth, rowHeight)
        }
        val fillIntensityControl = sliderControl(tabFillIntensity, fillIntensityValue, MAX_TAB_FILL_INTENSITY, "%")
        val heightControl = sliderControl(tabHeight, tabHeightValue, MAX_TAB_HEIGHT)
        val fontControl = sliderControl(tabFontSize, fontSizeValue, maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2))
        val groupSpacingControl = sliderControl(groupSpacing, groupSpacingValue, MAX_GROUP_SPACING)
        val separatorThicknessControl = sliderControl(groupSeparatorThickness, separatorThicknessValue, MAX_OUTLINE_THICKNESS)
        val separatorHeightControl = sliderControl(groupSeparatorHeight, separatorHeightValue, 100, "%")
        fun section(title: String, content: JComponent) = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 12)
                add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
                add(JPanel(GridBagLayout()).apply {
                    isOpaque = false
                    add(JSeparator(), GridBagConstraints().apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
        fun form(master: JComponent, fields: List<AppearanceField>) = SettingsAppearanceForm(
            master, fields, { fields.mapNotNull { it.label }.maxOfOrNull { it.preferredSize.width } ?: 0 },
            controlWidth, rowHeight,
            { (availableWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right).coerceAtLeast(1) },
        )
        val styleControlWidth = maxOf(JBUI.scale(180), iconSize.preferredSize.width, outlineStyle.preferredSize.width)
        val styleLabelWidth = { listOf(sizeLabel, positionLabel, initialsLabel, letterWeightLabel,
            styleLabel, thicknessLabel, fillInsetLabel, fillIntensityLabel).maxOf { it.preferredSize.width } }
        val styleColumns = SettingsAppearanceColumns(
            { (availableWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right).coerceAtLeast(1) },
            { styleLabelWidth() + styleControlWidth + JBUI.scale(36) },
            maximumColumns = 3,
        )
        fun styleForm(master: JComponent, fields: List<AppearanceField>) = SettingsAppearanceForm(
            master, fields, styleLabelWidth, styleControlWidth, rowHeight, styleColumns::columnWidth,
            singleColumn = true,
        )
        styleColumns.add(section("Icon", styleForm(showIcons, listOf(
            AppearanceField(sizeLabel, iconSize, stretch = false),
            AppearanceField(positionLabel, iconPosition, stretch = false),
            AppearanceField(initialsLabel, iconInitials, stretch = false),
            AppearanceField(letterWeightLabel, iconLetterWeight, stretch = false),
        ))))
        styleColumns.add(section("Outline", styleForm(showOutline, listOf(
            AppearanceField(styleLabel, outlineStyle),
            AppearanceField(thicknessLabel, pixelField(outlineThickness), stretch = false),
        ))))
        styleColumns.add(section("Fill", styleForm(showTabBackground, listOf(
            AppearanceField(fillInsetLabel, pixelField(tabFillInset), stretch = false),
            AppearanceField(fillIntensityLabel, fillIntensityControl),
        ))))
        appearanceTabs.addTab("Style", styleColumns)
        val moreInfoRow = SettingsRadioRow(moreInfoLabel, moreInfoButtons.values.toList(), wrapChoices = true) {
            (availableWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right - JBUI.scale(24)).coerceAtLeast(1)
        }
        appearanceTabs.addTab("Tab layout", form(moreInfoRow, listOf(
            AppearanceField(tabHeightLabel, heightControl), AppearanceField(fontSizeLabel, fontControl),
            AppearanceField(closeButtonLabel, closeButtonPosition, stretch = false), AppearanceField(branchPositionLabel, gitInfoControl),
        )))
        val groupLabelWidth = { maxOf(backgroundHighlight.labelWidth(), joinedHighlight.labelWidth(),
            listOf(groupSpacingLabel, groupVariantLabel,
                separatorThicknessLabel, separatorHeightLabel, separatorColorLabel).maxOf { it.preferredSize.width }) }
        val groupColumns = SettingsAppearanceColumns(
            { (availableWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right).coerceAtLeast(1) },
            { maxOf(groupLabelWidth() + controlWidth + JBUI.scale(36),
                hideGroupedTabIcons.preferredSize.width + JBUI.scale(24)) },
        )
        fun groupForm(master: JComponent, fields: List<AppearanceField>, label: JBLabel? = null) =
            SettingsAppearanceForm(master, fields, groupLabelWidth, controlWidth, rowHeight,
                groupColumns::columnWidth, label, singleColumn = true)
        backgroundHighlightForm = backgroundHighlight.form(controlWidth, rowHeight, groupLabelWidth, groupColumns::columnWidth)
        joinedHighlightForm = joinedHighlight.form(controlWidth, rowHeight, groupLabelWidth, groupColumns::columnWidth)
        groupColumns.add(section("Group highlight", JPanel(BorderLayout()).apply {
            isOpaque = false
            add(groupForm(showGroupOutline, listOf(AppearanceField(groupVariantLabel, groupHighlightStyle))), BorderLayout.NORTH)
            add(groupVariantDetails, BorderLayout.CENTER)
        }))
        groupColumns.add(JPanel(BorderLayout(0, JBUI.scale(16))).apply {
            isOpaque = false
            add(section("Group icon", groupForm(showGroupIcon, listOf(
                AppearanceField(null, hideGroupedTabIcons),
            ))), BorderLayout.NORTH)
            add(section("Spacing & separator", JPanel(BorderLayout()).apply {
                isOpaque = false
                add(groupForm(groupSpacingControl, emptyList(), groupSpacingLabel), BorderLayout.NORTH)
                add(groupForm(showGroupSeparator, listOf(
                    AppearanceField(separatorThicknessLabel, separatorThicknessControl),
                    AppearanceField(separatorHeightLabel, separatorHeightControl),
                    AppearanceField(separatorColorLabel, groupSeparatorColor),
                )), BorderLayout.CENTER)
            }), BorderLayout.CENTER)
        })
        appearanceTabs.addTab("Group layout", groupColumns)
        // Навигация локальна для открытой формы: не выбирает Custom и не меняет Apply.
        appearanceTabs.addChangeListener { layoutChanged() }
        add(appearanceTabs, BorderLayout.CENTER)
        showTabBackground.accessibleContext.accessibleName = "Fill tab"
        showTabBackground.toolTipText = "Color the tab using Intensity and Inset. Matches the icon color unless a Tab color is assigned."
        showTabBackground.addActionListener { appearanceChanged() }
        tabFillInset.accessibleContext.accessibleName = "Tab fill inset in pixels"
        tabFillInset.accessibleContext.accessibleDescription = "From 0 to 6 pixels. Use arrow keys for one-pixel steps."
        tabFillInset.toolTipText = "Inset the fill from every tab edge. Outline, title and icon stay in place."
        tabFillInset.addChangeListener { appearanceChanged() }
        tabFillIntensity.accessibleContext.accessibleName = "Tab fill intensity in percent"
        tabFillIntensity.accessibleContext.accessibleDescription = "From 0% (transparent) to 100% (solid). Use arrow keys for one-percent steps."
        tabFillIntensity.toolTipText = "Adjust fill opacity. 0% hides the fill; 100% uses the solid tab color. Icon and outline colors stay unchanged."
        tabFillIntensity.addChangeListener { appearanceChanged() }
        groupSpacing.accessibleContext.accessibleName = "Spacing between project groups in pixels"
        groupSpacing.accessibleContext.accessibleDescription = "From 0 to $MAX_GROUP_SPACING pixels. Use arrow keys for one-pixel steps."
        groupSpacing.toolTipText = "Extra space between visible groups and between grouped and ungrouped tabs. Hidden for split groups until Arrange Tabs by Group."
        groupSpacing.addChangeListener { appearanceChanged() }
        showGroupOutline.toolTipText = "Highlight each intact group using its Tab color and Outline color, or icon color for Automatic. Split groups hide their highlight until Arrange Tabs by Group."
        showGroupOutline.addActionListener { appearanceChanged() }
        groupHighlightStyle.accessibleContext.accessibleName = "Group highlight variant"
        groupHighlightStyle.toolTipText = "Shared background or joined tabs. Each variant keeps its own settings."
        groupHighlightStyle.addActionListener { appearanceChanged() }
        showGroupSeparator.toolTipText = "Draw a vertical pipe at intact group boundaries. Hidden for split groups until Arrange Tabs by Group. Reserves space even when Group spacing is zero."
        showGroupSeparator.addActionListener { appearanceChanged() }
        groupSeparatorThickness.accessibleContext.accessibleName = "Group separator thickness in pixels"
        groupSeparatorThickness.toolTipText = "Pipe thickness: 1–6 px."
        groupSeparatorThickness.addChangeListener { appearanceChanged() }
        groupSeparatorHeight.accessibleContext.accessibleName = "Group separator height in percent"
        groupSeparatorHeight.toolTipText = "Pipe height: 20–100% of the actual tab height."
        groupSeparatorHeight.addChangeListener { appearanceChanged() }
        groupSeparatorColor.accessibleContext.accessibleName = "Group separator color"
        groupSeparatorColor.toolTipText = "Neutral theme color, or the left group's outline color. If the left tab is ungrouped, uses the right group."
        groupSeparatorColor.addActionListener { appearanceChanged() }
        showGroupIcon.toolTipText = "Show the group's own icon before its first tab. Hidden for split groups until Arrange Tabs by Group. Tab icons can be hidden inside the group below."
        showGroupIcon.addActionListener { appearanceChanged() }
        hideGroupedTabIcons.toolTipText = "Hide member tab icons while their group icon is visible. Ungrouped tabs still follow Style → Show tab icons. If the group icon is hidden, member icons return."
        hideGroupedTabIcons.addActionListener { appearanceChanged() }
        showOutline.toolTipText = "Use each project's icon color or its custom outline color."
        iconSize.accessibleContext.accessibleName = "Project icon size"
        iconSize.toolTipText = "Size of tab icons and shared group icons."
        iconPosition.accessibleContext.accessibleName = "Project icon position"
        iconInitials.accessibleContext.accessibleName = "Generated icon initials"
        iconInitials.toolTipText = "A: one letter. AB: two uppercase initials (Backend → BA). Ab: the same initials in mixed case (Ba). Uses IDE abbreviations when available. SVG/PNG artwork is unchanged."
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
                if (!syncing && button.isSelected && displayedMoreInfo != mode) {
                    branchStatus.isSelected = mode.isBranch
                    appearanceChanged()
                }
            }
        }
        branchPosition.accessibleContext.accessibleName = "Git info position"
        branchPosition.toolTipText = "Place the Git branch and changed-line count together to the left or right of the project name."
        branchPosition.addChangeListener { appearanceChanged() }
        branchStatus.accessibleContext.accessibleName = "Show Git status with branch"
        branchStatus.toolTipText = "Show added and removed lines alongside Git branch or Git Branch Compact. Saved tracked changes against HEAD, staged and unstaged."
        branchStatus.addActionListener { appearanceChanged() }
        showIcons.addActionListener { appearanceChanged() }
        showOutline.addActionListener { appearanceChanged() }
        iconSize.addChangeListener { appearanceChanged() }
        iconPosition.addChangeListener { appearanceChanged() }
        iconInitials.addChangeListener { appearanceChanged() }
        iconLetterWeight.addChangeListener { appearanceChanged() }
        outlineStyle.addActionListener { appearanceChanged() }
        outlineThickness.addChangeListener { appearanceChanged() }
        closeButtonPosition.addChangeListener { appearanceChanged() }
        tabHeight.addChangeListener { appearanceChanged() }
        tabFontSize.accessibleContext.accessibleName = "Window tab font size in pixels"
        tabFontSize.accessibleContext.accessibleDescription = "Use arrow keys for one-pixel steps."
        tabFontSize.toolTipText = "Adjust tab title text. The default size follows the IDE font."
        tabFontSize.addChangeListener {
            if (!syncing) fontSizeSelection.select(tabFontSize.value, defaultTabFontSize())
            appearanceChanged()
        }
    }

    fun value(): TabAppearanceState = TabAppearanceState(
        showIcons = showIcons.isSelected,
        showOutline = showOutline.isSelected,
        showTabBackground = showTabBackground.isSelected,
        tabFillInset = tabFillInset.value as Int,
        tabFillIntensity = tabFillIntensity.value,
        iconSize = iconSize.selectedItem as TabIconSize,
        iconPosition = iconPosition.selectedItem as TabIconPosition,
        iconInitials = iconInitials.selectedItem as TabIconInitials,
        iconLetterWeight = iconLetterWeight.selectedItem as TabIconLetterWeight,
        outlineThickness = outlineThickness.value as Int,
        outlineStyle = outlineStyle.selectedItem as TabOutlineStyle,
        closeButtonPosition = closeButtonPosition.selectedItem as TabCloseButtonPosition,
        tabHeight = tabHeight.value,
        groupSpacing = groupSpacing.value,
        showGroupOutline = showGroupOutline.isSelected,
        groupHighlightStyle = groupHighlightStyle.selectedItem as GroupHighlightStyle,
        groupBackground = backgroundHighlight.value(),
        groupJoined = joinedHighlight.value(),
        showGroupSeparator = showGroupSeparator.isSelected,
        groupSeparatorThickness = groupSeparatorThickness.value,
        groupSeparatorHeight = groupSeparatorHeight.value,
        groupSeparatorColor = groupSeparatorColor.selectedItem as GroupSeparatorColor,
        showGroupIcon = showGroupIcon.isSelected,
        hideGroupedTabIcons = hideGroupedTabIcons.isSelected,
        tabFontSize = fontSizeSelection.storedSize,
        showProjectPath = moreInfoButtons.getValue(TabMoreInfo.PROJECT_PATH).isSelected,
        showGitBranch = moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected },
        gitBranchFormat = if (moreInfoButtons.getValue(TabMoreInfo.GIT_BRANCH_COMPACT).isSelected)
            TabGitBranchFormat.COMPACT else TabGitBranchFormat.FULL,
        showGitStatus = moreInfoButtons.getValue(TabMoreInfo.GIT_STATUS).isSelected ||
            (moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected } && branchStatus.isSelected),
        branchPosition = branchPosition.selectedItem as TabBranchPosition,
    )

    fun setValue(value: TabAppearanceState) {
        syncing = true
        try {
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
            groupSpacing.value = value.groupSpacing
            showGroupOutline.isSelected = value.showGroupOutline
            groupHighlightStyle.selectedItem = value.groupHighlightStyle.normalized()
            backgroundHighlight.setValue(value.groupBackground)
            joinedHighlight.setValue(value.groupJoined)
            showGroupSeparator.isSelected = value.showGroupSeparator
            groupSeparatorThickness.value = value.groupSeparatorThickness
            groupSeparatorHeight.value = value.groupSeparatorHeight
            groupSeparatorColor.selectedItem = value.groupSeparatorColor
            showGroupIcon.isSelected = value.showGroupIcon
            hideGroupedTabIcons.isSelected = value.hideGroupedTabIcons
            moreInfoButtons.getValue(value.moreInfo()).isSelected = true
            branchStatus.isSelected = value.showGitBranch && value.showGitStatus
            branchPosition.selectedItem = value.branchPosition
        } finally {
            syncing = false
        }
        displayedMoreInfo = value.moreInfo()
    }

    fun refreshEnabled(enabled: Boolean) {
        listOf(showIcons, showOutline, showTabBackground, closeButtonPosition, closeButtonLabel,
            tabHeight, tabHeightLabel, tabHeightValue, tabFontSize, fontSizeLabel, fontSizeValue, moreInfoLabel,
            groupSpacing, groupSpacingLabel, groupSpacingValue, showGroupOutline, showGroupSeparator, showGroupIcon)
            .forEach { it.isEnabled = enabled }
        listOf(iconSize, sizeLabel).forEach {
            it.isEnabled = enabled && (showIcons.isSelected || showGroupIcon.isSelected)
        }
        listOf(iconPosition, iconInitials, iconLetterWeight,
            positionLabel, initialsLabel, letterWeightLabel).forEach {
            it.isEnabled = enabled && showIcons.isSelected
        }
        listOf(outlineStyle, outlineThickness, styleLabel, thicknessLabel).forEach {
            it.isEnabled = enabled && showOutline.isSelected
        }
        listOf(groupHighlightStyle, groupVariantLabel).forEach {
            it.isEnabled = enabled && showGroupOutline.isSelected
        }
        val highlightEnabled = enabled && showGroupOutline.isSelected
        backgroundHighlight.refresh(highlightEnabled)
        joinedHighlight.refresh(highlightEnabled)
        val details = if (groupHighlightStyle.selectedItem == GroupHighlightStyle.JOINED) joinedHighlightForm else backgroundHighlightForm
        if (groupVariantDetails.components.firstOrNull() !== details) {
            groupVariantDetails.removeAll()
            groupVariantDetails.add(details, BorderLayout.CENTER)
            layoutChanged()
        }
        listOf(groupSeparatorThickness, separatorThicknessLabel, separatorThicknessValue,
            groupSeparatorHeight, separatorHeightLabel, separatorHeightValue, groupSeparatorColor, separatorColorLabel).forEach {
            it.isEnabled = enabled && showGroupSeparator.isSelected
        }
        hideGroupedTabIcons.isEnabled = enabled && showGroupIcon.isSelected
        listOf(tabFillInset, fillInsetLabel, tabFillIntensity, fillIntensityLabel, fillIntensityValue).forEach {
            it.isEnabled = enabled && showTabBackground.isSelected
        }
        moreInfoButtons.values.forEach { it.isEnabled = enabled }
        listOf(branchPosition, branchPositionLabel).forEach {
            it.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isGit && it.value.isSelected }
        }
        branchStatus.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected }
        fillIntensityValue.text = "${tabFillIntensity.value}%"
        tabHeightValue.text = "${tabHeight.value} px"
        groupSpacingValue.text = "${groupSpacing.value} px"
        separatorThicknessValue.text = "${groupSeparatorThickness.value} px"
        separatorHeightValue.text = "${groupSeparatorHeight.value}%"
        fontSizeValue.text = "${tabFontSize.value} px"
    }

    fun installMarkers(watch: (JComponent, (TabAppearanceState) -> Any?) -> Unit) {
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
        watch(groupSpacingLabel) { it.groupSpacing }
        watch(showGroupOutline) { it.showGroupOutline }
        watch(groupVariantLabel) { it.groupHighlightStyle }
        backgroundHighlight.installMarkers(watch)
        joinedHighlight.installMarkers(watch)
        watch(showGroupSeparator) { it.showGroupSeparator }
        watch(separatorThicknessLabel) { it.groupSeparatorThickness }
        watch(separatorHeightLabel) { it.groupSeparatorHeight }
        watch(separatorColorLabel) { it.groupSeparatorColor }
        watch(showGroupIcon) { it.showGroupIcon }
        watch(hideGroupedTabIcons) { it.hideGroupedTabIcons }
        watch(fontSizeLabel) { it.tabFontSize }
        watch(closeButtonLabel) { it.closeButtonPosition }
        watch(moreInfoLabel) { it.moreInfo() }
        watch(branchPositionLabel) { it.branchPosition }
        watch(branchStatus) { it.showGitBranch && it.showGitStatus }
    }
}

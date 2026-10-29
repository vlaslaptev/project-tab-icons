package dev.vlas.projecttabicons

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.ButtonGroup
import javax.swing.JSeparator
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SpinnerNumberModel
import kotlin.math.roundToInt

private const val MAX_ACTIVE_TAB_HIGHLIGHT_LEVEL = 100
private const val MAX_INACTIVE_TAB_DIMMING_LEVEL = 100

private fun activeTabHighlightLevel(value: Double): Int =
    (normalizedActiveTabHighlight(value) * MAX_ACTIVE_TAB_HIGHLIGHT_LEVEL / MAX_ACTIVE_TAB_HIGHLIGHT).roundToInt()

private fun inactiveTabDimmingLevel(value: Double): Int =
    (normalizedInactiveTabDimming(value) * MAX_INACTIVE_TAB_DIMMING_LEVEL / MAX_INACTIVE_TAB_DIMMING).roundToInt()

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
    private val styleLabel = JBLabel("Style:").apply { labelFor = outlineStyle }
    private val thicknessLabel = JBLabel("Thickness:").apply { labelFor = outlineThickness }
    private val tabFillInset = pixelSpinner(0, MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET)
    private val fillInsetLabel = JBLabel("Inset:").apply { labelFor = tabFillInset }
    private val tabFillIntensity = JSlider(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY, DEFAULT_TAB_FILL_INTENSITY).apply {
        minorTickSpacing = 1
        snapToTicks = true
        isOpaque = false
    }
    private val fillIntensityLabel = JBLabel("Intensity:").apply { labelFor = tabFillIntensity }
    private val inactiveTabDimming = JSlider(0, MAX_INACTIVE_TAB_DIMMING_LEVEL, inactiveTabDimmingLevel(DEFAULT_INACTIVE_TAB_DIMMING)).apply {
        minorTickSpacing = 1; snapToTicks = true; isOpaque = false
    }
    private val inactiveTabDimmingLabel = JBLabel("Inactive tab dimming:").apply { labelFor = inactiveTabDimming }
    private var displayedInactiveTabDimming = DEFAULT_INACTIVE_TAB_DIMMING
    private val activeTabHighlight = JSlider(0, MAX_ACTIVE_TAB_HIGHLIGHT_LEVEL, activeTabHighlightLevel(DEFAULT_ACTIVE_TAB_HIGHLIGHT)).apply {
        minorTickSpacing = 1; snapToTicks = true; isOpaque = false
    }
    private val activeTabHighlightLabel = JBLabel("Active tab highlight:").apply { labelFor = activeTabHighlight }
    private var displayedActiveTabHighlight = DEFAULT_ACTIVE_TAB_HIGHLIGHT
    private val closeButtonPosition = appearanceChoice(TabCloseButtonPosition.entries)
    private val closeButtonLabel = JBLabel("Close button:").apply { labelFor = closeButtonPosition }
    private val fontSizeSelection = TabFontSizeSelection()
    private val useIdeFontSize = JBCheckBox("Use IDE font size", true).apply {
        isOpaque = false
        toolTipText = "Follow the IDE font size automatically. Clear to use a fixed size for project tabs."
    }
    private val tabFontSize = pixelSpinner(defaultTabFontSize(), minOf(MIN_TAB_FONT_SIZE, defaultTabFontSize()),
        maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2))
    private val fontSizeLabel = JBLabel("Font size:").apply { labelFor = tabFontSize }
    private val tabHeight = pixelSpinner(DEFAULT_TAB_HEIGHT, DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT)
    private val tabHeightLabel = JBLabel("Height:").apply { labelFor = tabHeight }
    private val tabCorners = ComboBox(TabCornerMode.entries.toTypedArray())
    private val tabCornersLabel = JBLabel("Tab corners:").apply { labelFor = tabCorners }
    private val groupSpacing = pixelSpinner(0, 0, MAX_GROUP_SPACING)
    private val groupSpacingLabel = JBLabel("Group spacing:").apply { labelFor = groupSpacing }
    private val showGroupOutline = JBCheckBox("Show group highlight")
    private val groupHighlightStyle = ComboBox(arrayOf(GroupHighlightStyle.BACKGROUND, GroupHighlightStyle.JOINED))
    private val groupVariantLabel = JBLabel("Variant:").apply { labelFor = groupHighlightStyle }
    private val backgroundHighlight = GroupHighlightControls(GroupHighlightStyle.BACKGROUND) { appearanceChanged() }
    private val joinedHighlight = GroupHighlightControls(GroupHighlightStyle.JOINED) { appearanceChanged() }
    private val groupVariantDetails = JPanel(BorderLayout()).apply { isOpaque = false }
    private lateinit var backgroundHighlightForm: JComponent
    private lateinit var joinedHighlightForm: JComponent
    private val showGroupSeparator = JBCheckBox("Show separator (pipe)")
    private val groupSeparatorThickness = pixelSpinner(1, MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS)
    private val separatorThicknessLabel = JBLabel("Thickness:").apply { labelFor = groupSeparatorThickness }
    private val groupSeparatorHeight = JSlider(20, 100, DEFAULT_GROUP_SEPARATOR_HEIGHT).apply {
        minorTickSpacing = 1; snapToTicks = true; isOpaque = false
    }
    private val separatorHeightLabel = JBLabel("Height:").apply { labelFor = groupSeparatorHeight }
    private val groupSeparatorColor = ComboBox(GroupSeparatorColor.entries.toTypedArray())
    private val separatorColorLabel = JBLabel("Color:").apply { labelFor = groupSeparatorColor }
    private val showGroupIcon = JBCheckBox("Show group icon")
    private val hideGroupedTabIcons = JBCheckBox("Hide tab icons inside the group")
    private val moreInfoLabel = JBLabel("Show:")
    private val moreInfoButtons = TabMoreInfo.entries.associateWith { mode ->
        JBRadioButton(when (mode) {
            TabMoreInfo.NONE -> "Name only"
            TabMoreInfo.PROJECT_PATH -> "Project path"
            TabMoreInfo.GIT_BRANCH -> "Branch"
            TabMoreInfo.GIT_BRANCH_COMPACT -> "Compact branch"
            TabMoreInfo.GIT_STATUS -> "Changed lines"
        })
    }
    private val branchPosition = appearanceChoice(TabBranchPosition.entries)
    private val branchPositionLabel = JBLabel("Git info position:").apply { labelFor = branchPosition }
    private val branchStatus = JBCheckBox("Also show changed lines").apply { isOpaque = false }
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
        val controlWidth = maxOf(groupSeparatorColor.preferredSize.width,
            groupHighlightStyle.preferredSize.width, JBUI.scale(240))
        val fillIntensityControl = percentField(tabFillIntensity, "Tab fill intensity in percent", controlWidth, rowHeight)
        val inactiveTabDimmingControl = percentField(inactiveTabDimming, "Inactive tab dimming in percent", controlWidth, rowHeight)
        val activeTabHighlightControl = percentField(activeTabHighlight, "Active tab highlight in percent", controlWidth, rowHeight)
        val heightControl = pixelField(tabHeight)
        val fontControl = pixelField(tabFontSize)
        val groupSpacingControl = pixelField(groupSpacing)
        val separatorThicknessControl = pixelField(groupSeparatorThickness)
        val separatorHeightControl = percentField(groupSeparatorHeight, "Group separator height in percent", controlWidth, rowHeight)
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
        val tabLabelWidth = { listOf(tabHeightLabel, tabCornersLabel, fontSizeLabel, closeButtonLabel, branchPositionLabel,
            inactiveTabDimmingLabel, activeTabHighlightLabel).maxOf { it.preferredSize.width } }
        val tabColumns = SettingsAppearanceColumns(
            { (availableWidth() - appearanceTabs.insets.left - appearanceTabs.insets.right).coerceAtLeast(1) },
            { tabLabelWidth() + controlWidth + JBUI.scale(36) },
        )
        fun tabForm(master: JComponent, fields: List<AppearanceField>, label: JBLabel? = null) =
            SettingsAppearanceForm(master, fields, tabLabelWidth, controlWidth, rowHeight,
                tabColumns::columnWidth, label, singleColumn = true)
        val moreInfoRow = SettingsRadioRow(moreInfoLabel, moreInfoButtons.values.toList(), wrapChoices = true) {
            (tabColumns.columnWidth() - JBUI.scale(24)).coerceAtLeast(1)
        }
        tabColumns.add(JPanel(BorderLayout(0, JBUI.scale(16))).apply {
            isOpaque = false
            add(section("Content", tabForm(moreInfoRow, listOf(
                AppearanceField(branchPositionLabel, branchPosition, stretch = false),
                AppearanceField(null, branchStatus),
            ))), BorderLayout.NORTH)
            add(section("Selection", tabForm(activeTabHighlightControl, listOf(
                AppearanceField(inactiveTabDimmingLabel, inactiveTabDimmingControl),
            ), activeTabHighlightLabel)), BorderLayout.CENTER)
        })
        tabColumns.add(section("Size & position", tabForm(compactField(heightControl), listOf(
            AppearanceField(tabCornersLabel, tabCorners),
            AppearanceField(null, useIdeFontSize),
            AppearanceField(fontSizeLabel, fontControl, stretch = false),
            AppearanceField(closeButtonLabel, closeButtonPosition, stretch = false),
        ), tabHeightLabel)))
        appearanceTabs.addTab("Tab layout", tabColumns)
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
                add(groupForm(compactField(groupSpacingControl), emptyList(), groupSpacingLabel), BorderLayout.NORTH)
                add(groupForm(showGroupSeparator, listOf(
                    AppearanceField(separatorThicknessLabel, separatorThicknessControl, stretch = false),
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
        inactiveTabDimming.accessibleContext.accessibleName = "Inactive tab dimming in percent"
        inactiveTabDimming.accessibleContext.accessibleDescription = "From 0% to $MAX_INACTIVE_TAB_DIMMING_LEVEL%. Use arrow keys for one-percent steps."
        inactiveTabDimming.toolTipText = "Reduce the fill and outline of each inactive tab, including tabs inside the active group. 0% disables dimming; 75% is the default. Text and icons keep their colors."
        inactiveTabDimming.addChangeListener { appearanceChanged() }
        activeTabHighlight.accessibleContext.accessibleName = "Active tab highlight in percent"
        activeTabHighlight.accessibleContext.accessibleDescription = "From 0% to $MAX_ACTIVE_TAB_HIGHLIGHT_LEVEL%. Use arrow keys for one-percent steps."
        activeTabHighlight.toolTipText = "Adjust the active tab's background and fill highlighting. 0% keeps the theme's selection; 25% is the default. Hover brightening never exceeds the active tab's level."
        activeTabHighlight.addChangeListener { appearanceChanged() }
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
        tabCorners.accessibleContext.accessibleName = "Tab corner mode"
        tabCorners.toolTipText = "Auto: square corners on Windows, IDEA theme on macOS. Square and IDEA theme override this on either platform. Applies to tab backgrounds, outlines and groups."
        moreInfoButtons.forEach { (mode, button) ->
            button.accessibleContext.accessibleName = "Show more info: ${button.text}"
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
        branchStatus.accessibleContext.accessibleName = "Also show changed lines with branch"
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
        tabCorners.addActionListener { appearanceChanged() }
        tabFontSize.accessibleContext.accessibleName = "Window tab font size in pixels"
        tabFontSize.accessibleContext.accessibleDescription = "Use arrow keys for one-pixel steps."
        tabFontSize.toolTipText = "Adjust tab title text. The default size follows the IDE font."
        tabFontSize.addChangeListener {
            if (!syncing && !useIdeFontSize.isSelected) fontSizeSelection.reset(tabFontSize.value as Int, defaultTabFontSize())
            appearanceChanged()
        }
        useIdeFontSize.addActionListener {
            if (!syncing) {
                syncing = true
                try {
                    tabFontSize.value = fontSizeSelection.reset(
                        if (useIdeFontSize.isSelected) 0 else tabFontSize.value as Int, defaultTabFontSize())
                } finally { syncing = false }
                appearanceChanged()
            }
        }
    }

    fun value(): TabAppearanceState = TabAppearanceState(
        showIcons = showIcons.isSelected,
        showOutline = showOutline.isSelected,
        showTabBackground = showTabBackground.isSelected,
        tabFillInset = tabFillInset.value as Int,
        tabFillIntensity = tabFillIntensity.value,
        // Старое целое значение между делениями новой шкалы не меняется при просмотре
        // или возврате ползунка на исходное деление.
        inactiveTabDimming = if (inactiveTabDimming.value == inactiveTabDimmingLevel(displayedInactiveTabDimming)) displayedInactiveTabDimming
            else inactiveTabDimming.value * MAX_INACTIVE_TAB_DIMMING / MAX_INACTIVE_TAB_DIMMING_LEVEL,
        activeTabHighlight = if (activeTabHighlight.value == activeTabHighlightLevel(displayedActiveTabHighlight)) displayedActiveTabHighlight
            else activeTabHighlight.value * MAX_ACTIVE_TAB_HIGHLIGHT / MAX_ACTIVE_TAB_HIGHLIGHT_LEVEL,
        iconSize = iconSize.selectedItem as TabIconSize,
        iconPosition = iconPosition.selectedItem as TabIconPosition,
        iconInitials = iconInitials.selectedItem as TabIconInitials,
        iconLetterWeight = iconLetterWeight.selectedItem as TabIconLetterWeight,
        outlineThickness = outlineThickness.value as Int,
        outlineStyle = outlineStyle.selectedItem as TabOutlineStyle,
        closeButtonPosition = closeButtonPosition.selectedItem as TabCloseButtonPosition,
        tabHeight = tabHeight.value as Int,
        tabCorners = tabCorners.selectedItem as TabCornerMode,
        groupSpacing = groupSpacing.value as Int,
        showGroupOutline = showGroupOutline.isSelected,
        groupHighlightStyle = groupHighlightStyle.selectedItem as GroupHighlightStyle,
        groupBackground = backgroundHighlight.value(),
        groupJoined = joinedHighlight.value(),
        showGroupSeparator = showGroupSeparator.isSelected,
        groupSeparatorThickness = groupSeparatorThickness.value as Int,
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
            useIdeFontSize.isSelected = value.tabFontSize == 0
            (tabFontSize.model as SpinnerNumberModel).apply {
                maximum = maxOf(MAX_TAB_FONT_SIZE, defaultTabFontSize() + 2, size)
                minimum = minOf(MIN_TAB_FONT_SIZE, defaultTabFontSize())
            }
            tabFontSize.value = size
            showIcons.isSelected = value.showIcons
            showOutline.isSelected = value.showOutline
            showTabBackground.isSelected = value.showTabBackground
            tabFillInset.value = value.tabFillInset
            tabFillIntensity.value = value.tabFillIntensity
            displayedInactiveTabDimming = normalizedInactiveTabDimming(value.inactiveTabDimming)
            inactiveTabDimming.value = inactiveTabDimmingLevel(displayedInactiveTabDimming)
            displayedActiveTabHighlight = normalizedActiveTabHighlight(value.activeTabHighlight)
            activeTabHighlight.value = activeTabHighlightLevel(displayedActiveTabHighlight)
            iconSize.selectedItem = value.iconSize
            iconPosition.selectedItem = value.iconPosition
            iconInitials.selectedItem = value.iconInitials
            iconLetterWeight.selectedItem = value.iconLetterWeight
            outlineStyle.selectedItem = value.outlineStyle
            outlineThickness.value = value.outlineThickness
            closeButtonPosition.selectedItem = value.closeButtonPosition
            tabHeight.value = value.tabHeight
            tabCorners.selectedItem = value.tabCorners
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
            tabHeight, tabHeightLabel, tabCorners, tabCornersLabel, useIdeFontSize, moreInfoLabel,
            inactiveTabDimming, inactiveTabDimmingLabel,
            activeTabHighlight, activeTabHighlightLabel,
            groupSpacing, groupSpacingLabel, showGroupOutline, showGroupSeparator, showGroupIcon)
            .forEach { it.isEnabled = enabled }
        listOf(tabFontSize, fontSizeLabel).forEach {
            it.isEnabled = enabled && !useIdeFontSize.isSelected
        }
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
        listOf(groupSeparatorThickness, separatorThicknessLabel,
            groupSeparatorHeight, separatorHeightLabel, groupSeparatorColor, separatorColorLabel).forEach {
            it.isEnabled = enabled && showGroupSeparator.isSelected
        }
        hideGroupedTabIcons.isEnabled = enabled && showGroupIcon.isSelected
        listOf(tabFillInset, fillInsetLabel, tabFillIntensity, fillIntensityLabel).forEach {
            it.isEnabled = enabled && showTabBackground.isSelected
        }
        moreInfoButtons.values.forEach { it.isEnabled = enabled }
        listOf(branchPosition, branchPositionLabel).forEach {
            it.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isGit && it.value.isSelected }
        }
        branchStatus.isEnabled = enabled && moreInfoButtons.entries.any { it.key.isBranch && it.value.isSelected }
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
        watch(tabCornersLabel) { it.tabCorners }
        watch(inactiveTabDimmingLabel) { it.inactiveTabDimming }
        watch(activeTabHighlightLabel) { it.activeTabHighlight }
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
        watch(useIdeFontSize) { it.tabFontSize == 0 }
        watch(closeButtonLabel) { it.closeButtonPosition }
        watch(moreInfoLabel) { it.moreInfo() }
        watch(branchPositionLabel) { it.branchPosition }
        watch(branchStatus) { it.showGitBranch && it.showGitStatus }
    }
}

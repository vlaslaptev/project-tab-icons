package dev.vlas.projecttabicons

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.AvatarUtils
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.Icon
import javax.swing.JPanel

internal typealias PreviewArtwork = Map<String, Map<TabIconSize, Icon>>

private data class PreviewSample(val title: String, val path: String, val branch: String,
                                 val contourId: String?, val color: Color,
                                 val groupMember: Boolean = false, val extendedOnly: Boolean = false)

internal enum class PreviewTabCount(val title: String) {
    THREE("3 tabs"), FIVE("5 tabs"),
}

private val PREVIEW_GROUP_COLOR = Color(0xC084FC)
private val PREVIEW_SAMPLES = listOf(
    PreviewSample("Frontend", "~/web", "feature/improve-project-tabs", "globe-simple", Color(0x60A5FA), groupMember = true),
    PreviewSample("Backend", "~/api", "feature/WASD-3245", null, PREVIEW_GROUP_COLOR, groupMember = true),
    PreviewSample("MobileApp", "~/mobile", "hotfix/hotfix-067", "device-mobile", Color(0x22D3EE), groupMember = true, extendedOnly = true),
    PreviewSample("Tools", "~/tools", "main", "brackets-curly", Color(0x4ADE80)),
    PreviewSample("pet_projects", "~/pet_projects", "B2B-SaaS-03", "paw-print", Color(0xFB923C), extendedOnly = true),
)
private const val PREVIEW_GROUP_ARTWORK = "preview-group"

/** Постоянные примеры: никаких окон, файлов проекта или записей в настройки. */
internal class WindowTabsPreview : JPanel(null) {
    private var tabHeight = DEFAULT_TAB_HEIGHT
    private var theme = PreviewIdeTheme.CURRENT
    private var appearance = TabAppearanceState.defaults()
    private var palette = theme.palette()
    private val groupSeparator = ProjectTabGroupSeparator()
    private var groupArtwork = emptyMap<TabIconSize, Icon>()
    private var groupIcon: Icon? = null
    private var groupIconSlot = 0
    private var tabCount = PreviewTabCount.THREE
    private var samples = PREVIEW_SAMPLES.filterNot { it.extendedOnly }
    private var artwork: PreviewArtwork = emptyMap()
    private var selectedIndex = 1
    private var sampleTabs: List<PreviewTab> = createTabs()
    private val groupTabCount get() = samples.count { it.groupMember }

    private val groupDescription: String get() {
        val grouped = samples.filter { it.groupMember }.joinToString(" + ") { it.title }
        val ungrouped = samples.filterNot { it.groupMember }
        return "$grouped are grouped; ${ungrouped.joinToString(" + ") { it.title }} ${if (ungrouped.size == 1) "is" else "are"} ungrouped."
    }

    private fun createTabs() = samples.mapIndexed { index, sample ->
        PreviewTab(sample.title, sample.path, sample.branch, sample.contourId, sample.color, index == selectedIndex, sample.groupMember) {
            selectTab(index)
        }
    }

    init {
        isOpaque = false
        sampleTabs.forEach { add(it) }
        getAccessibleContext().accessibleName = "IDE window preview"
        updateAccessibleDescription()
    }

    private fun updateAccessibleDescription() {
        getAccessibleContext().accessibleDescription = "Decorative macOS window with traffic lights, toolbar, ${samples.size} project tabs and an editor fragment. $groupDescription Click a project tab to preview activation and inactive tab dimming. Toolbar and file tabs are not interactive."
    }

    fun showTabCount(value: PreviewTabCount) {
        if (tabCount == value) return
        val selectedTitle = samples[selectedIndex].title
        tabCount = value
        samples = PREVIEW_SAMPLES.filter { value == PreviewTabCount.FIVE || !it.extendedOnly }
        selectedIndex = samples.indexOfFirst { it.title == selectedTitle }.takeIf { it >= 0 } ?: 1
        removeAll()
        sampleTabs = createTabs()
        sampleTabs.forEach { tab -> tab.showArtwork(artwork); add(tab) }
        updateAccessibleDescription()
        showAppearance(appearance)
    }

    private fun selectTab(index: Int) {
        selectedIndex = index
        sampleTabs.forEachIndexed { tabIndex, tab -> tab.select(tabIndex == selectedIndex) }
        showAppearance(appearance)
    }

    private fun contentHeight(): Int = maxOf(sampleTabs.maxOf { it.minimumContentHeight() },
        if (groupIcon != null) groupIconMinimumHeight(appearance) else 0)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(400), JBUI.scale(180))

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun doLayout() {
        // Высота всего окна постоянна. Растут только табы; фрагмент редактора сокращается.
        val top = JBUI.scale(PreviewWindowChrome.TOOLBAR_HEIGHT)
        val availableHeight = (height - top).coerceAtLeast(0)
        val edge = JBUI.scale(8)
        val available = (width - edge * 2).coerceAtLeast(0)
        val gap = (if (appearance.enabled) JBUI.scale(groupBoundarySpacing(appearance)) else 0).coerceIn(0, available / sampleTabs.size)
        groupIconSlot = if (groupIcon != null) groupIconSlotWidth(appearance).takeIf { it <= available / 4 } ?: 0 else 0
        val iconHeight = maxOf(JBUI.scale(tabHeight), groupIconMinimumHeight(appearance)).coerceAtMost(availableHeight)
        val sharedIconVisible = visibleGroupIcon(groupIcon, groupIconSlot, iconHeight, appearance) != null
        val hideGroupMembers = appearance.hideGroupedTabIcons && sharedIconVisible
        sampleTabs.forEachIndexed { index, tab -> tab.setTabIconHidden(hideGroupMembers && samples[index].groupMember) }
        val actualHeight = maxOf(JBUI.scale(tabHeight), contentHeight()).coerceAtMost(availableHeight)
        val tabSpace = (available - gap - groupIconSlot).coerceAtLeast(0)
        sampleTabs.forEachIndexed { index, tab ->
            val groupOffset = if (index >= groupTabCount) gap else 0
            val left = edge + groupIconSlot + tabSpace * index / sampleTabs.size + groupOffset
            val right = edge + groupIconSlot + tabSpace * (index + 1) / sampleTabs.size + groupOffset
            tab.setBounds(left, top, right - left, actualHeight)
        }
        val shared = appearance.enabled && appearance.showGroupOutline
        val geometry = groupSurfaceGeometry()
        val bounds = geometry.bounds
        sampleTabs.forEachIndexed { index, tab ->
            tab.setGroupSurface(if (shared && samples[index].groupMember) ProjectTabGroupSurfaceState(appearance,
                geometry.copy(bounds = Rectangle2D.Double(bounds.x - tab.x, bounds.y - tab.y, bounds.width, bounds.height)),
                Rectangle2D.Double(0.0, 0.0, tab.width.toDouble(), tab.height.toDouble()),
                ProjectTabGroupColors(PREVIEW_GROUP_COLOR, PREVIEW_GROUP_COLOR),
                index == 0, index == groupTabCount - 1, selectedIndex == index + 1) else null)
            tab.doLayout()
        }
    }

    private fun groupSurfaceGeometry(): ProjectTabGeometry {
        val first = sampleTabs[0]
        val last = sampleTabs[groupTabCount - 1]
        val left = first.x - groupIconSlot
        val geometry = palette.geometry((last.x + last.width - left).coerceAtLeast(0), first.height)
        return geometry.copy(bounds = Rectangle2D.Double(geometry.bounds.x + left,
            geometry.bounds.y + first.y, geometry.bounds.width, geometry.bounds.height))
    }

    // Форма внешнего окна не зависит от скруглений проектных табов выбранной темы.
    private fun windowShape() = RoundRectangle2D.Double(0.5, 0.5,
        (width - 1).coerceAtLeast(0).toDouble(), (height - 1).coerceAtLeast(0).toDouble(),
        JBUI.scale(20).toDouble(), JBUI.scale(20).toDouble())

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.clip(windowShape())
            val bottom = sampleTabs.first().let { it.y + it.height }
            val active = samples[selectedIndex]
            PreviewWindowChrome.paint(g, width, height, bottom, palette, active.title, active.branch, samples.size)
            if (appearance.enabled && appearance.showGroupOutline) {
                // Префикс общей иконки принадлежит первому сегменту, включая его активную заливку.
                val first = sampleTabs[0]
                val left = first.x - groupIconSlot
                val geometry = groupSurfaceGeometry()
                val bounds = geometry.bounds
                val prefix = g.create(left, first.y, groupIconSlot, first.height) as Graphics2D
                try {
                    val state = ProjectTabGroupSurfaceState(appearance,
                        geometry.copy(bounds = Rectangle2D.Double(bounds.x - left, bounds.y - first.y, bounds.width, bounds.height)),
                        Rectangle2D.Double(groupIconSlot.toDouble(), 0.0, first.width.toDouble(), first.height.toDouble()),
                        ProjectTabGroupColors(PREVIEW_GROUP_COLOR, PREVIEW_GROUP_COLOR),
                        true, false, selectedIndex == 1)
                    ProjectTabGroupSurface.paint(prefix, groupIconSlot, first.height, state,
                        first.surfaceColors(), selectedIndex == 0, first.isPreviewHovered())
                } finally { prefix.dispose() }
            }
        } finally { g.dispose() }
    }

    override fun paintChildren(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.clip(windowShape())
            super.paintChildren(g)
            if (appearance.enabled) {
                val first = sampleTabs[0]
                val last = sampleTabs[groupTabCount - 1]
                val icon = visibleGroupIcon(groupIcon, groupIconSlot, first.height, appearance)
                val iconX = first.x - groupIconSlot + (groupIconSlot - (icon?.iconWidth ?: 0)) / 2
                icon?.paintIcon(this, g, iconX, first.y + first.insets.top +
                    (first.height - first.insets.top - first.insets.bottom - icon.iconHeight) / 2)
                if (appearance.showGroupSeparator) {
                    val next = sampleTabs[groupTabCount]
                    val color = if (appearance.groupSeparatorColor == GroupSeparatorColor.GROUP) PREVIEW_GROUP_COLOR
                        else palette.groupSeparatorColor
                    groupSeparator.paint(g, (last.x + last.width + next.x) / 2.0, first.y.toDouble(), first.height,
                        appearance.groupSeparatorThickness, appearance.groupSeparatorHeight, color)
                }
            }
            g.color = palette.chrome.border
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.draw(windowShape())
        } finally { g.dispose() }
    }

    fun showTheme(value: PreviewIdeTheme) {
        // Сначала читаем палитру: при ошибке выбранная тема и готовое превью остаются прежними.
        value.palette()
        theme = value
        showAppearance(appearance)
    }

    fun showArtwork(artwork: PreviewArtwork) {
        this.artwork = artwork
        groupArtwork = artwork[PREVIEW_GROUP_ARTWORK].orEmpty()
        sampleTabs.forEach { it.showArtwork(artwork) }
        showAppearance(appearance)
    }

    fun showAppearance(appearance: TabAppearanceState) {
        this.appearance = appearance
        tabHeight = if (appearance.enabled) {
            appearance.tabHeight.coerceIn(DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT)
        } else DEFAULT_TAB_HEIGHT
        palette = theme.palette()
        groupIcon = if (appearance.enabled && appearance.showGroupIcon) {
            val image = groupArtwork[appearance.iconSize] ?: ProjectInitialsIcon("Gr", PREVIEW_GROUP_COLOR,
                appearance.iconSize.pixels, DEFAULT_ICON_INITIALS, TabIconLetterWeight.BOLD, projectName = "Group")
            CenteredProjectIcon(image, appearance.iconSize.pixels, artwork = { image })
        } else null
        sampleTabs.forEach { it.showAppearance(appearance, palette) }
        revalidate()
        // null-layout требует явно обновить дочернюю геометрию и при смене шрифта,
        // когда внешний размер самого Preview остался прежним.
        doLayout()
        repaint()
    }

    companion object {
        /** Подготовка ресурсов выполняется фоновым загрузчиком настроек. */
        fun loadArtwork(): PreviewArtwork = PREVIEW_SAMPLES.mapNotNull { sample ->
            sample.contourId?.let { id ->
                val svg = ContourCatalog.entries.first { it.id == id }.svg(sample.color)
                id to TabIconSize.entries.associateWith { size ->
                    RecentProjectIconHelper.createIcon(svg, true, size.pixels)
                }
            }
        }.toMap() + (PREVIEW_GROUP_ARTWORK to TabIconSize.entries.associateWith { size ->
            val svg = ContourCatalog.entries.first { it.id == "folder" }.svg(PREVIEW_GROUP_COLOR)
            RecentProjectIconHelper.createIcon(svg, true, size.pixels)
        })
    }
}

private class PreviewTab(
    private val title: String,
    samplePath: String,
    private val sampleBranch: String,
    private val contourId: String?,
    private val color: Color,
    private var selected: Boolean,
    private val groupMember: Boolean,
    private val activate: () -> Unit,
) : JPanel(BorderLayout()) {
    private var groupSurface: ProjectTabGroupSurfaceState? = null
    private var hovered = false
    private var customizationEnabled = true
    private var palette = PreviewIdeTheme.CURRENT.palette()
    private val baseLayout = layout as BorderLayout
    private val baseBorder = JBUI.Borders.empty(0, 4)
    private val previewLayout = ProjectTabIconLayout(baseLayout, null, TabIconPosition.RIGHT,
        TabCloseButtonPosition.LEFT, centerProjectName = true)
    private val fullTitle = "$title [$samplePath]"
    private val caption = ProjectTabBranchCaption().apply {
        font = JBFont.medium()
        minimumSize = Dimension(0, preferredSize.height)
        foreground = if (selected) palette.selectedForeground else palette.foreground
        toolTipText = fullTitle
    }
    private val close = JBLabel().apply { border = JBUI.Borders.emptyLeft(6) }
    private var artwork = emptyMap<TabIconSize, Icon>()
    private var tabIcon: CenteredProjectIcon? = null

    init {
        isOpaque = false
        // Неактивные вкладки сохраняют место под крестик, как в полосе окон IDEA.
        add(close, BorderLayout.WEST)
        add(caption, BorderLayout.CENTER)
        layout = previewLayout
        isFocusable = true
        toolTipText = "Click to preview $title as the active tab"
        val click = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) { activate() }
            override fun mouseEntered(event: MouseEvent) { hovered = true; parent?.repaint() }
            override fun mouseExited(event: MouseEvent) { hovered = false; parent?.repaint() }
        }
        listOf(this, caption, close).forEach { it.addMouseListener(click) }
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "activatePreview")
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "activatePreview")
        actionMap.put("activatePreview", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = activate()
        })
        select(selected)
    }

    fun select(value: Boolean) {
        selected = value
        getAccessibleContext().accessibleName = "$title, ${if (selected) "active" else "inactive"} example tab"
        getAccessibleContext().accessibleDescription = "Click or press Space to preview this tab as active."
    }

    // Как в ProjectTabsContainerLayout: border уже учитывает иконку и защиту линии.
    fun minimumContentHeight(): Int = layout.preferredLayoutSize(this).height

    fun showArtwork(icons: PreviewArtwork) {
        artwork = contourId?.let(icons::get).orEmpty()
    }

    fun setTabIconHidden(hidden: Boolean) {
        val visibleIcon = tabIcon?.takeUnless { hidden }
        previewLayout.projectIcon = visibleIcon
        (border as? ProjectTabBorder)?.hideProjectIconPreview = hidden
    }

    fun setGroupSurface(value: ProjectTabGroupSurfaceState?) {
        groupSurface = value
        (border as? ProjectTabBorder)?.sharedGroupSurfacePreview = value != null
    }

    fun showAppearance(appearance: TabAppearanceState, theme: PreviewThemePalette) {
        customizationEnabled = appearance.enabled
        palette = theme
        caption.foreground = if (selected) palette.selectedForeground else palette.foreground
        caption.branchForeground = palette.chrome.muted
        close.icon = when {
            !selected -> EmptyIcon.create(AllIcons.Actions.Close.iconWidth, AllIcons.Actions.Close.iconHeight)
            palette.nativeClose -> AllIcons.Actions.Close
            else -> PreviewCloseIcon(palette.closeColor)
        }
        caption.font = if (appearance.enabled && appearance.tabFontSize > 0) {
            JBFont.medium().deriveFont(JBUI.scale(appearance.tabFontSize).toFloat())
        } else JBFont.medium()
        font = caption.font
        val mode = appearance.moreInfo()
        val status = if (appearance.enabled && mode.isGit && appearance.showGitStatus) ProjectTabExtraInfo.Status(when (title) {
            "Frontend" -> GitTabDiffStatus(GitTabDiffState.READY, 128, 12)
            "Backend" -> GitTabDiffStatus(GitTabDiffState.READY, 443, 44)
            else -> GitTabDiffStatus(GitTabDiffState.READY)
        }) else null
        val extra = if (appearance.enabled && mode.isBranch) ProjectTabExtraInfo.Branch(
            sampleBranch, compact = mode == TabMoreInfo.GIT_BRANCH_COMPACT, status = status,
        ) else status
        val light = palette.background.let { it.red * 299 + it.green * 587 + it.blue * 114 > 128000 }
        caption.addedForeground = Color(if (light) 0x2E7D32 else 0x6AAB73)
        caption.removedForeground = Color(if (light) 0xC62828 else 0xDB5C5C)
        caption.border = if (extra != null) JBUI.Borders.empty(0, 12) else null
        caption.toolTipText = extra?.tooltip ?: fullTitle
        caption.showTitle(if (!appearance.enabled || appearance.showProjectPath) fullTitle else title,
            extra, appearance.branchPosition)
        if (!appearance.enabled) {
            previewLayout.restoreCloseInsets()
            layout = baseLayout
            border = baseBorder
            revalidate()
            repaint()
            return
        }
        layout = previewLayout
        // Backend — буквенный пример; остальные образцы сохраняют свои SVG.
        val image = artwork[appearance.iconSize] ?: ProjectInitialsIcon(AvatarUtils.initials(title), color,
            appearance.iconSize.pixels, appearance.iconInitials, appearance.iconLetterWeight, projectName = title)
        val icon = if (appearance.showIcons) CenteredProjectIcon(
            image, appearance.iconSize.pixels, artwork = { image },
        ) else null
        tabIcon = icon
        previewLayout.projectIcon = icon
        previewLayout.position = appearance.iconPosition
        previewLayout.closeButtonPosition = appearance.closeButtonPosition
        previewLayout.centerProjectName = mode == TabMoreInfo.NONE
        border = ProjectTabBorder(baseBorder, icon, color.takeIf { appearance.showOutline },
            appearance.outlineThickness, appearance.outlineStyle,
            tabColor = color.takeIf { appearance.showTabBackground }, tabFillInset = appearance.tabFillInset,
            tabFillIntensity = appearance.tabFillIntensity, geometryOverride = palette::geometry,
            surfaceColors = { surfaceColors() }, inactiveTabDimming = appearance.inactiveTabDimming,
            activeTabHighlight = appearance.activeTabHighlight).apply {
                sharedGroupSurfacePreview = groupMember && appearance.showGroupOutline
            }
        revalidate()
        repaint()
    }

    fun isPreviewHovered(): Boolean = hovered

    fun surfaceColors(): ProjectTabSurfaceColors = ProjectTabSurfaceColors(palette.stripBackground,
        when { selected -> palette.selectedBackground; hovered -> palette.hoverBackground; else -> palette.background },
        palette.background, if (customizationEnabled) ProjectTabSurfaceState.of(selected, hovered)
        else ProjectTabSurfaceState.NATIVE)

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val colors = surfaceColors()
        groupSurface?.let {
            ProjectTabGroupSurface.paint(graphics as Graphics2D, width, height, it, colors, selected, hovered)
            return
        }
        // Обычная вкладка использует тот же border, что и реальная полоса IDEA.
        if (!customizationEnabled) ProjectTabSurface.paint(graphics as Graphics2D, width, height,
            palette.geometry(width, height), colors, null, 0)
    }
}

/** Размер и hit slot совпадают с нативным крестиком; цвет берётся только из палитры образца. */
private class PreviewCloseIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = AllIcons.Actions.Close.iconWidth
    override fun getIconHeight(): Int = AllIcons.Actions.Close.iconHeight
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val left = JBUI.scale(5)
            val right = iconWidth - left
            val top = JBUI.scale(5)
            val bottom = iconHeight - top
            g.drawLine(left, top, right, bottom)
            g.drawLine(right, top, left, bottom)
        } finally { g.dispose() }
    }
}

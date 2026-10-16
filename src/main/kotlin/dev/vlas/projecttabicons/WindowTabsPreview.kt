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
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Постоянные примеры: никаких окон, файлов проекта или записей в настройки. */
internal class WindowTabsPreview : JPanel(null) {
    private var tabHeight = DEFAULT_TAB_HEIGHT
    private var theme = PreviewIdeTheme.CURRENT
    private var appearance = TabAppearanceState.defaults()
    private var palette = theme.palette()
    private val sampleTabs = listOf(
        PreviewTab("Frontend", "~/web", "feature/improve-project-tabs", "globe-simple", Color(0x60A5FA), selected = false),
        PreviewTab("Backend", "~/api", "feature/PROJ-1287-show-current-branch", null, Color(0xC084FC), selected = true),
        PreviewTab("Tools", "~/tools", "main", "brackets-curly", Color(0x4ADE80), selected = false),
    )

    init {
        isOpaque = false
        sampleTabs.forEach { add(it) }
        getAccessibleContext().accessibleName = "IDE window preview"
        getAccessibleContext().accessibleDescription = "Decorative macOS window with traffic lights, toolbar, three project tabs and an editor fragment. Backend is active. Toolbar and file tabs are not interactive."
    }

    private fun contentHeight(): Int = sampleTabs.maxOf { it.minimumContentHeight() }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(400), JBUI.scale(180))

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun doLayout() {
        // Высота всего окна постоянна. Растут только табы; фрагмент редактора сокращается.
        val top = JBUI.scale(PreviewWindowChrome.TOOLBAR_HEIGHT)
        val actualHeight = maxOf(JBUI.scale(tabHeight), contentHeight()).coerceAtMost((height - top).coerceAtLeast(0))
        val edge = JBUI.scale(8)
        val available = (width - edge * 2).coerceAtLeast(0)
        sampleTabs.forEachIndexed { index, tab ->
            val left = edge + available * index / sampleTabs.size
            val right = edge + available * (index + 1) / sampleTabs.size
            tab.setBounds(left, top, right - left, actualHeight)
            tab.doLayout()
        }
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
            PreviewWindowChrome.paint(g, width, height, bottom, palette)
        } finally { g.dispose() }
    }

    override fun paintChildren(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.clip(windowShape())
            super.paintChildren(g)
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

    fun showAppearance(appearance: TabAppearanceState) {
        this.appearance = appearance
        tabHeight = if (appearance.enabled) {
            appearance.tabHeight.coerceIn(DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT)
        } else DEFAULT_TAB_HEIGHT
        palette = theme.palette()
        sampleTabs.forEach { it.showAppearance(appearance, palette) }
        revalidate()
        // null-layout требует явно обновить дочернюю геометрию и при смене шрифта,
        // когда внешний размер самого Preview остался прежним.
        doLayout()
        repaint()
    }
}

private class PreviewTab(
    private val title: String,
    samplePath: String,
    private val sampleBranch: String,
    contourId: String?,
    private val color: Color,
    private val selected: Boolean,
) : JPanel(BorderLayout()) {
    private var palette = PreviewIdeTheme.CURRENT.palette()
    private var tabFillColor: Color? = null
    private var tabFillInset = 0
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
    private val artwork = contourId?.let { id ->
        val contour = ContourCatalog.entries.first { it.id == id }
        val svg = contour.svg(color)
        TabIconSize.entries.associateWith { size ->
            RecentProjectIconHelper.createIcon(svg, true, size.pixels)
        }
    }.orEmpty()

    init {
        isOpaque = false
        // Неактивные вкладки сохраняют место под крестик, как в полосе окон IDEA.
        add(close, BorderLayout.WEST)
        add(caption, BorderLayout.CENTER)
        layout = previewLayout
        getAccessibleContext().accessibleName = "$title, ${if (selected) "active" else "inactive"} example tab"
    }

    fun minimumContentHeight(): Int = maxOf(layout.preferredLayoutSize(this).height,
        maxOf(caption.getFontMetrics(caption.font).height,
            if (layout === previewLayout) previewLayout.projectIcon?.iconHeight ?: 0 else 0) +
            insets.top + insets.bottom + JBUI.scale(4))

    fun showAppearance(appearance: TabAppearanceState, theme: PreviewThemePalette) {
        palette = theme
        tabFillColor = color.takeIf { appearance.enabled && appearance.showTabBackground }
        tabFillInset = appearance.tabFillInset
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
        // Backend — буквенный пример; Frontend и Tools сохраняют свои SVG.
        val image = artwork[appearance.iconSize] ?: ProjectInitialsIcon(AvatarUtils.initials(title), color,
            appearance.iconSize.pixels, appearance.iconInitials, appearance.iconLetterWeight, projectName = title)
        val icon = if (appearance.showIcons) CenteredProjectIcon(
            image, appearance.iconSize.pixels, artwork = { image },
        ) else null
        previewLayout.projectIcon = icon
        previewLayout.position = appearance.iconPosition
        previewLayout.closeButtonPosition = appearance.closeButtonPosition
        previewLayout.centerProjectName = mode == TabMoreInfo.NONE
        border = ProjectTabBorder(baseBorder, icon, color.takeIf { appearance.showOutline },
            appearance.outlineThickness, appearance.outlineStyle, geometryOverride = palette::geometry,
            surfaceColors = null)
        revalidate()
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        ProjectTabSurface.paint(graphics as Graphics2D, width, height, palette.geometry(width, height),
            ProjectTabSurfaceColors(palette.stripBackground,
                if (selected) palette.selectedBackground else palette.background), tabFillColor, tabFillInset)
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

package dev.vlas.projecttabicons

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.JBColor
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.Color

internal enum class TabIconSize(val pixels: Int, private val title: String) {
    SMALL(20, "Small · 20 px"),
    MEDIUM(24, "Medium · 24 px"),
    LARGE(28, "Large · 28 px");

    override fun toString(): String = title
}

internal enum class TabIconInitials(val count: Int, private val title: String) {
    ONE_LETTER(1, "One letter"), TWO_INITIALS(2, "Two initials"),
    // Legacy name/title сохраняют чтение выбора из XML 0.3.26; подпись UI задаёт renderer.
    NAME_PREFIX(2, "First two letters · Ab");

    override fun toString(): String = title
}

internal val DEFAULT_ICON_INITIALS = TabIconInitials.NAME_PREFIX

internal enum class TabIconLetterWeight(val fontStyle: Int, private val title: String) {
    NORMAL(Font.PLAIN, "Normal"), BOLD(Font.BOLD, "Bold");

    override fun toString(): String = title
}

internal enum class TabIconPosition(private val title: String) {
    RIGHT("Right"), LEFT("Left · after close");

    override fun toString(): String = title
}

internal enum class TabCloseButtonPosition(private val title: String) {
    LEFT("Left"), RIGHT("Right");

    override fun toString(): String = title
}

internal fun defaultTabCloseButtonPosition(): TabCloseButtonPosition =
    if (SystemInfoRt.isWindows) TabCloseButtonPosition.RIGHT else TabCloseButtonPosition.LEFT

internal fun defaultProjectTabAccent(): Color = JBColor.namedColor("Checkbox.Background.Selected",
    JBColor.namedColor("Component.focusColor", Color(0x3574F0)))

/** Windows owns its tab strip even when customization is disabled. Never persist this fallback. */
internal fun TabAppearanceState.renderedAppearance(): TabAppearanceState =
    if (enabled || !SystemInfoRt.isWindows) this else TabAppearanceState.forPreset(TabAppearancePreset.DZEN, false).copy(
        outlineThickness = 2,
        closeButtonPosition = TabCloseButtonPosition.RIGHT,
        inactiveTabDimming = 0.0,
    )

internal enum class TabMoreInfo(val title: String) {
    NONE("None"), PROJECT_PATH("Project path"), GIT_BRANCH("Git branch"),
    GIT_BRANCH_COMPACT("Git Branch Compact"), GIT_STATUS("Git status");

    val isBranch: Boolean get() = this == GIT_BRANCH || this == GIT_BRANCH_COMPACT
    val isGit: Boolean get() = isBranch || this == GIT_STATUS
}

internal enum class TabBranchPosition(private val title: String) {
    LEFT("Left"), RIGHT("Right");

    override fun toString(): String = title
}

// WindowTabsComponent в целевом SDK использует 30 логических пикселей.
internal const val DEFAULT_TAB_HEIGHT = 30
internal const val MAX_TAB_HEIGHT = DEFAULT_TAB_HEIGHT * 2
internal const val MAX_GROUP_SPACING = 32
internal const val DEFAULT_GROUP_SEPARATOR_HEIGHT = 60

internal enum class GroupHighlightStyle(private val title: String) {
    BACKGROUND("Shared background"), JOINED("Joined tabs"),
    // Только для чтения XML/JSON 0.3.80. После загрузки заменяются на BACKGROUND.
    CLASSIC("Classic outline"), BADGE("Group badge");

    fun normalized(): GroupHighlightStyle = if (this == JOINED) JOINED else BACKGROUND
    override fun toString(): String = title
}

internal enum class GroupSeparatorColor(private val title: String) {
    NEUTRAL("Neutral"), GROUP("Group color");
    override fun toString(): String = title
}

internal const val MIN_OUTLINE_THICKNESS = 1
internal const val MAX_OUTLINE_THICKNESS = 6
internal const val DEFAULT_OUTLINE_THICKNESS = 2
internal const val MIN_TAB_FILL_INTENSITY = 0
internal const val MAX_TAB_FILL_INTENSITY = 100
internal const val DEFAULT_TAB_FILL_INTENSITY = 50
internal const val DEFAULT_INACTIVE_TAB_DIMMING = 60.0
internal const val MAX_INACTIVE_TAB_DIMMING = 80.0
internal const val DEFAULT_ACTIVE_TAB_HIGHLIGHT = 5.0
internal const val MAX_ACTIVE_TAB_HIGHLIGHT = 20.0

internal fun normalizedActiveTabHighlight(value: Double): Double =
    if (value.isFinite()) value.coerceIn(0.0, MAX_ACTIVE_TAB_HIGHLIGHT) else DEFAULT_ACTIVE_TAB_HIGHLIGHT

internal fun normalizedInactiveTabDimming(value: Double): Double =
    if (value.isFinite()) value.coerceIn(0.0, MAX_INACTIVE_TAB_DIMMING) else DEFAULT_INACTIVE_TAB_DIMMING
internal const val MIN_TAB_FILL_INSET = 0
internal const val MAX_TAB_FILL_INSET = 6
internal const val MIN_TAB_FONT_SIZE = 10
internal const val MAX_TAB_FONT_SIZE = 24
// Больший сохранённый размер остаётся доступным после смены UI-шрифта.
private const val MAX_STORED_TAB_FONT_SIZE = 48

internal fun defaultTabFontSize(): Int = JBUI.unscale(JBFont.medium().size)

internal enum class TabAppearancePreset(val title: String) {
    CUSTOM("Custom"), DEFAULT("Default"), FILLED("Filled"), BOLD("Bold"), GIT("Git"),
    // Имя enum сохраняет чтение старого XML; новое название показано только в UI.
    DZEN("Relax");
}

internal enum class TabOutlineStyle(private val title: String) {
    BOTTOM("Bottom only"),
    ROUNDED("Rounded corners"),
    FULL("Full outline");

    override fun toString(): String = title
}

internal enum class TabCornerMode(private val title: String) {
    AUTO("Auto"), SQUARE("Square"), IDEA_THEME("IDEA theme");

    override fun toString(): String = title
}

// XMLB в SDK 262 пропускает final-скаляры без явной аннотации хранения.
// Значения конструктора задают смысл полей, опущенных в XML.
// Для первого запуска и кнопки сброса используется defaults().
internal data class TabAppearanceState(
    @field:OptionTag @JvmField val showIcons: Boolean = true,
    @field:OptionTag @JvmField val showOutline: Boolean = true,
    @field:OptionTag @JvmField val iconSize: TabIconSize = TabIconSize.LARGE,
    @field:OptionTag @JvmField val iconPosition: TabIconPosition = TabIconPosition.RIGHT,
    @field:OptionTag @JvmField val outlineThickness: Int = DEFAULT_OUTLINE_THICKNESS,
    @field:OptionTag @JvmField val outlineStyle: TabOutlineStyle = TabOutlineStyle.ROUNDED,
    @field:OptionTag @JvmField val closeButtonPosition: TabCloseButtonPosition = defaultTabCloseButtonPosition(),
    @field:OptionTag @JvmField val tabHeight: Int = DEFAULT_TAB_HEIGHT,
    // Старые XML и пресеты сохраняют геометрию темы; первый запуск и Reset используют AUTO.
    @field:OptionTag @JvmField val tabCorners: TabCornerMode = TabCornerMode.IDEA_THEME,
    @field:OptionTag @JvmField val showProjectPath: Boolean = false,
    @field:OptionTag @JvmField val showGitBranch: Boolean = false,
    @field:OptionTag @JvmField val gitBranchFormat: TabGitBranchFormat = TabGitBranchFormat.FULL,
    @field:OptionTag @JvmField val showGitStatus: Boolean = false,
    @field:OptionTag @JvmField val branchPosition: TabBranchPosition = TabBranchPosition.RIGHT,
    @field:OptionTag @JvmField val enabled: Boolean = true,
    @field:OptionTag @JvmField val showTabBackground: Boolean = false,
    // Отсутствующее поле старого XML всегда сохраняет ручное оформление.
    @field:OptionTag @JvmField val preset: TabAppearancePreset = TabAppearancePreset.CUSTOM,
    // 0 означает исходный шрифт IDEA; в UI показывается его реальный размер.
    @field:OptionTag @JvmField val tabFontSize: Int = 0,
    @field:OptionTag @JvmField val tabFillInset: Int = 0,
    // Старый XML без этого поля сохраняет прежнюю заливку 50%.
    @field:OptionTag @JvmField val tabFillIntensity: Int = DEFAULT_TAB_FILL_INTENSITY,
    // В XML остаётся фактическое затемнение; шкала UI пересчитывается отдельно.
    @field:OptionTag @JvmField val inactiveTabDimming: Double = DEFAULT_INACTIVE_TAB_DIMMING,
    // В XML остаются реальные п.п. осветления: старые целые значения читаются без смены смысла.
    @field:OptionTag @JvmField val activeTabHighlight: Double = DEFAULT_ACTIVE_TAB_HIGHLIGHT,
    // Пропущенное поле тоже использует текущий дефолт Ab; явный выбор XML сохраняется.
    @field:OptionTag @JvmField val iconInitials: TabIconInitials = DEFAULT_ICON_INITIALS,
    @field:OptionTag @JvmField val iconLetterWeight: TabIconLetterWeight = TabIconLetterWeight.BOLD,
    // Пользовательский пресет использует стабильный ID, не расширяя сохранённый enum.
    @field:OptionTag @JvmField val userPresetId: String? = null,
    @field:OptionTag @JvmField val groupSpacing: Int = 0,
    @field:OptionTag @JvmField val showGroupOutline: Boolean = false,
    @field:OptionTag @JvmField val groupHighlightStyle: GroupHighlightStyle = GroupHighlightStyle.BACKGROUND,
    @field:OptionTag @JvmField val groupBackground: GroupHighlightOptions = GroupHighlightOptions(),
    @field:OptionTag @JvmField val groupJoined: GroupHighlightOptions = GroupHighlightOptions(),
    @field:OptionTag @JvmField val showGroupSeparator: Boolean = false,
    @field:OptionTag @JvmField val groupSeparatorThickness: Int = 1,
    @field:OptionTag @JvmField val groupSeparatorHeight: Int = DEFAULT_GROUP_SEPARATOR_HEIGHT,
    @field:OptionTag @JvmField val groupSeparatorColor: GroupSeparatorColor = GroupSeparatorColor.NEUTRAL,
    @field:OptionTag @JvmField val showGroupIcon: Boolean = false,
    @field:OptionTag @JvmField val hideGroupedTabIcons: Boolean = false,
) {
    // XMLB может записать null в non-null enum, если значение неизвестно текущему SDK.
    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
    fun normalized(): TabAppearanceState = copy(
        iconSize = iconSize ?: TabIconSize.LARGE,
        iconPosition = iconPosition ?: TabIconPosition.RIGHT,
        outlineStyle = outlineStyle ?: TabOutlineStyle.ROUNDED,
        tabCorners = tabCorners ?: TabCornerMode.IDEA_THEME,
        closeButtonPosition = closeButtonPosition ?: defaultTabCloseButtonPosition(),
        branchPosition = branchPosition ?: TabBranchPosition.RIGHT,
        preset = preset ?: TabAppearancePreset.CUSTOM,
        iconInitials = iconInitials ?: DEFAULT_ICON_INITIALS,
        iconLetterWeight = iconLetterWeight ?: TabIconLetterWeight.BOLD,
        groupSeparatorColor = groupSeparatorColor ?: GroupSeparatorColor.NEUTRAL,
        userPresetId = userPresetId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 },
        outlineThickness = outlineThickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS),
        tabHeight = tabHeight.coerceIn(DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT),
        groupSpacing = groupSpacing.coerceIn(0, MAX_GROUP_SPACING),
        groupHighlightStyle = groupHighlightStyle?.normalized() ?: GroupHighlightStyle.BACKGROUND,
        groupBackground = groupBackground?.normalized() ?: GroupHighlightOptions(),
        groupJoined = groupJoined?.normalized() ?: GroupHighlightOptions(),
        groupSeparatorThickness = groupSeparatorThickness.coerceIn(MIN_OUTLINE_THICKNESS, MAX_OUTLINE_THICKNESS),
        groupSeparatorHeight = groupSeparatorHeight.coerceIn(20, 100),
        tabFillInset = tabFillInset.coerceIn(MIN_TAB_FILL_INSET, MAX_TAB_FILL_INSET),
        tabFillIntensity = tabFillIntensity.coerceIn(MIN_TAB_FILL_INTENSITY, MAX_TAB_FILL_INTENSITY),
        inactiveTabDimming = normalizedInactiveTabDimming(inactiveTabDimming),
        activeTabHighlight = normalizedActiveTabHighlight(activeTabHighlight),
        // Старый XML мог хранить обе галочки: путь уже имел приоритет при отрисовке.
        // Ветка и счётчик совместимы; путь по-прежнему исключает всю Git-информацию.
        showGitStatus = showGitStatus && !showProjectPath,
        showGitBranch = showGitBranch && !showProjectPath,
        gitBranchFormat = if (showGitBranch && !showProjectPath) gitBranchFormat ?: TabGitBranchFormat.FULL else TabGitBranchFormat.FULL,
        tabFontSize = if (tabFontSize == 0) 0 else tabFontSize.coerceIn(MIN_TAB_FONT_SIZE, MAX_STORED_TAB_FONT_SIZE),
    )

    fun groupHighlightOptions(): GroupHighlightOptions =
        if (groupHighlightStyle == GroupHighlightStyle.JOINED) groupJoined else groupBackground

    fun moreInfo(): TabMoreInfo = when {
        showProjectPath -> TabMoreInfo.PROJECT_PATH
        showGitBranch && gitBranchFormat == TabGitBranchFormat.COMPACT -> TabMoreInfo.GIT_BRANCH_COMPACT
        showGitBranch -> TabMoreInfo.GIT_BRANCH
        showGitStatus -> TabMoreInfo.GIT_STATUS
        else -> TabMoreInfo.NONE
    }

    /** Встроенные стили сохраняют независимо настроенные углы вкладок и оформление групп. */
    fun withGroupLayoutFrom(value: TabAppearanceState): TabAppearanceState = copy(
        tabCorners = value.tabCorners,
        groupSpacing = value.groupSpacing,
        showGroupOutline = value.showGroupOutline,
        groupHighlightStyle = value.groupHighlightStyle,
        groupBackground = value.groupBackground,
        groupJoined = value.groupJoined,
        showGroupSeparator = value.showGroupSeparator,
        groupSeparatorThickness = value.groupSeparatorThickness,
        groupSeparatorHeight = value.groupSeparatorHeight,
        groupSeparatorColor = value.groupSeparatorColor,
        showGroupIcon = value.showGroupIcon,
        hideGroupedTabIcons = value.hideGroupedTabIcons,
    )

    companion object {
        fun defaults(): TabAppearanceState = TabAppearanceState(
            iconSize = TabIconSize.MEDIUM,
            iconPosition = TabIconPosition.LEFT,
            showProjectPath = false,
            showGitBranch = true,
            branchPosition = TabBranchPosition.RIGHT,
            outlineStyle = TabOutlineStyle.BOTTOM,
            tabHeight = 33,
            tabCorners = TabCornerMode.AUTO,
            preset = TabAppearancePreset.DEFAULT,
            iconInitials = DEFAULT_ICON_INITIALS,
        )

        fun forPreset(preset: TabAppearancePreset, enabled: Boolean): TabAppearanceState {
            // Новый дефолт ветки относится к Default; остальные пресеты сохраняют None.
            val base = defaults().copy(enabled = enabled, preset = preset,
                showGitBranch = preset == TabAppearancePreset.DEFAULT)
            return when (preset) {
                TabAppearancePreset.CUSTOM, TabAppearancePreset.DEFAULT -> base
                TabAppearancePreset.FILLED -> base.copy(
                    iconSize = TabIconSize.SMALL,
                    showTabBackground = true, tabFillInset = 3, tabFillIntensity = 12,
                    outlineThickness = 1, outlineStyle = TabOutlineStyle.FULL, tabHeight = 34,
                )
                TabAppearancePreset.BOLD -> base.copy(
                    iconSize = TabIconSize.LARGE, outlineThickness = 4,
                    outlineStyle = TabOutlineStyle.FULL, tabHeight = 38, tabFontSize = 16,
                )
                TabAppearancePreset.GIT -> base.copy(
                    showIcons = false, showGitBranch = true,
                    gitBranchFormat = TabGitBranchFormat.COMPACT, showGitStatus = true,
                )
                TabAppearancePreset.DZEN -> base.copy(
                    showIcons = false, outlineThickness = 1, tabFontSize = 14,
                )
            }
        }
    }
}

/** Глобальное оформление вкладок. SVG хранится отдельно, в самом проекте. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsAppearance", storages = [Storage("projectTabIcons.xml")])
internal class TabAppearanceSettings :
    SerializablePersistentStateComponent<TabAppearanceState>(TabAppearanceState.defaults()) {

    fun apply(value: TabAppearanceState) {
        SharedIconSettingsLock.withLock { updateState { value.normalized() } }
        refreshTabs()
    }

    override fun loadState(state: TabAppearanceState) {
        SharedIconSettingsLock.withLock { super.loadState(state.normalized()) }
        // Загрузка XML может происходить во время создания сервиса отрисовки.
        ApplicationManager.getApplication().invokeLater { refreshTabs() }
    }

    private fun refreshTabs() {
        ApplicationManager.getApplication().getServiceIfCreated(ProjectTabIconsService::class.java)
            ?.requestRefresh(resetTransientFailure = true)
    }
}

package dev.vlas.projecttabicons

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.UITheme
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.IslandsState
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIDefaults

/** Только локальные цвета и геометрия Preview; установка LookAndFeel не выполняется. */
internal enum class PreviewIdeTheme(val title: String, private val themeId: String? = null,
                                    private val resource: String? = null) {
    CURRENT("Current"),
    ISLANDS_DARK("Islands Dark", "Islands Dark", "/themes/islands/ManyIslandsDark.theme.json"),
    ISLANDS_LIGHT("Islands Light", "Islands Light", "/themes/islands/ManyIslandsLight.theme.json"),
    ISLANDS_DARCULA("Islands Darcula", "Islands Darcula", "/themes/islands/ManyIslandsDarcula.theme.json"),
    DARK("Dark", "ExperimentalDark", "/themes/expUI/expUI_dark.theme.json"),
    LIGHT("Light", "ExperimentalLight", "/themes/expUI/expUI_light.theme.json"),
    LIGHT_HEADER("Light with Light Header", "ExperimentalLightWithLightHeader", "/themes/expUI/expUI_light_with_light_header.theme.json"),
    DARCULA("Darcula", "Darcula", "/themes/darcula.theme.json"),
    HIGH_CONTRAST("High Contrast", "JetBrainsHighContrastTheme", "/themes/islands/HighContrast.theme.json");

    private var cached: PreviewThemePalette? = null

    override fun toString(): String = if (this == CURRENT) {
        "Current · ${LafManager.getInstance().currentUIThemeLookAndFeel?.name ?: "IDE theme"}"
    } else title

    fun palette(): PreviewThemePalette {
        if (this == CURRENT) {
            val islands = ExperimentalUI.isNewUI() && IslandsState.isEnabled()
            val strip = JBColor.namedColor("MainWindow.background", UIUtil.getPanelBackground())
            return PreviewThemePalette(
                strip,
                JBUI.CurrentTheme.MainWindow.Tab.background(true, false, false),
                // IslandsUICustomization не рисует фон обычного неактивного таба.
                // Старый MainWindow.Tab.background относится к Classic painter и здесь даёт чёрный фон.
                if (islands) strip else JBUI.CurrentTheme.MainWindow.Tab.background(false, false, false),
                JBUI.CurrentTheme.MainWindow.Tab.foreground(true, false),
                JBUI.CurrentTheme.MainWindow.Tab.foreground(false, false),
                UIUtil.getLabelForeground(), islands,
                JBUI.getInt("Island.arc", 10),
                chrome = chromePalette({ key, fallback -> JBColor.namedColor(key, fallback) },
                    EditorColorsManager.getInstance().globalScheme), nativeClose = true,
            )
        }
        cached?.let { return it }
        val bytes = requireNotNull(UITheme::class.java.getResourceAsStream(resource!!)) {
            "Missing bundled theme: $title"
        }.use { it.readBytes() }
        // SDK разбирает JSON с родителями и именованными цветами. Таблица изолирована от UIManager.
        val theme = UITheme.loadFromJsonWithParent(bytes, themeId!!, UITheme::class.java.classLoader)
        val dark = theme.isDark
        val defaults = UIDefaults()
        defaults["Panel.background"] = Color(if (dark) 0x3C3F41 else 0xF7F8FA)
        defaults["Label.foreground"] = Color(if (dark) 0xBBBBBB else 0x000000)
        defaults["MainWindow.Tab.selectedForeground"] = Color(if (dark) 0xCED0D6 else 0xC9CCD6)
        defaults["MainWindow.Tab.selectedBackground"] = Color(if (dark) 0x2B2D30 else 0x27282E)
        defaults["MainWindow.Tab.foreground"] = Color(if (dark) 0xB4B8BF else 0xA8ADBD)
        defaults["MainWindow.Tab.background"] = Color(if (dark) 0x131314 else 0x000000)
        theme.applyTheme(defaults)
        // Снимаем значения Color, чтобы образец не зависел от динамического JBColor текущей темы.
        fun color(key: String, fallback: Color) = Color((defaults.getColor(key) ?: fallback).rgb, true)
        val panel = color("Panel.background", Color(if (dark) 0x3C3F41 else 0xF7F8FA))
        val islands = (defaults["Islands"] as? Number)?.toInt() == 1
        val strip = color("MainWindow.background", panel)
        val result = PreviewThemePalette(
            strip,
            color("MainWindow.Tab.selectedBackground", panel),
            // То же правило и для локально выбранной Islands-темы, независимо от темы самой IDE.
            if (islands) strip else color("MainWindow.Tab.background", panel),
            color("MainWindow.Tab.selectedForeground", Color(if (dark) 0xCED0D6 else 0xC9CCD6)),
            color("MainWindow.Tab.foreground", Color(if (dark) 0xB4B8BF else 0xA8ADBD)),
            color("ColorPalette.icon-default-stroke", Color(if (dark) 0x6F737A else 0x6C707E)),
            islands,
            (defaults["Island.arc"] as? Number)?.toInt() ?: 10,
            chrome = chromePalette(::color, theme.editorSchemeId?.let {
                EditorColorsManager.getInstance().getScheme(it)
            }),
        )
        cached = result
        return result
    }
}

internal data class PreviewThemePalette(
    val stripBackground: Color,
    val selectedBackground: Color,
    val background: Color,
    val selectedForeground: Color,
    val foreground: Color,
    val closeColor: Color,
    val islands: Boolean,
    val arc: Int,
    val chrome: PreviewChromePalette,
    val nativeClose: Boolean = false,
) {
    fun geometry(width: Int, height: Int): ProjectTabGeometry = projectTabGeometry(width, height, islands, arc)
}

/** Цвета обрамления читаются отдельно от проектных табов, без установки темы/схемы. */
private fun chromePalette(color: (String, Color) -> Color, scheme: EditorColorsScheme?): PreviewChromePalette {
    val panel = color("Panel.background", Color(0x2B2D30))
    val foreground = color("Label.foreground", Color(0xCED0D6))
    val tabs = color("EditorTabs.background", panel)
    return PreviewChromePalette(
        color("MainToolbar.background", panel), color("MainToolbar.foreground", foreground),
        tabs, color("EditorTabs.underlinedTabBackground", tabs),
        scheme?.defaultBackground?.let { Color(it.rgb, true) } ?: tabs,
        scheme?.defaultForeground?.let { Color(it.rgb, true) } ?: foreground,
        color("Label.infoForeground", color("ContextHelp.foreground", foreground)),
        color("EditorTabs.underTabsBorderColor", color("Separator.separatorColor", panel)),
        color("EditorTabs.underlinedBorderColor", Color(0x548AF7)),
    )
}

internal data class PreviewChromePalette(
    val toolbarBackground: Color,
    val toolbarForeground: Color,
    val fileTabsBackground: Color,
    val selectedFileBackground: Color,
    val editorBackground: Color,
    val editorForeground: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
)

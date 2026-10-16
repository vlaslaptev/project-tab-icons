package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag
import java.awt.Color
import java.nio.file.Path

internal enum class ProjectColorTarget(val title: String) {
    ICON("Icon color"), OUTLINE("Outline color"), TAB("Tab color");
}

internal data class ProjectColorOverrides(val icon: Color? = null, val outline: Color? = null, val tab: Color? = null) {
    fun value(target: ProjectColorTarget): Color? = when (target) {
        ProjectColorTarget.ICON -> icon
        ProjectColorTarget.OUTLINE -> outline
        ProjectColorTarget.TAB -> tab
    }

    fun with(target: ProjectColorTarget, color: Color?): ProjectColorOverrides = when (target) {
        ProjectColorTarget.ICON -> copy(icon = color)
        ProjectColorTarget.OUTLINE -> copy(outline = color)
        ProjectColorTarget.TAB -> copy(tab = color)
    }
}

internal data class ProjectOutlineColorState(
    // Имя colors и имя компонента сохраняют все прежние цвета обводки.
    @field:OptionTag @JvmField val colors: Map<String, String> = emptyMap(),
    @field:OptionTag @JvmField val iconColors: Map<String, String> = emptyMap(),
    @field:OptionTag @JvmField val tabColors: Map<String, String> = emptyMap(),
)

/** Независимые цвета проектов. SVG/PNG сохраняют исходные цвета; iconColors — только для буквенных аватаров. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsOutlineColors", storages = [Storage("projectTabIcons.xml")])
internal class ProjectOutlineColors :
    SerializablePersistentStateComponent<ProjectOutlineColorState>(ProjectOutlineColorState()) {

    fun overrides(path: Path): ProjectColorOverrides {
        val key = projectIconPathKey(path)
        return ProjectColorOverrides(state.iconColors[key]?.let(::parseIconColor),
            state.colors[key]?.let(::parseIconColor), state.tabColors[key]?.let(::parseIconColor))
    }

    fun iconColor(path: Path): Color? = state.iconColors[projectIconPathKey(path)]?.let(::parseIconColor)

    fun apply(changes: Map<Path, ProjectColorOverrides>) {
        updateState { previous ->
            fun updated(source: Map<String, String>, target: ProjectColorTarget): Map<String, String> =
                source.toMutableMap().apply {
                    changes.forEach { (path, value) ->
                        val key = projectIconPathKey(path)
                        val color = value.value(target)
                        if (color == null) remove(key) else put(key, color.hex())
                    }
                }.toMap()
            previous.copy(colors = updated(previous.colors, ProjectColorTarget.OUTLINE),
                iconColors = updated(previous.iconColors, ProjectColorTarget.ICON),
                tabColors = updated(previous.tabColors, ProjectColorTarget.TAB))
        }
    }
}

internal fun projectIconPathKey(path: Path): String = path.toAbsolutePath().normalize().toString()

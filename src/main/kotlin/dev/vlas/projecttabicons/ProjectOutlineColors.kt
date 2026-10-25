package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.ConfigurationException
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

internal data class ProjectColorChanges(val before: ProjectColorOverrides, val wanted: ProjectColorOverrides,
                                        val projectName: String) {
    fun changed(target: ProjectColorTarget): Boolean = before.value(target) != wanted.value(target)
}

internal class ProjectColorConflictException(projectName: String, channel: ProjectColorTarget) : ConfigurationException(
    "$projectName: ${channel.title} changed outside this editor. Your selection has been kept. " +
        "Reload the project's row in Settings, or review and apply the quick action again.",
    "Project Tab Icons color conflict",
)

/** Чистое слияние: одинаковое итоговое значение не конфликтует, чистые каналы берутся из хранилища. */
internal fun mergeProjectColors(previous: ProjectOutlineColorState, changes: Map<Path, ProjectColorChanges>): ProjectOutlineColorState {
    fun updated(source: Map<String, String>, target: ProjectColorTarget): Map<String, String> =
        source.toMutableMap().apply {
            changes.forEach { (path, change) ->
                if (!change.changed(target)) return@forEach
                val key = projectIconPathKey(path)
                val current = source[key]?.let(::parseIconColor)
                val wanted = change.wanted.value(target)
                if (current != change.before.value(target) && current != wanted)
                    throw ProjectColorConflictException(change.projectName, target)
                if (wanted == null) remove(key) else put(key, wanted.hex())
            }
        }.toMap()
    return previous.copy(colors = updated(previous.colors, ProjectColorTarget.OUTLINE),
        iconColors = updated(previous.iconColors, ProjectColorTarget.ICON),
        tabColors = updated(previous.tabColors, ProjectColorTarget.TAB))
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
        val snapshot = state
        return ProjectColorOverrides(snapshot.iconColors[key]?.let(::parseIconColor),
            snapshot.colors[key]?.let(::parseIconColor), snapshot.tabColors[key]?.let(::parseIconColor))
    }

    fun iconColor(path: Path): Color? = state.iconColors[projectIconPathKey(path)]?.let(::parseIconColor)

    fun apply(changes: Map<Path, ProjectColorChanges>, commitSettings: () -> Unit = {}) = SharedIconSettingsLock.withLock {
        // Callback проверяет общие настройки до первой записи; I/O и ожидания EDT здесь нет.
        val next = mergeProjectColors(state, changes)
        commitSettings()
        updateState { next }
        Unit
    }

    override fun loadState(state: ProjectOutlineColorState) {
        SharedIconSettingsLock.withLock { super.loadState(state) }
    }
}

internal fun projectIconPathKey(path: Path): String = path.toAbsolutePath().normalize().toString()

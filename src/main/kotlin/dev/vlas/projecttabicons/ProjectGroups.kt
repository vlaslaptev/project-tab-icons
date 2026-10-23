package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag

internal data class ProjectTabGroup(
    @field:OptionTag @JvmField val id: String = "",
    @field:OptionTag @JvmField val name: String = "",
    @field:OptionTag @JvmField val icon: ProjectGroupIcon = ProjectGroupIcon(),
    @field:OptionTag @JvmField val outlineColor: String = "",
    @field:OptionTag @JvmField val tabColor: String = "",
    // Сохраняем старое поле для чтения/обратной совместимости; наличие группы уже задаёт группировку.
    @field:OptionTag @JvmField val groupTabs: Boolean = false,
    @field:OptionTag @JvmField val memberPositions: Map<String, Int> = emptyMap(),
)
internal data class ProjectGroupsState(
    @field:OptionTag @JvmField val groups: List<ProjectTabGroup> = emptyList(),
    @field:OptionTag @JvmField val memberships: Map<String, String> = emptyMap(),
) {
    fun normalized(): ProjectGroupsState {
        var iconBytes = 0L
        val valid = groups.filter { it.id.isNotBlank() && it.id.length <= 64 && groupNameError(it.name) == null }
            .distinctBy { it.id }.distinctBy { it.name.lowercase(java.util.Locale.ROOT) }.take(50)
            .map { group ->
                val icon = group.icon.normalized()
                iconBytes += icon.svg.length
                group.copy(icon = if (iconBytes <= MAX_LIBRARY_BYTES * 4L / 3) icon else ProjectGroupIcon(),
                    outlineColor = parseIconColor(group.outlineColor)?.hex().orEmpty(),
                    tabColor = parseIconColor(group.tabColor)?.hex().orEmpty())
            }
        val members = memberships.filter { (path, id) ->
            path.isNotBlank() && path.length <= 4096 && valid.any { it.id == id }
        }
        return copy(groups = valid.map { group ->
            val count = members.count { it.value == group.id }
            val used = mutableSetOf<Int>()
            group.copy(memberPositions = group.memberPositions.filter { (path, position) ->
                members[path] == group.id && position in 1..count && used.add(position)
            })
        }, memberships = members)
    }
}
internal fun groupNameError(name: String): String? = when {
    name.isBlank() -> "Enter a group name."
    name.codePointCount(0, name.length) > 40 || name.any { it.isISOControl() } -> "Use at most 40 printable characters."
    name.equals("All projects", true) || name.equals("Ungrouped", true) -> "Choose another group name."
    else -> null
}

/** Группы относятся к локальным путям; переносимые наборы оформления не раскрывают эти пути. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsGroups", storages = [Storage("projectTabIconsGroups.xml")])
internal class ProjectGroups : SerializablePersistentStateComponent<ProjectGroupsState>(ProjectGroupsState()) {
    fun apply(value: ProjectGroupsState) = SharedIconSettingsLock.withLock { updateState { value.normalized() } }
    override fun loadState(state: ProjectGroupsState) = SharedIconSettingsLock.withLock { super.loadState(state.normalized()) }
}

package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag

internal data class ProjectTabGroupVisualStateData(
    @field:OptionTag @JvmField val suspendedGroupIds: List<String> = emptyList(),
) {
    fun normalized() = copy(suspendedGroupIds = suspendedGroupIds.filter { it.isNotBlank() && it.length <= 64 }
        .distinct().sorted().take(50))
}

/** Разрыв отключает оформление до явного Arrange, включая Apply, merge и перезапуск IDE. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsGroupVisuals", storages = [Storage("projectTabIconsGroupVisuals.xml")])
internal class ProjectTabGroupVisualState : SerializablePersistentStateComponent<ProjectTabGroupVisualStateData>(ProjectTabGroupVisualStateData()) {
    fun suspendGroups(ids: Set<String>): Boolean = ids.isNotEmpty() && change { it + ids }
    fun restore(ids: Set<String>): Boolean = ids.isNotEmpty() && change { it - ids }
    fun retain(ids: Set<String>): Boolean = change { it.intersect(ids) }

    private fun change(transform: (Set<String>) -> Set<String>): Boolean {
        var changed = false
        updateState { current ->
            val next = ProjectTabGroupVisualStateData(transform(current.suspendedGroupIds.toSet()).toList()).normalized()
            changed = next != current
            if (changed) next else current
        }
        return changed
    }

    override fun loadState(state: ProjectTabGroupVisualStateData) = super.loadState(state.normalized())
}

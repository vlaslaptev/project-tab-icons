package dev.vlas.projecttabicons

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

internal data class ProjectWindowOpeningState(
    var automaticallyOpenInTabs: Boolean = false,
    var automaticallyOpenInTabsOnMac: Boolean = false,
)

/** Window behavior is independent of appearance presets and style import/export. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsWindowOpening", storages = [Storage("projectTabIconsWindowOpening.xml")])
internal class ProjectWindowOpeningSettings : PersistentStateComponent<ProjectWindowOpeningState> {
    private var value = ProjectWindowOpeningState()
    @Volatile var revision: Long = 0
        private set

    override fun getState(): ProjectWindowOpeningState = SharedIconSettingsLock.withLock { value.copy() }

    override fun loadState(state: ProjectWindowOpeningState) = SharedIconSettingsLock.withLock {
        if (value != state) {
            value = state.copy()
            revision++
        }
    }

    fun apply(state: ProjectWindowOpeningState) = loadState(state)
}

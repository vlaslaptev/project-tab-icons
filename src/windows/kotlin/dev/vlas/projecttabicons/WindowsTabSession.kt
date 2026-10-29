package dev.vlas.projecttabicons

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Rectangle

internal data class WindowsTabSessionState(
    var projects: MutableList<String> = mutableListOf(),
    var selected: String = "",
    var windowX: Int = 0,
    var windowY: Int = 0,
    var windowWidth: Int = 0,
    var windowHeight: Int = 0,
    var maximized: Boolean = false,
)

/** Keep unopened projects in the snapshot while already reopened windows are usable. */
internal class WindowsSessionRestore(saved: WindowsTabSessionState) {
    var state = saved.copy(projects = saved.projects.distinct().toMutableList())
        private set
    private val joined = mutableSetOf<String>()
    val complete: Boolean get() = joined.containsAll(state.projects)

    fun joined(paths: Collection<String>) { joined.addAll(paths) }

    fun remember(paths: List<String>, selected: String, bounds: Rectangle, maximized: Boolean, selectionChanged: Boolean) {
        val present = paths.toSet()
        // A previously joined project that disappears was explicitly closed or detached.
        // Projects that have not opened yet must retain their slots in the saved order.
        val retained = state.projects.filter { it !in joined || it in present }
        val ordered = paths.filter { it in retained }.iterator()
        val merged = retained.map { if (it in present) ordered.next() else it } + paths.filter { it !in retained }
        val selection = if (!selectionChanged && state.selected in merged && state.selected !in present)
            state.selected else selected
        state = WindowsTabSessionState(merged.toMutableList(), selection, bounds.x, bounds.y, bounds.width, bounds.height, maximized)
        joined.retainAll(present)
    }
}

@Service(Service.Level.APP)
@State(name = "ProjectTabIconsWindowsSession", storages = [Storage("projectTabIconsWindows.xml")])
internal class WindowsTabSession : PersistentStateComponent<WindowsTabSessionState> {
    private var value = WindowsTabSessionState()
    override fun getState() = value
    override fun loadState(state: WindowsTabSessionState) { value = state }
    fun save(paths: List<String>, selected: String, bounds: Rectangle? = null, maximized: Boolean = false) {
        value = WindowsTabSessionState(paths.toMutableList(), selected,
            bounds?.x ?: 0, bounds?.y ?: 0, bounds?.width ?: 0, bounds?.height ?: 0, maximized)
    }
}

internal class WindowsProjectTabsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        service<ProjectTabIconsService>().requestRefresh()
        withContext(Dispatchers.EDT) { service<WindowsWindowMergeService>().projectOpened(project) }
    }
}

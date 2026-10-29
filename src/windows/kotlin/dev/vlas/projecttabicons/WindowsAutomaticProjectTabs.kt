package dev.vlas.projecttabicons

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.IdeFrameImpl
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Startup activities may run after appStarted, so remember the actual startup projects. */
@Service(Service.Level.APP)
internal class WindowsProjectOpeningSession {
    private var starting = false
    private val startupProjects = mutableSetOf<Project>()

    @Synchronized fun starting() { starting = true }
    @Synchronized fun started() {
        startupProjects.addAll(ProjectManager.getInstance().openProjects)
        starting = false
    }
    @Synchronized fun isStartupProject(project: Project): Boolean = startupProjects.remove(project) || starting
    @Synchronized fun projectClosed(project: Project) { startupProjects.remove(project) }
}

class WindowsProjectTabsApplicationListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) { service<WindowsProjectOpeningSession>().starting() }
    override fun appStarted() { service<WindowsProjectOpeningSession>().started() }
}

/** Only opening a project creates a request; refresh, focus and detach never do. EDT only. */
internal class WindowsAutomaticProjectTabs(private val merge: WindowsWindowMergeService) : AutoCloseable {
    private data class Request(
        val target: Project,
        val group: WindowsMergedProjectWindow?,
        val settingsRevision: Long,
    )

    private val settings = service<ProjectWindowOpeningSettings>()
    private val session = service<WindowsProjectOpeningSession>()
    private val recentFrames = mutableListOf<IdeFrameImpl>()
    private val pending = linkedMapOf<Project, Request>()
    private val timer = Timer(250) { joinReadyProjects() }
    private var closed = false
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val focusListener = PropertyChangeListener {
        if (SwingUtilities.isEventDispatchThread()) rememberFocus()
        else SwingUtilities.invokeLater { if (!closed) rememberFocus() }
    }

    init {
        focusManager.addPropertyChangeListener("activeWindow", focusListener)
    }

    private fun rememberFocus() {
        recentFrames.removeAll { !it.isDisplayable }
        val frame = focusManager.activeWindow as? IdeFrameImpl ?: merge.window?.activeFrame ?: return
        recentFrames.remove(frame)
        recentFrames.add(0, frame)
    }

    fun projectOpened(project: Project) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed || project.isDisposed || project.isDefault) return
        val frames = merge.openFrames()
        val group = merge.window
        // Startup keeps the saved membership. Automatic opening must not undo a
        // manual Separate or pull independent windows into a restored group.
        val startupProject = session.isStartupProject(project)
        if (!settings.state.automaticallyOpenInTabs || startupProject || openedByGroupAction(project) ||
            merge.isRestoringProject(project) || frames.any { it.value === project && it.key in group?.frames.orEmpty() }) {
            rememberFocus()
            return
        }
        val candidates = frames.filterValues { it !== project }
        val target = group?.activeFrame?.let(candidates::get)
            ?: recentFrames.firstNotNullOfOrNull { candidates[it] }
            ?: candidates.entries.firstOrNull { it.key.isActive }?.value
            ?: candidates.values.lastOrNull()
        if (target != null) {
            pending[project] = Request(target, group, settings.revision)
            timer.start()
        }
        rememberFocus()
    }

    private fun joinReadyProjects() {
        if (closed || ApplicationManager.getApplication().isExitInProgress || !settings.state.automaticallyOpenInTabs) {
            cancelPending()
            return
        }
        // Joining can synchronously close/dispose windows and cancel the queue.
        for ((project, request) in pending.toMap()) {
            if (pending[project] !== request) continue
            if (project.isDisposed || request.target.isDisposed || request.settingsRevision != settings.revision) {
                pending.remove(project)
            } else if (merge.joinNewProject(project, request.target, request.group)) {
                pending.remove(project)
            }
        }
        if (pending.isEmpty()) timer.stop()
    }

    fun projectClosed(project: Project) {
        session.projectClosed(project)
        pending.entries.removeAll { it.key === project || it.value.target === project }
        recentFrames.removeAll { !it.isDisplayable }
        if (pending.isEmpty()) timer.stop()
    }

    fun cancelPending() {
        pending.clear()
        timer.stop()
    }

    override fun close() {
        closed = true
        cancelPending()
        focusManager.removePropertyChangeListener("activeWindow", focusListener)
        recentFrames.clear()
    }
}

package dev.vlas.projecttabicons

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.mac.MacWinTabsHandlerV2
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Activities can run after appStarted: retain the actual restored project set. */
@Service(Service.Level.APP)
internal class MacProjectOpeningSession {
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

class MacProjectTabsApplicationListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        if (!SystemInfoRt.isMac) return
        service<MacProjectOpeningSession>().starting()
        ApplicationManager.getApplication().invokeLater {
            service<MacAutomaticProjectTabs>()
            service<MacTabbingPreference>().refresh()
        }
    }

    override fun appStarted() {
        if (SystemInfoRt.isMac) service<MacProjectOpeningSession>().started()
    }
}

/** Only a new project requests a join. Refresh, switching tabs and detaching do not. */
@Service(Service.Level.APP)
internal class MacAutomaticProjectTabs : Disposable {
    private data class Request(val revision: Long, val candidates: List<Project>, val deadline: Long)
    private val settings = service<ProjectWindowOpeningSettings>()
    private val session = service<MacProjectOpeningSession>()
    private val recentFrames = mutableListOf<IdeFrameImpl>()
    private val pending = linkedMapOf<Project, Request>()
    private val timer = Timer(250) { joinNext() }
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val focusListener = PropertyChangeListener {
        if (SwingUtilities.isEventDispatchThread()) rememberFocus()
        else SwingUtilities.invokeLater { if (!disposed) rememberFocus() }
    }
    @Volatile private var disposed = false
    private var joining = false

    // Resolve both hooks before touching a native group. The supported SDK is 262.
    private val synchronizeBars by lazy {
        MacWinTabsHandlerV2::class.java.getDeclaredMethod(
            "updateTabBarsAfterMove", IdeFrameImpl::class.java, IdeFrameImpl::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }
    private val removeBar by lazy {
        MacWinTabsHandlerV2::class.java.getDeclaredMethod(
            "removeFromFrame", IdeFrameImpl::class.java, IdeFrameImpl::class.java,
        ).apply { isAccessible = true }
    }

    init {
        focusManager.addPropertyChangeListener("activeWindow", focusListener)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosed(project: Project) {
                session.projectClosed(project)
                pending.remove(project)
                recentFrames.removeAll { !it.isDisplayable }
                if (pending.isEmpty()) timer.stop()
            }
        })
        rememberFocus()
    }

    private fun rememberFocus() {
        recentFrames.removeAll { !it.isDisplayable }
        val frame = focusManager.activeWindow as? IdeFrameImpl ?: return
        recentFrames.remove(frame)
        recentFrames.add(0, frame)
    }

    private fun openFrames(): Map<IdeFrameImpl, Project> = WindowManager.getInstance().allProjectFrames
        .filterIsInstance<ProjectFrameHelper>().mapNotNull { helper ->
            helper.project?.takeUnless { it.isDisposed || it.isDefault }?.let { helper.frame to it }
        }.toMap()

    fun projectOpened(project: Project) {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed || project.isDisposed || project.isDefault) return
        val startup = session.isStartupProject(project)
        if (!startup && !openedByGroupAction(project) && settings.state.automaticallyOpenInTabsOnMac) {
            val frames = openFrames().filterValues { it !== project }
            val candidates = (recentFrames.mapNotNull(frames::get) + frames.values).distinct()
            if (candidates.isNotEmpty()) {
                pending[project] = Request(settings.revision, candidates, System.nanoTime() + 10_000_000_000L)
                timer.start()
            }
        }
        rememberFocus()
    }

    private fun valid(project: Project, request: Request): Boolean = !disposed && !project.isDisposed &&
        !ApplicationManager.getApplication().isExitInProgress && request.revision == settings.revision &&
        settings.state.automaticallyOpenInTabsOnMac && System.nanoTime() < request.deadline

    private fun joinNext() {
        if (joining || ModalityState.current() != ModalityState.nonModal()) return
        pending.entries.removeAll { !valid(it.key, it.value) }
        val (project, request) = pending.entries.firstOrNull() ?: run { timer.stop(); return }
        val frames = openFrames()
        val frame = frames.entries.firstOrNull { it.value === project }?.key ?: return
        val candidates = request.candidates.mapNotNull { candidate -> frames.entries.firstOrNull { it.value === candidate }?.key }
            .filter { it.isDisplayable && it.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) != null }
        if (!frame.isDisplayable || frame.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) == null) return
        if (candidates.isEmpty()) { pending.remove(project); return }
        val synchronize = try { synchronizeBars.also { removeBar } } catch (error: Exception) {
            pending.clear(); timer.stop()
            LOG.warn("Automatic project tabs are unavailable in this IDE build", error)
            return
        }
        joining = true
        Foundation.executeOnMainThread(true, false) {
            var completed = false
            var target: IdeFrameImpl? = null
            var members = emptyList<IdeFrameImpl>()
            try {
                if (valid(project, request)) {
                    val mode = MacTabbingPreference.readMode()
                    // Native automatic opening already handles Always. Never fight its grouping.
                    if (mode == MacTabbingMode.ALWAYS || mode == MacTabbingMode.UNKNOWN) completed = true
                    else {
                        val windows = frames.keys.associateWith { MacUtil.getWindowFromJavaWindow(it) }
                        val window = windows.getValue(frame)
                        if (window != ID.NIL) {
                            if (nativeTabs(window).size > 1) completed = true
                            else {
                                val identifier = Foundation.invoke(window, "tabbingIdentifier")
                                val eligible = candidates.filter { candidate ->
                                    val other = windows.getValue(candidate)
                                    other != ID.NIL && identifier != ID.NIL &&
                                        Foundation.invoke(identifier, "isEqualToString:", Foundation.invoke(other, "tabbingIdentifier")).toInt() != 0
                                }
                                val chosen = eligible.firstOrNull { nativeTabs(windows.getValue(it)).size > 1 } ?: eligible.firstOrNull()
                                if (chosen != null) {
                                    val nativeTarget = windows.getValue(chosen)
                                    val before = nativeTabs(nativeTarget).ifEmpty { listOf(nativeTarget) }
                                    // All members must belong to live project frames, not an unknown native window.
                                    if (before.all { id -> windows.values.any { it.toLong() == id.toLong() } } && valid(project, request)) {
                                        Foundation.invoke(nativeTarget, "addTabbedWindow:ordered:", window, 1L)
                                        val after = nativeTabs(nativeTarget)
                                        if (after.any { it.toLong() == window.toLong() }) {
                                            Foundation.invoke(Foundation.invoke(nativeTarget, "tabGroup"), "setSelectedWindow:", window)
                                            members = after.mapNotNull { id -> windows.entries.firstOrNull { it.value.toLong() == id.toLong() }?.key }
                                            target = chosen
                                            completed = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                completed = true
                LOG.warn("Cannot automatically join a project window to macOS tabs", error)
            } finally {
                ApplicationManager.getApplication().invokeLater({
                    try {
                        val destination = target
                        if (!disposed && destination != null && members.all { it.isDisplayable && openFrames().containsKey(it) }) {
                            // SDK 262's index=-1 path creates bars without removing old copies.
                            // Rebuild only the affected native group; leave other groups untouched.
                            members.forEach { removeBar.invoke(null, it, null) }
                            synchronize.invoke(null, frame, destination, -1)
                            service<ProjectTabIconsService>().requestRefresh()
                            LOG.info("Automatically joined a new macOS project window to tabs")
                        }
                    } catch (error: Exception) {
                        LOG.warn("Cannot synchronize automatically joined macOS project tabs", error)
                    } finally {
                        if (completed && pending[project] === request) pending.remove(project)
                        joining = false
                        if (pending.isEmpty()) timer.stop()
                    }
                }, ModalityState.any())
            }
        }
    }

    private fun nativeTabs(window: ID): List<ID> {
        val tabs = Foundation.invoke(window, "tabbedWindows")
        return (0 until Foundation.invoke(tabs, "count").toInt()).map { Foundation.invoke(tabs, "objectAtIndex:", it) }
    }

    override fun dispose() {
        disposed = true
        SwingUtilities.invokeLater {
            timer.stop(); pending.clear(); recentFrames.clear()
            focusManager.removePropertyChangeListener("activeWindow", focusListener)
        }
    }

    companion object { private val LOG = Logger.getInstance(MacAutomaticProjectTabs::class.java) }
}

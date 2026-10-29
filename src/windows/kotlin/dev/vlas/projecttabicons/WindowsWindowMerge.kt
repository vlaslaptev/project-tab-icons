package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.WindowManagerEx
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.beans.PropertyChangeListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.MenuSelectionManager
import java.nio.file.Path

/** Win32 backend. Swing-деревья, меню, action IDs и ProjectFrameHelper проектов остаются у IDEA. */
@Service(Service.Level.APP)
class WindowsWindowMergeService : Disposable {
    internal var window: WindowsMergedProjectWindow? = null
        private set
    private var restoreTimer: Timer? = null
    private var restoreInitialized = false
    private var sessionRestore: WindowsSessionRestore? = null
    private var restoringSession = false
    private var disposing = false
    private val automaticTabs = WindowsAutomaticProjectTabs(this)

    init {
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(AppLifecycleListener.TOPIC, object : AppLifecycleListener {
            override fun beforeAppWillBeClosed(isRestart: Boolean) {
                // IDEA has already accepted all exit vetoes, and has not saved window bounds yet.
                onEdt { restoreTimer?.stop(); separate(preserveSession = true) }
            }
        })
        bus.subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosingBeforeSave(project: Project) {
                onEdt { window?.projectClosingBeforeSave(project) }
            }
            override fun projectClosing(project: Project) {
                onEdt { window?.projectClosing(project) }
            }
            override fun projectClosed(project: Project) {
                onEdt { automaticTabs.projectClosed(project); window?.projectClosed(project) }
            }
        })
    }

    internal fun openFrames(): Map<IdeFrameImpl, Project> = WindowManager.getInstance().allProjectFrames
        .filterIsInstance<ProjectFrameHelper>().mapNotNull { helper ->
            helper.project?.takeUnless { it.isDisposed || it.isDefault }?.let { helper.frame to it }
        }.filter { it.first.isDisplayable }.toMap()

    internal fun merge(project: Project?) {
        check(SwingUtilities.isEventDispatchThread())
        if (!SystemInfoRt.isWindows) return
        automaticTabs.cancelPending()
        finishSessionRestore()
        mergeFrames(openFrames(), project)
    }

    internal fun projectOpened(project: Project) {
        restoreSessionWhenReady()
        automaticTabs.projectOpened(project)
    }

    internal fun mergeGroupedProjects(anchor: Project, projects: List<Project>): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (disposing || anchor.isDisposed) return false
        val frames = openFrames()
        val requested = projects.toSet() + anchor
        if (!frames.values.containsAll(requested)) return false
        val members = frames.filter { (frame, project) -> project in requested || frame in window?.frames.orEmpty() }
        automaticTabs.cancelPending()
        finishSessionRestore()
        return members.size < 2 || mergeFrames(members, anchor)
    }

    internal fun isRestoringProject(project: Project): Boolean =
        windowsProjectPathKey(project) in sessionRestore?.state?.projects.orEmpty()

    /** True consumes the request; false waits for the frames or a modal/full-screen operation. */
    internal fun joinNewProject(project: Project, target: Project, expectedGroup: WindowsMergedProjectWindow?): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (disposing || project.isDisposed || target.isDisposed) return true
        val frames = openFrames()
        val added = frames.entries.firstOrNull { it.value === project }?.key ?: return false
        val anchor = frames.entries.firstOrNull { it.value === target }?.key ?: return false
        val group = window
        if (expectedGroup != null && group !== expectedGroup) return true
        if (group != null && (anchor !in group.frames || added in group.frames)) return true
        val members = if (group == null) mapOf(anchor to target) else frames.filterKeys { it in group.frames }
        val requested = members + (added to project)
        if (group?.hasPendingClose == true || visibleModalWindow() != null || requested.keys.any { it.isInFullScreen }) return false
        // Keep pending session members and leave unrelated standalone windows alone.
        mergeFrames(requested, project)
        return true
    }

    private fun mergeFrames(frames: Map<IdeFrameImpl, Project>, project: Project?, savedWindow: WindowsTabSessionState? = null): Boolean {
        if (frames.size < 2 || window?.hasPendingClose == true) return false
        visibleModalWindow()?.let { it.toFront(); return false }
        if (frames.keys.any { it.isInFullScreen }) {
            Messages.showInfoMessage(project, "Leave full-screen or presentation mode before merging project windows.", "Merge All Project Windows")
            return false
        }
        val selected = frames.entries.firstOrNull { it.value === project }?.key
            ?: window?.activeFrame ?: frames.keys.firstOrNull { it.isActive } ?: frames.keys.first()
        try {
            val group = window ?: WindowsMergedProjectWindow(selected, ::changed, ::separateWithNotification, savedWindow)
                .also { window = it }
            group.merge(frames, selected, savedWindow?.projects)
            changed()
            return true
        } catch (error: Exception) {
            LOG.warn("Windows native merge failed; restoring project windows", error)
            try { separate(preserveSession = savedWindow != null) } catch (restore: Exception) { error.addSuppressed(restore) }
            finishSessionRestore()
            Messages.showErrorDialog(project, "Could not merge project windows. ${error.message}", "Merge All Project Windows")
            return false
        }
    }

    internal fun separate(preserveSession: Boolean = false) {
        check(SwingUtilities.isEventDispatchThread())
        automaticTabs.cancelPending()
        if (!preserveSession) finishSessionRestore()
        val group = window ?: return
        if (preserveSession) saveSession()
        group.separateAll()
        window = null
        if (!preserveSession) service<WindowsTabSession>().save(emptyList(), "")
        refreshDecorations()
    }

    internal fun separateWithNotification() {
        try { separate() }
        catch (error: Exception) {
            LOG.warn("Windows merge: could not restore all project windows", error)
            Messages.showErrorDialog(error.message.orEmpty(), "Separate All Project Windows")
        }
    }

    private fun changed() {
        if (!disposing && !ApplicationManager.getApplication().isExitInProgress) saveSession(selectionChanged = true)
        refreshDecorations()
    }

    private fun saveSession(selectionChanged: Boolean = false) {
        if (restoringSession) return
        val group = window ?: return
        if (group.hasPendingClose) return
        val frames = openFrames()
        val paths = group.tabs.tabs.mapNotNull { frames[it.`object`]?.let(::windowsProjectPathKey) }
        val selected = frames[group.activeFrame]?.let(::windowsProjectPathKey).orEmpty()
        val pending = sessionRestore
        if (pending != null) {
            pending.remember(paths, selected, group.normalBounds, group.isMaximized, selectionChanged)
            service<WindowsTabSession>().loadState(pending.state.copy(projects = pending.state.projects.toMutableList()))
            if (pending.complete) finishSessionRestore()
        } else {
            service<WindowsTabSession>().save(paths, selected, group.normalBounds, group.isMaximized)
        }
    }

    internal fun orderChanged() = saveSession()

    private fun refreshDecorations() {
        ApplicationManager.getApplication().getServiceIfCreated(ProjectTabIconsService::class.java)?.requestRefresh()
    }

    internal fun restoreSessionWhenReady() {
        if (disposing || ApplicationManager.getApplication().isExitInProgress) return
        if (!restoreInitialized) {
            restoreInitialized = true
            val saved = service<WindowsTabSession>().state
            if (saved.projects.distinct().size >= 2) sessionRestore = WindowsSessionRestore(saved)
        }
        if (sessionRestore == null || restoreTimer?.isRunning == true) return
        // Startup activities can precede native frame creation. Keep waiting for late
        // windows; modal dialogs and slow loading must not expire the saved session.
        restoreTimer = Timer(600) { restoreAvailableWindows() }.apply { start() }
    }

    private fun restoreAvailableWindows() {
        val pending = sessionRestore ?: return
        if (disposing || ApplicationManager.getApplication().isExitInProgress || visibleModalWindow() != null ||
            window?.hasPendingClose == true) return
        val saved = pending.state
        val frames = openFrames().filterValues { windowsProjectPathKey(it) in saved.projects }
        if (frames.size < 2 || frames.keys.any { it.isInFullScreen } ||
            frames.keys.all { it in window?.frames.orEmpty() }) return
        val selected = frames.values.firstOrNull { windowsProjectPathKey(it) == saved.selected }
        restoringSession = true
        val merged = try { mergeFrames(frames, selected, saved) } finally { restoringSession = false }
        if (!merged) return
        pending.joined(frames.values.mapNotNull(::windowsProjectPathKey))
        saveSession()
    }

    private fun finishSessionRestore() {
        restoreInitialized = true
        restoreTimer?.stop()
        sessionRestore = null
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    override fun dispose() {
        disposing = true
        onEdt { automaticTabs.close(); restoreTimer?.stop(); separate(preserveSession = true) }
    }

    companion object { private val LOG = Logger.getInstance(WindowsWindowMergeService::class.java) }
}

internal fun windowsMergedWindow(): WindowsMergedProjectWindow? = if (SystemInfoRt.isWindows)
    ApplicationManager.getApplication().getServiceIfCreated(WindowsWindowMergeService::class.java)?.window else null

class MergeAllProjectWindowsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = SystemInfoRt.isWindows
        e.presentation.isEnabled = SystemInfoRt.isWindows && service<WindowsWindowMergeService>().let { merge ->
            val frames = merge.openFrames().keys
            frames.size > 1 && (merge.window == null || frames.any { it !in merge.window!!.frames })
        }
    }
    override fun actionPerformed(e: AnActionEvent) {
        if (SystemInfoRt.isWindows) service<WindowsWindowMergeService>().merge(e.project)
    }
}

class SeparateAllProjectWindowsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = SystemInfoRt.isWindows
        e.presentation.isEnabled = windowsMergedWindow() != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        if (!SystemInfoRt.isWindows) return
        service<WindowsWindowMergeService>().separateWithNotification()
    }
}

internal class WindowsMergedProjectWindow(
    selected: IdeFrameImpl,
    private val changed: () -> Unit,
    private val separate: () -> Unit,
    savedWindow: WindowsTabSessionState? = null,
) {
    private val lifetime = Disposer.newDisposable("Merged Windows projects")
    val tabs = WindowsProjectTabs(null, lifetime)
    private val canvas = Canvas()
    private val container = JFrame()
    private var layoutConfiguration: GraphicsConfiguration? = null
    private var layoutTransform: AffineTransform? = null
    private val caption: WindowsMergedCaption
    var normalBounds: Rectangle = fitProjectWindowBounds(
        savedWindow?.takeIf { it.windowWidth > 0 && it.windowHeight > 0 }?.let {
            Rectangle(it.windowX, it.windowY, it.windowWidth, it.windowHeight)
        } ?: projectWindowNormalBounds(selected))
        private set
    val isMaximized: Boolean get() = container.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
    private val members = linkedMapOf<IdeFrameImpl, EmbeddedProjectWindow>()
    private val pendingClose = mutableMapOf<IdeFrameImpl, Boolean>()
    val hasPendingClose: Boolean get() = pendingClose.isNotEmpty()
    private var needsOrderAdoption = false
    val frames: Set<IdeFrameImpl> get() = members.keys.toSet()
    var activeFrame: IdeFrameImpl = selected
        private set
    private var changing = false
    private var closed = false
    // Keep arrow navigation on the label through delayed shell-activation events.
    private var focusTabLabel = false
    private val timer = Timer(250) { reconcile() }
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val activationListener = PropertyChangeListener { event ->
        val frame = event.newValue as? IdeFrameImpl
        if (!changing && !closed && frame != null && frame in members) {
            focusTabLabel = false
            if (frame !== activeFrame) activate(frame)
        }
    }

    init {
        container.name = "Project Tab Icons merged project window"
        container.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        container.glassPane = IdeGlassPaneImpl(container.rootPane)
        caption = WindowsMergedCaption(container, tabs)
        container.contentPane = object : JPanel(BorderLayout()), UiDataProvider {
            override fun uiDataSnapshot(sink: DataSink) { activeFrame.uiDataSnapshot(sink) }
        }.apply {
            add(caption, BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)
        }
        container.bounds = normalBounds
        container.extendedState = when {
            savedWindow == null -> selected.extendedState and Frame.MAXIMIZED_BOTH
            savedWindow.maximized -> Frame.MAXIMIZED_BOTH
            else -> Frame.NORMAL
        }
        container.minimumSize = java.awt.Dimension(640, 400)
        container.iconImages = selected.iconImages
        container.addComponentListener(object : ComponentAdapter() {
            private fun rememberBounds() {
                if (container.isVisible && container.extendedState == Frame.NORMAL) normalBounds = Rectangle(container.bounds)
            }
            override fun componentMoved(e: ComponentEvent) = rememberBounds()
            override fun componentResized(e: ComponentEvent) = rememberBounds()
        })
        tabs.ownFrame = selected
        tabs.selectFrame = { target, requestFocus -> activate(target, requestFocus) }
        tabs.reordered = { service<WindowsWindowMergeService>().orderChanged() }
        tabs.detachFrame = { frame, point -> detach(frame, point) }
        tabs.contextMenu = { target ->
            DefaultActionGroup().apply {
                add(DumbAwareAction.create("Close Project") { closeProjectWindow(target) })
                add(DumbAwareAction.create("Move Project to New Window") { detach(target) })
                addSeparator()
                add(DumbAwareAction.create("Arrange Tabs by Group") { service<ProjectTabIconsService>().windowsTabs?.arrange() })
                add(OpenGroupedProjectsAction())
                ActionManager.getInstance().getAction("ProjectTabIcons.MergeAllWindows")?.let { add(it) }
                add(DumbAwareAction.create("Separate All Project Windows") { separate() })
                addSeparator()
                ActionManager.getInstance().getAction("ProjectTabIcons.Actions")?.let { add(it) }
            }
        }
        focusManager.addPropertyChangeListener("activeWindow", activationListener)
        ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(AnActionListener.TOPIC,
            object : AnActionListener {
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    // IDEA's full-screen/presentation decorators own an independent native frame.
                    // Restore it before the original action runs, retaining its normal behavior.
                    val target = when (ActionManager.getInstance().getId(action)) {
                        "ToggleFullScreen" -> (WindowManagerEx.getInstanceEx().findFrameHelper(event.project) as? ProjectFrameHelper)?.frame
                        "TogglePresentationMode" -> if (event.project != null) IdeFrameImpl.activeFrame else null
                        else -> null
                    }
                    if (!closed && (target === container || target in members)) {
                        separate()
                    }
                }
                override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
                    if (!closed && !changing) followIdeWindowAction(action, event)
                }
            })
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (closed || changing || hasPendingClose) return
                try { layoutChildren() }
                catch (error: Exception) { restoreAfterRefreshFailure(error) }
            }
        })
        container.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                // Standard IDEA close flow retains project vetoes and running-task dialogs.
                if (visibleModalWindow() == null) closeProjectWindow(activeFrame)
            }
            override fun windowActivated(e: WindowEvent) {
                SwingUtilities.invokeLater {
                    // Activating the shell on tab press must not redirect focus to
                    // the embedded HWND before the label receives release/click.
                    if (!changing && !closed && !caption.isTabGestureInProgress) restoreFocus()
                }
            }
        })
    }

    fun merge(projects: Map<IdeFrameImpl, Project>, selected: IdeFrameImpl, savedOrder: List<String>? = null) {
        changing = true
        try {
            container.isVisible = true
            val parent = HWND(Native.getComponentPointer(canvas))
            projects.forEach { (frame, project) ->
                if (frame !in members) {
                    val member = EmbeddedProjectWindow(frame, project, container)
                    // Запись до первого изменения позволяет откатить незавершённое встраивание.
                    members[frame] = member
                    member.embed(parent)
                }
            }
            activeFrame = selected
            synchronizeTabs(savedOrder)
            if (savedOrder != null) needsOrderAdoption = true
            layoutChildren()
        } finally { changing = false }
        activate(selected)
        timer.start()
        LOG.info("Windows merge: ${members.size} project HWNDs embedded in one container; parent=${parentHandle()}")
    }

    private fun parentHandle(): Long = Pointer.nativeValue(Native.getComponentPointer(canvas))

    fun activate(frame: IdeFrameImpl, requestFocus: Boolean = true): Boolean {
        if (closed || hasPendingClose || frame !in members || !frame.isDisplayable) return false
        visibleModalWindow()?.let { it.toFront(); return false }
        changing = true
        try {
            members[activeFrame]?.rememberFocus()
            activeFrame = frame
            focusTabLabel = !requestFocus
            tabs.ownFrame = frame
            synchronizeTabs()
            container.title = frame.title
            container.iconImages = frame.iconImages
            if (container.extendedState and Frame.ICONIFIED != 0) container.extendedState = container.extendedState and Frame.ICONIFIED.inv()
            layoutChildren()
            members.forEach { (candidate, member) -> member.show(candidate === frame) }
            container.toFront()
        } finally { changing = false }
        SwingUtilities.invokeLater { if (!closed && activeFrame === frame) restoreFocus() }
        changed()
        LOG.info("Windows merge: selected ${members[frame]?.project?.name}")
        return true
    }

    private fun restoreFocus() {
        if (hasPendingClose) return
        if (container.extendedState and Frame.ICONIFIED != 0) return
        if (visibleModalWindow() != null) return
        if (tabs.popupInfo != null || MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) return
        if (focusTabLabel) {
            tabs.selectedLabel?.let { IdeFocusManager.findInstanceByComponent(tabs).requestFocus(it, true) }
            return
        }
        val member = members[activeFrame] ?: return
        activeFrame.requestFocus()
        val focus = member.lastFocus?.takeIf { it.isDisplayable && it.isShowing }
            ?: activeFrame.mostRecentFocusOwner
        focus?.requestFocusInWindow()
    }

    /** Follow IDEA's own project-window order, including items contributed to Window. */
    private fun followIdeWindowAction(action: AnAction, event: AnActionEvent) {
        val id = ActionManager.getInstance().getId(action)
        try {
            val targetAction = when {
                id == "NextProjectWindow" || id == "PreviousProjectWindow" -> {
                    val path = event.project?.let(::windowsProjectPath) ?: return
                    val group = ActionManager.getInstance().getAction("OpenProjectWindows") ?: return
                    group.javaClass.getMethod("findSwitchTarget", Path::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(group, path, id == "NextProjectWindow") ?: return
                }
                action.javaClass.name == "com.intellij.openapi.wm.impl.ProjectWindowAction" -> action
                else -> return
            }
            val path = targetAction.javaClass.getMethod("getProjectLocation").invoke(targetAction) as? Path ?: return
            members.entries.firstOrNull { (_, member) -> windowsProjectPath(member.project) == path }?.key?.let { frame ->
                SwingUtilities.invokeLater { if (!closed) activate(frame) }
            }
        } catch (error: ReflectiveOperationException) {
            LOG.warn("Could not follow the IDE project-window action", error)
        }
    }

    fun adoptRestoredOrder(): List<IdeFrameImpl>? {
        if (!needsOrderAdoption) return null
        needsOrderAdoption = false
        return tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl }
    }

    private fun synchronizeTabs(savedOrder: List<String>? = null) {
        if (tabs.isReordering) return
        val existing = tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl }.filter { it in members }
        val restored = savedOrder?.mapNotNull { path ->
            members.entries.firstOrNull { (_, member) -> windowsProjectPathKey(member.project) == path }?.key
        }.orEmpty()
        val order = restored + existing.filter { it !in restored } + members.keys.filter { it !in existing && it !in restored }
        tabs.synchronize(order, members.mapValues { it.value.project })
    }

    private fun layoutChildren() {
        if (closed || hasPendingClose || !canvas.isDisplayable || container.extendedState and Frame.ICONIFIED != 0) return
        // Reconcile the outer Swing hierarchy too: display/RDP changes can leave
        // the canvas laid out for old insets or scale even while the HWND is current.
        val configuration = container.graphicsConfiguration
        val transform = configuration?.defaultTransform
        val insets = container.insets
        val root = container.rootPane
        val expectedRoot = Rectangle(insets.left, insets.top,
            maxOf(0, container.width - insets.left - insets.right),
            maxOf(0, container.height - insets.top - insets.bottom))
        if (layoutConfiguration !== configuration || layoutTransform != transform ||
            !container.isValid || !root.isValid || root.bounds != expectedRoot) {
            container.invalidate()
            container.validate()
            container.repaint()
            layoutConfiguration = configuration
            layoutTransform = transform
        }
        val parent = HWND(Native.getComponentPointer(canvas))
        val rect = RECT()
        if (!User32.INSTANCE.GetClientRect(parent, rect)) return
        val bounds = rect.toRectangle()
        if (bounds.isEmpty) return
        // Canvas has no non-client border: its screen origin is its client origin.
        if (!User32.INSTANCE.GetWindowRect(parent, rect)) return
        bounds.setLocation(rect.left, rect.top)
        members.values.forEach { it.resize(bounds) }
    }

    private fun reconcile() {
        if (closed || changing || hasPendingClose) return
        try { reconcileWindows() }
        catch (error: Exception) { restoreAfterRefreshFailure(error) }
    }

    private fun restoreAfterRefreshFailure(error: Exception) {
        timer.stop()
        LOG.warn("Windows merge refresh failed; restoring independent windows", error)
        separate()
    }

    private fun reconcileWindows() {
        val removed = members.filter { !it.key.isDisplayable || it.value.project.isDisposed }.keys
        removed.forEach { members.remove(it)?.restore() }
        if (members.size < 2) {
            members.values.singleOrNull()?.restore(normalBounds, container.extendedState)
            separate()
            return
        }
        if (activeFrame !in members) activate(members.keys.first())
        // Заголовки и базовая навигация живут и при выключенном оформлении плагина.
        synchronizeTabs()
        if (removed.isNotEmpty()) changed()
        if (container.title != activeFrame.title) container.title = activeFrame.title
        members.values.forEach { it.refreshHeader() }
        layoutChildren()
    }

    private fun detach(frame: IdeFrameImpl, point: Point? = null) {
        if (hasPendingClose || visibleModalWindow() != null) return
        val member = members[frame] ?: return
        tabs.cancelInteraction()
        val target = Rectangle(normalBounds).apply {
            if (point != null) { x = point.x - width / 2; y = point.y - 30 }
            else { translate(40, 40) }
        }
        try { member.restore(target, Frame.NORMAL) }
        catch (error: Exception) {
            LOG.warn("Could not detach project window", error)
            Messages.showErrorDialog(member.project, error.message.orEmpty(), "Move Project to New Window")
            return
        }
        members.remove(frame)
        LOG.info("Windows merge: detached ${member.project.name}")
        if (members.size < 2) reconcileWindows()
        else {
            if (activeFrame !in members) activate(members.keys.first()) else synchronizeTabs()
            changed()
        }
        frame.toFront()
        frame.requestFocus()
    }

    fun projectClosingBeforeSave(project: Project) {
        val entry = members.entries.firstOrNull { it.value.project === project } ?: return
        if (entry.key in pendingClose) return
        tabs.cancelInteraction()
        pendingClose[entry.key] = false
        // IDEA saves bounds before its final unable-to-save veto. Keep membership
        // and order until projectClosed, but let it save the independent frame.
        try { entry.value.restore() }
        catch (error: Exception) {
            pendingClose.remove(entry.key)
            LOG.warn("Could not prepare merged project for saving on close", error)
            separate()
            return
        }
        ApplicationManager.getApplication().invokeLater({
            if (closed || pendingClose[entry.key] != false) return@invokeLater
            // This callback cannot run inside save progress or the save-failure dialog.
            // A successful close has already delivered projectClosing/projectClosed.
            if (project.isDisposed || !project.isOpen || !entry.key.isDisplayable) {
                projectClosed(project)
                return@invokeLater
            }
            try {
                changing = true
                entry.value.embed(HWND(Native.getComponentPointer(canvas)))
                pendingClose.remove(entry.key)
            } catch (error: Exception) {
                LOG.warn("Could not restore merged project after cancelled close", error)
                pendingClose.remove(entry.key)
                separate()
            } finally { changing = false }
            if (!closed && !hasPendingClose) {
                if (activeFrame in members) activate(activeFrame) else reconcile()
            }
        }, ModalityState.nonModal())
    }

    fun projectClosing(project: Project) {
        members.entries.firstOrNull { it.value.project === project }?.key?.let { frame ->
            if (frame in pendingClose) pendingClose[frame] = true
        }
    }

    fun projectClosed(project: Project) {
        val entry = members.entries.firstOrNull { it.value.project === project } ?: return
        pendingClose.remove(entry.key)
        members.remove(entry.key)?.restore()
        SwingUtilities.invokeLater { if (!closed) { reconcile(); changed() } }
    }

    fun separateAll() {
        if (closed) return
        changing = true
        timer.stop()
        try {
            var failure: Exception? = null
            members.toMap().forEach { (frame, member) ->
                try { member.restore(); members.remove(frame) }
                catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
            }
            // При неудаче оставляем оболочку живой: её destroy уничтожил бы дочерние HWND.
            failure?.let { throw it }
            pendingClose.clear()
            closed = true
            tabs.selectFrame = null
            tabs.reordered = null
            tabs.dragFinished = null
            tabs.detachFrame = null
            tabs.contextMenu = null
            focusManager.removePropertyChangeListener("activeWindow", activationListener)
            tabs.clear()
            container.dispose()
            Disposer.dispose(lifetime)
            LOG.info("Windows merge: all project windows restored")
        } finally { changing = false }
    }

    companion object { private val LOG = Logger.getInstance(WindowsMergedProjectWindow::class.java) }
}

private fun visibleModalWindow(): Dialog? = Window.getWindows().filterIsInstance<Dialog>().firstOrNull {
    it.isShowing && it.isModal
}

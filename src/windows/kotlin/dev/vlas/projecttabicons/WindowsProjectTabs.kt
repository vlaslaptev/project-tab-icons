package dev.vlas.projecttabicons

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.ui.InplaceButton
import com.intellij.ui.ClientProperty
import com.intellij.ui.IslandsState
import com.intellij.ui.JBColor
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.DefaultTabPainterAdapter
import com.intellij.ui.tabs.impl.DragHelper
import com.intellij.ui.tabs.impl.JBDefaultTabPainter
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.MorePopupAware
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.tabs.impl.TabLayout
import com.intellij.ui.tabs.impl.themes.DefaultTabTheme
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal fun closeProjectWindow(frame: IdeFrameImpl) {
    if (frame.isDisplayable) {
        // WindowCloseListener устанавливает собственный coroutine context. Событие
        // должно прийти из AWT-очереди, вне контекста вызвавшего его AnAction.
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
    }
}

/** Общая полоса Windows; merged-контейнер управляет выбором через selectFrame. */
internal open class WindowsProjectTabs(project: Project?, lifetime: Disposable) : JBTabsImpl(project, lifetime), ManagedProjectTabs {
    // Оконные табы используют фон MainWindow, как штатные WindowTabsComponent и Preview.
    // CommonTabPainter в Islands берёт более тёмный фон вкладок редактора.
    override fun createTabPainterAdapter() = DefaultTabPainterAdapter(JBDefaultTabPainter(object : DefaultTabTheme() {
        override val background get() = if (IslandsState.isEnabled())
            JBColor.namedColor("MainWindow.background", JBColor.PanelBackground) else super.background
        override val inactiveColoredTabBackground get() = null
    }))

    var ownFrame: IdeFrameImpl? = null
    var selectFrame: ((IdeFrameImpl, Boolean) -> Boolean)? = null
    var reordered: (() -> Unit)? = null
    var dragFinished: ((Boolean) -> Unit)? = null
    var detachFrame: ((IdeFrameImpl, Point) -> Unit)? = null
    var contextMenu: ((IdeFrameImpl) -> DefaultActionGroup)? = null
    private val titles = IdentityHashMap<IdeFrameImpl, String>()
    private var synchronizing = false
    private var dragOrder: List<TabInfo>? = null
    private var suppressClick = false
    val isReordering: Boolean get() = dragOrder != null

    init {
        getAccessibleContext().accessibleName = "Open project windows"
        setTabDraggingEnabled(true)
    }

    override fun createRowLayout(): TabLayout = WindowsWindowTabsLayout(this)

    override fun createDragHelper(tabs: JBTabsImpl, parentDisposable: Disposable): DragHelper =
        WindowsProjectTabDragHelper(this, parentDisposable)

    override val draggedTabSelectionInfo: TabInfo? get() = dragHelper?.dragSource ?: super.draggedTabSelectionInfo

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        ((popupInfo ?: selectedInfo)?.`object` as? IdeFrameImpl)?.uiDataSnapshot(sink)
    }

    private fun showTabPopup(info: TabInfo, label: TabLabel, x: Int, y: Int) {
        val frame = info.`object` as? IdeFrameImpl ?: return
        val group = contextMenu?.invoke(frame) ?: return
        popupInfo = info
        val menu = ActionManager.getInstance().createActionPopupMenu("ProjectTabIcons.Windows.Context", group).component
        // Embedded HWNDs paint above Swing lightweight popups in the outer frame.
        // Only our project-tab popup needs its own native window; IDE menus stay intact.
        menu.isLightWeightPopupEnabled = false
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent) { popupInfo = null }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { popupInfo = null }
        })
        menu.show(label, x, y)
    }

    override fun createTabLabel(info: TabInfo): TabLabel = object : TabLabel(this, info) {
        init {
            // TabLabel выбирает вкладку на press. Для независимых окон это переносит
            // фокус до начала drag; выбор должен происходить только по завершённому click.
            mouseListeners.forEach(::removeMouseListener)
            val label = this
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!isReordering) beginMouseGesture()
                }
                override fun mouseReleased(event: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(event) && !isReordering) {
                        showTabPopup(info, label, event.x, event.y)
                        event.consume()
                    }
                }
                override fun mouseClicked(event: MouseEvent) {
                    if (suppressClick || event.isConsumed) return
                    val component = SwingUtilities.getDeepestComponentAt(label, event.x, event.y)
                    if (component is InplaceButton || component != null &&
                        SwingUtilities.getAncestorOfClass(InplaceButton::class.java, component) != null) return
                    if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1 && !event.isPopupTrigger &&
                        !event.isControlDown && !event.isAltDown && !event.isMetaDown) {
                        select(info, true)
                        PopupUtil.getPopupContainerFor(label)?.let { popup ->
                            if (ClientProperty.isTrue(popup.content, MorePopupAware::class.java)) popup.cancel()
                        }
                    } else if (event.button == MouseEvent.BUTTON2) {
                        (info.`object` as? IdeFrameImpl)?.let(::closeProjectWindow)
                    }
                }
                override fun mouseEntered(event: MouseEvent) { isHovered = true }
                override fun mouseExited(event: MouseEvent) { isHovered = false }
            })
            // Штатный listener после select(false) возвращает фокус старой полосе.
            // В Windows выбор означает переход в другое окно; фокус переносит select.
            keyListeners.forEach(::removeKeyListener)
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    if (event.keyCode == KeyEvent.VK_CONTEXT_MENU || event.keyCode == KeyEvent.VK_F10 && event.isShiftDown) {
                        event.consume()
                        showTabPopup(info, label, 0, label.height)
                        return
                    }
                    val index = getIndexOf(info)
                    if (index < 0) return
                    val next = when (event.keyCode) {
                        KeyEvent.VK_LEFT -> findEnabledBackward(index, true)
                        KeyEvent.VK_RIGHT -> findEnabledForward(index, true)
                        else -> return
                    }
                    event.consume()
                    if (next != null) select(next, false)
                }
            })
        }
        override val isTabActionsOnTheRight: Boolean get() = false
        override fun shouldPaintFadeout(): Boolean = false
    }

    override fun select(info: TabInfo, requestFocus: Boolean): ActionCallback {
        val target = info.`object` as? IdeFrameImpl ?: return ActionCallback.REJECTED
        if (synchronizing) return super.select(info, false)
        selectFrame?.let { return if (it(target, requestFocus)) ActionCallback.DONE else ActionCallback.REJECTED }
        return ActionCallback.REJECTED
    }

    fun synchronize(frames: List<IdeFrameImpl>, projects: Map<IdeFrameImpl, Project>) {
        synchronizing = true
        try {
            var changed = false
            val present = frames.toSet()
            tabs.filter { it.`object` !in present }.toList().forEach { removeTab(it); changed = true }
            titles.keys.retainAll(present)
            val byFrame = tabs.associateBy { it.`object` }
            frames.forEach { frame ->
                val info = byFrame[frame] ?: TabInfo(JLabel()).also { tab ->
                    tab.setObject(frame)
                    val close = object : DumbAwareAction("Close Project", "Close this project window", AllIcons.Actions.Close) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                        override fun actionPerformed(e: AnActionEvent) {
                            closeProjectWindow(frame)
                        }
                    }
                    tab.setTabLabelActions(DefaultActionGroup(close), "ProjectTabIcons.Windows.Close")
                    tab.dragDelegate = object : TabInfo.DragDelegate {
                        override fun dragStarted(mouseEvent: MouseEvent) = beginDrag()
                        override fun dragFinishedOrCanceled() = Unit
                    }
                    addTab(tab)
                    changed = true
                }
                val title = frame.title?.takeIf { it.isNotBlank() } ?: projects[frame]?.name.orEmpty()
                if (titles.put(frame, title) != title) {
                    info.setText(title)
                    info.setTooltipText(HtmlChunk.text(title))
                    changed = true
                }
            }
            val indexes = frames.withIndex().associate { it.value to it.index }
            if (tabs.map { it.`object` } != frames) {
                sortTabs(compareBy { indexes[it.`object`] ?: Int.MAX_VALUE })
                changed = true
            }
            tabs.firstOrNull { it.`object` === ownFrame && it !== selectedInfo }?.let {
                super.select(it, false)
                changed = true
            }
            if (changed) revalidateAndRepaint()
        } finally { synchronizing = false }
    }

    fun beginMouseGesture() { suppressClick = false }

    fun beginDrag() {
        dragOrder = getVisibleInfos().toList()
        suppressClick = true
    }

    /** SDK cleanup отмены не трогает UISettings.sortTabsAlphabetically, в отличие от finish. */
    fun finishDrag(cancelled: Boolean, finishPlatform: () -> Unit) {
        val before = dragOrder
        var completed = false
        try {
            if (cancelled && before != null) {
                val indexes = before.withIndex().associate { it.value to it.index }
                sortTabs(compareBy { indexes[it] ?: Int.MAX_VALUE })
            }
        } finally {
            try { finishPlatform(); completed = true }
            finally {
                dragOrder = null
                // Завершить групповое оформление до синхронизации полос. Отложенный
                // SDK callback уже не найдёт активную сессию этого жеста.
                if (before != null) dragFinished?.invoke(completed && !cancelled)
            }
        }
        if (before != null && !cancelled && before != tabs && !synchronizing && isDisplayable) reordered?.invoke()
    }

    fun cancelInteraction() {
        dragHelper?.cancelDragging()
    }

    fun clear() {
        cancelInteraction()
        synchronizing = true
        try {
            removeAllTabs(); titles.clear(); dragOrder = null
            // validate() скрытой полосы может не обновить lastLayoutPass в SDK.
            // Пустой layout освобождает его ссылки на прежние TabInfo и окна.
            doLayout()
        }
        finally { synchronizing = false }
    }

    override fun getPreferredSize(): Dimension {
        val contentHeight = tabs.maxOfOrNull { getTabLabel(it)?.preferredSize?.height ?: 0 } ?: 0
        return Dimension(super.getPreferredSize().width, maxOf(JBUI.scale(DEFAULT_TAB_HEIGHT), contentHeight))
    }
}

/** Перестановка в полосе и отделение в окно после явного отпускания далеко от полосы. */
internal open class WindowsProjectTabDragHelper(private val owner: WindowsProjectTabs, lifetime: Disposable) : DragHelper(owner, lifetime) {
    override fun processMousePressed(event: MouseEvent) {
        owner.beginMouseGesture()
        super.processMousePressed(event)
    }

    override fun isDragOut(event: MouseEvent, dragToScreenPoint: Point, startScreenPoint: Point): Boolean = false

    override fun processDragFinish(event: MouseEvent, willDragOutStart: Boolean) {
        val point = SwingUtilities.convertPoint(event.component, event.point, owner)
        val sameWindow = SwingUtilities.getWindowAncestor(event.component) === SwingUtilities.getWindowAncestor(owner)
        val target = dragSource?.`object` as? IdeFrameImpl
        val safeArea = java.awt.Rectangle(owner.size).apply { grow(JBUI.scale(40), JBUI.scale(40)) }
        val detach = target != null && owner.detachFrame != null && MouseDragHelper.checkModifiers(event) && !safeArea.contains(point)
        owner.finishDrag(detach || !MouseDragHelper.checkModifiers(event) || !sameWindow || !owner.contains(point)) { super.processDragCancel() }
        if (detach) owner.detachFrame?.invoke(target!!, event.locationOnScreen)
    }

    override fun processDragCancel() = owner.finishDrag(cancelled = true) { super.processDragCancel() }
}

internal class WindowsProjectTabsController(
    private val changed: () -> Unit,
    private val orderRestored: (JBTabsImpl, Boolean) -> Unit,
    private val dragFinished: (JBTabsImpl, Boolean) -> Unit,
) : PlatformProjectTabsBackend {
    private val order = OpenProjectTabOrder<IdeFrameImpl>()
    private var frames = emptyMap<IdeFrameImpl, Project>()
    private var groups = ProjectGroupsState()
    private var keys = emptyMap<IdeFrameImpl, String>()
    private var resumeLiveOrder = false
    private var hiddenGrouping: ProjectTabGrouping? = null
    override var isOrdering = false
        private set
    private var closed = false

    override fun update(value: Map<IdeFrameImpl, Project>, state: ProjectGroupsState) {
        if (closed) return
        val merged = windowsMergedWindow()
        val members = value.filterKeys { it in merged?.frames.orEmpty() }
        if (frames != members || groups != state) cancelInteractions()
        frames = members
        groups = state
        keys = frames.mapNotNull { (frame, project) -> windowsProjectPathKey(project)?.let { frame to it } }.toMap()
        val open = frames.keys.sortedBy { keys[it] ?: frames[it]?.name.orEmpty() }
        order.update(open, groups) { keys[it] }
        // Always adopt the live strip before applying a grouping edit. hide() clears
        // the model, so arranging its path-sorted fallback would lose ungrouped order.
        val restored = merged?.adoptRestoredOrder()
        val live = if (resumeLiveOrder)
            merged?.tabs?.tabs?.mapNotNull { it.`object` as? IdeFrameImpl } else null
        (restored ?: live)?.let { previous ->
            val retained = previous.filter { it in frames }
            order.moved(retained + order.tabs.filter { it !in retained })
            if (restored == null && hiddenGrouping != null && hiddenGrouping != groups.tabGrouping()) {
                order.arrange(groups) { keys[it] }
            }
        }
        resumeLiveOrder = false
        hiddenGrouping = null
        synchronize(false)
    }

    private fun synchronize(explicit: Boolean) {
        val merged = windowsMergedWindow()
        if (merged?.tabs?.isReordering == true) return
        isOrdering = true
        try {
            if (merged != null && frames.size > 1) {
                merged.tabs.ownFrame = merged.activeFrame
                merged.tabs.dragFinished = { commit -> dragFinished(merged.tabs, commit) }
                merged.tabs.reordered = {
                    order.moved(merged.tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl })
                    service<WindowsWindowMergeService>().orderChanged()
                    changed()
                }
                merged.tabs.synchronize(order.tabs, frames)
                orderRestored(merged.tabs, explicit)
                service<WindowsWindowMergeService>().orderChanged()
            }
        } finally { isOrdering = false }
    }

    override fun tabsFor(frame: IdeFrameImpl): WindowsProjectTabs? = windowsMergedWindow()?.takeIf { it.activeFrame === frame }?.tabs
    override fun arrange() {
        if (closed) return
        cancelInteractions()
        // Customization may be disabled, in which case hide() released the model.
        update(service<WindowsWindowMergeService>().openFrames(), service<ProjectGroups>().resolvedState)
        order.arrange(groups) { keys[it] }
        synchronize(true)
        service<ProjectTabGroupVisualState>().restore(keys.values.mapNotNull { groups.memberships[it] }.toSet())
        changed()
    }
    override suspend fun joinAndArrangeGroupedProjects(anchor: Project, projects: List<Project>): Boolean = withContext(Dispatchers.EDT) {
        val merge = service<WindowsWindowMergeService>()
        if (!merge.mergeGroupedProjects(anchor, projects)) return@withContext false
        arrange()
        true
    }
    override fun cancelInteractions() {
        windowsMergedWindow()?.tabs?.cancelInteraction()
    }

    override fun hide() {
        // The merged strip remains usable and can be reordered while decoration is off.
        if (order.tabs.isNotEmpty()) hiddenGrouping = groups.tabGrouping()
        resumeLiveOrder = true
        order.clear()
        frames = emptyMap(); keys = emptyMap(); groups = ProjectGroupsState()
    }

    override fun close() {
        closed = true
        order.clear()
        frames = emptyMap(); keys = emptyMap()
        resumeLiveOrder = false
        hiddenGrouping = null
    }
}

internal class WindowsProjectTabsFactory : PlatformProjectTabsFactory {
    override fun create(changed: () -> Unit, orderRestored: (JBTabsImpl, Boolean) -> Unit,
                        dragFinished: (JBTabsImpl, Boolean) -> Unit): PlatformProjectTabsBackend =
        WindowsProjectTabsController(changed, orderRestored, dragFinished)
}

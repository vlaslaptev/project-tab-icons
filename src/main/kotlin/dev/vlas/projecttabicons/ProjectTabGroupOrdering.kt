package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.ui.mac.WindowTabsComponent
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/** Чистый расчёт: один блок групп на месте первого участника, остальные вкладки сохраняют взаимный порядок. */
internal fun <T : Any> orderedProjectGroupTabs(tabs: List<T>, state: ProjectGroupsState, key: (T) -> String?): List<T> {
    val enabled = state.groups.filter { it.groupTabs }
    val grouped = tabs.filter { tab -> enabled.any { it.id == state.memberships[key(tab)] } }.toSet()
    if (grouped.isEmpty()) return tabs
    val block = enabled.flatMap { group ->
        val members = tabs.filter { state.memberships[key(it)] == group.id }
        val slots = MutableList<T?>(members.size) { null }
        val pinned = members.filter { key(it) in group.memberPositions }.sortedBy { group.memberPositions[key(it)] }
        var last = -1
        pinned.forEachIndexed { index, member ->
            // Отсутствующие/закрытые участники не оставляют дыр. Порядок закреплённых остаётся прежним.
            val requested = group.memberPositions.getValue(requireNotNull(key(member))) - 1
            val slot = requested.coerceIn(last + 1, members.size - pinned.size + index)
            slots[slot] = member; last = slot
        }
        val automatic = members.filterNot { it in pinned }.iterator()
        slots.map { it ?: automatic.next() }
    }
    var inserted = false
    return buildList {
        tabs.forEach { tab ->
            if (tab !in grouped) add(tab)
            else if (!inserted) { addAll(block); inserted = true }
        }
    }
}

/** Реагирует на объединение состава окон, а не на перетаскивания и обычные refresh оформления. */
internal class ProjectTabGroupOrdering(private val scope: CoroutineScope) : Disposable {
    private data class Bar(val tabs: WindowTabsComponent, val frames: List<IdeFrameImpl>) {
        val members = frames.toSet()
    }
    private data class Menu(val original: ActionGroup, val installed: ActionGroup, val place: String)
    private val menus = IdentityHashMap<WindowTabsComponent, Menu>()
    private var bars = emptyList<Bar>()
    private var keys = emptyMap<IdeFrameImpl, String>()
    private var previous = emptySet<Set<IdeFrameImpl>>()
    private var observed = false
    private var merged = false
    private val pending = mutableSetOf<Set<IdeFrameImpl>>()
    private val inFlight = mutableSetOf<Set<IdeFrameImpl>>()
    private var job: Job? = null
    @Volatile private var disposed = false
    @Volatile private var generation = 0L
    private val reorder by lazy {
        // SDK 262: публичный sortTabs не обновляет myIndexes, от которого зависит штатный DnD.
        WindowTabsComponent::class.java.getDeclaredMethod("reorderTabs", Map::class.java).apply { isAccessible = true }
    }

    fun windowsMerged() { merged = true }

    fun update(frames: Map<IdeFrameImpl, Project>) {
        if (disposed) return
        val recent = RecentProjectsManagerBase.getInstanceEx()
        keys = frames.mapNotNull { (frame, project) -> recent.getProjectPath(project)?.let { frame to projectIconPathKey(it) } }.toMap()
        bars = frames.keys.mapNotNull { frame ->
            val tabs = findWindowTabs(frame.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) as? java.awt.Component) ?: return@mapNotNull null
            val members = tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl }
            Bar(tabs, members).takeIf { members.size > 1 && members.size == tabs.tabCount && members.all { it in frames } }
        }
        menus.keys.filter { tab -> bars.none { it.tabs === tab } }.toList().forEach(::restoreMenu)
        bars.forEach { installMenu(it.tabs) }
        val current = bars.map { it.members }.toSet()
        if (observed || merged) {
            val requested = current.filter { members -> merged || previous.none { it.containsAll(members) } }
            if (pending.addAll(requested)) schedule()
        }
        previous = current; observed = true; merged = false
    }

    private fun schedule() {
        job?.cancel()
        job = scope.launch {
            // macOS и IDEA перестраивают копии полосы по очереди; ждём завершения одного объединения.
            delay(180)
            withContext(Dispatchers.EDT) {
                if (disposed) return@withContext
                val requested = pending.toSet(); pending.clear()
                bars.distinctBy { it.members }.filter { it.members in requested }.forEach(::restoreOrder)
            }
        }
    }

    private fun installMenu(tabs: WindowTabsComponent) {
        if (tabs in menus) return
        val original = tabs.popupGroup ?: return
        val restore = object : DumbAwareAction("Restore Group Tab Order") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                val state = ApplicationManager.getApplication().getService(ProjectGroups::class.java).state
                event.presentation.isEnabled = !disposed && tabs.tabCount > 1 && tabs.tabs.any { tab ->
                    keys[tab.`object`]?.let(state::groupFor)?.groupTabs == true
                }
                event.presentation.description = "Restore group order and fixed member positions for this window's tabs"
            }
            override fun actionPerformed(event: AnActionEvent) {
                bars.firstOrNull { it.tabs === tabs }?.let(::restoreOrder)
            }
        }
        val wrapper = DefaultActionGroup().apply {
            add(original)
            addSeparator(); add(restore)
        }
        val place = tabs.popupPlace ?: ActionPlaces.POPUP
        menus[tabs] = Menu(original, wrapper, place)
        tabs.setPopupGroup(wrapper, place, false)
    }

    private fun restoreOrder(snapshot: Bar) {
        // Снимок refresh не обязан совпадать с порядком после последнего ручного DnD.
        val live = snapshot.tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl }
        if (live.size != snapshot.tabs.tabCount || live.toSet() != snapshot.members) return
        val bar = Bar(snapshot.tabs, live)
        if (disposed || !inFlight.add(bar.members)) return
        val state = ApplicationManager.getApplication().getService(ProjectGroups::class.java).state
        val desired = orderedProjectGroupTabs(bar.frames, state) { keys[it] }
        if (desired == bar.frames) { inFlight.remove(bar.members); return }
        val synchronizer = try { reorder } catch (error: Exception) {
            inFlight.remove(bar.members)
            LOG.warn("Project window tab ordering is unavailable in this IDE build", error)
            return
        }
        val token = generation
        Foundation.executeOnMainThread(true, false) {
            var applied = false
            try {
                if (!disposed && token == generation) {
                    val windows = bar.frames.associateWith { MacUtil.getWindowFromJavaWindow(it) }
                    if (windows.values.none { it == ID.NIL }) {
                        val first = windows.getValue(bar.frames.first())
                        val nativeGroup = Foundation.invoke(first, "tabGroup")
                        val nativeTabs = Foundation.invoke(first, "tabbedWindows")
                        val count = Foundation.invoke(nativeTabs, "count").toInt()
                        val actual = (0 until count).map { Foundation.invoke(nativeTabs, "objectAtIndex:", it) }.toMutableList()
                        // Окно могло закрыться или перейти в другую группу после снимка на EDT.
                        if (nativeGroup != ID.NIL && actual.map { it.toLong() }.toSet() == windows.values.map { it.toLong() }.toSet()) {
                            val selected = Foundation.invoke(nativeGroup, "selectedWindow")
                            desired.forEachIndexed { index, frame ->
                                val window = windows.getValue(frame)
                                val old = actual.indexOfFirst { it.toLong() == window.toLong() }
                                if (old != index) {
                                    Foundation.invoke(nativeGroup, "removeWindow:", window)
                                    Foundation.invoke(nativeGroup, "insertWindow:atIndex:", window, index)
                                    actual.removeAt(old); actual.add(index, window)
                                }
                            }
                            if (selected != ID.NIL) Foundation.invoke(nativeGroup, "setSelectedWindow:", selected)
                            applied = true
                        }
                    }
                }
            } catch (error: Exception) { LOG.warn("Cannot restore project group tab order", error) }
            finally {
                ApplicationManager.getApplication().invokeLater {
                    inFlight.remove(bar.members)
                    if (applied && !disposed && token == generation) {
                        val indexes = desired.withIndex().associate { it.value to it.index }
                        try {
                            bars.filter { it.members == bar.members }.forEach { current ->
                                val liveMembers = current.tabs.tabs.mapNotNull { it.`object` as? IdeFrameImpl }
                                if (liveMembers.size == current.tabs.tabCount && liveMembers.toSet() == bar.members)
                                    synchronizer.invoke(current.tabs, indexes)
                            }
                        } catch (error: Exception) { LOG.warn("Cannot synchronize project window tab order", error) }
                    }
                }
            }
        }
    }

    private fun restoreMenu(tabs: WindowTabsComponent) {
        val menu = menus.remove(tabs) ?: return
        if (tabs.popupGroup === menu.installed) tabs.setPopupGroup(menu.original, menu.place, false)
    }

    fun reset() {
        generation++
        job?.cancel(); job = null; pending.clear()
        menus.keys.toList().forEach(::restoreMenu)
        bars = emptyList(); keys = emptyMap(); previous = emptySet(); observed = false; merged = false
    }
    override fun dispose() { disposed = true; reset() }
    companion object { private val LOG = Logger.getInstance(ProjectTabGroupOrdering::class.java) }
}

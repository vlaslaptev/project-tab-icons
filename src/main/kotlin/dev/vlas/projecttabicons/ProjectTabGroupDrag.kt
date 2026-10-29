package dev.vlas.projecttabicons

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.util.ui.JBUI
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

/** Граница возврата принадлежит началу жеста, а не уже перестроенному preview. */
internal class ProjectTabGroupDragBoundary(bounds: Rectangle?, private val threshold: Int) {
    private val bounds = bounds?.let(::Rectangle)
    var outside: Boolean = bounds == null
        private set

    fun move(centerX: Double, centerY: Double): Boolean {
        val area = bounds ?: return false
        val inset = if (outside) minOf(threshold.toDouble(), area.width / 4.0, area.height / 4.0) else -threshold.toDouble()
        val next = centerX < area.x + inset || centerX > area.maxX - inset ||
            centerY < area.y + inset || centerY > area.maxY - inset
        if (outside == next) return false
        outside = next
        return true
    }
}

/** Один жест на все копии native-полосы. Обработчик событий существует только во время drag. */
internal class ProjectTabGroupDrag {
    private class Hooks(
        val original: TabInfo.DragDelegate?, val installed: TabInfo.DragDelegate,
        val originalOut: TabInfo.DragOutDelegate?, val installedOut: TabInfo.DragOutDelegate?,
    ) {
        fun restore(info: TabInfo) {
            if (info.dragDelegate === installed) info.dragDelegate = original
            if (installedOut != null && info.dragOutDelegate === installedOut) info.setDragOutDelegate(originalOut)
        }
    }

    private class Session(val layout: ProjectTabGroupLayout, val source: TabInfo,
                          val peers: List<ProjectTabGroupLayout>, val boundary: ProjectTabGroupDragBoundary,
                          val holdX: Double, val holdY: Double) {
        val lifetime = Disposer.newDisposable("Project tab group drag")
        var nativeOut = false
        var cancelled = false
        var finishing = false
    }

    private val layouts = IdentityHashMap<ProjectTabGroupLayout, MutableMap<TabInfo, Hooks>>()
    private var session: Session? = null
    val isActive: Boolean get() = session != null

    fun bind(layout: ProjectTabGroupLayout) {
        val hooks = layouts.getOrPut(layout) { IdentityHashMap() }
        val tabs = layout.tabs.tabs
        if (hooks.size == tabs.size && tabs.all { info -> hooks[info]?.let {
                info.dragDelegate === it.installed && info.dragOutDelegate === it.installedOut
            } == true }) return
        hooks.keys.filter { it !in tabs }.forEach { info -> hooks.remove(info)?.restore(info) }
        tabs.forEach { info ->
            val previous = hooks[info]
            if (previous != null && info.dragDelegate === previous.installed &&
                info.dragOutDelegate === previous.installedOut) return@forEach
            previous?.restore(info)
            val original = info.dragDelegate
            val originalOut = info.dragOutDelegate
            val installed = object : TabInfo.DragDelegate {
                override fun dragStarted(event: MouseEvent) {
                    start(layout, info, event)
                    original?.dragStarted(event)
                }
                override fun dragFinishedOrCanceled() {
                    try { original?.dragFinishedOrCanceled() }
                    finally { finishLater(layout, info, fromNativeOut = false) }
                }
            }
            val installedOut = originalOut?.let { delegate -> object : TabInfo.DragOutDelegate {
                override fun dragOutStarted(event: MouseEvent, source: TabInfo) {
                    session?.takeIf { it.layout === layout && it.source === info }?.nativeOut = true
                    delegate.dragOutStarted(event, source)
                }
                override fun processDragOut(event: MouseEvent, source: TabInfo) = delegate.processDragOut(event, source)
                override fun dragOutFinished(event: MouseEvent, source: TabInfo) {
                    try { delegate.dragOutFinished(event, source) }
                    finally { finishLater(layout, info, fromNativeOut = true) }
                }
                override fun dragOutCancelled(source: TabInfo) {
                    session?.takeIf { it.layout === layout && it.source === info }?.cancelled = true
                    try { delegate.dragOutCancelled(source) }
                    finally { finishLater(layout, info, fromNativeOut = true) }
                }
            } }
            hooks[info] = Hooks(original, installed, originalOut, installedOut)
            info.dragDelegate = installed
            if (installedOut != null) info.setDragOutDelegate(installedOut)
        }
    }

    private fun start(layout: ProjectTabGroupLayout, source: TabInfo, event: MouseEvent) {
        session?.let { finish(it, commit = false) }
        val label = layout.tabs.getTabLabel(source) ?: return
        val point = SwingUtilities.convertPoint(event.component, event.point, layout.tabs)
        val peers = layouts.keys.filter { peer -> peer.tabs.tabs.any { it.`object` === source.`object` } }
        val active = Session(layout, source, peers,
            ProjectTabGroupDragBoundary(layout.groupBounds(source), JBUI.scale(4)),
            point.x - label.x - label.width / 2.0, point.y - label.y - label.height / 2.0)
        try {
            peers.forEach { it.beginDrag(source.`object`, moving = it === layout) }
        } catch (error: Exception) {
            peers.forEach { it.endDrag(commit = false) }
            com.intellij.openapi.diagnostic.Logger.getInstance(ProjectTabGroupDrag::class.java)
                .warn("Cannot install project tab drag painter; native dragging is preserved", error)
            return
        }
        session = active
        IdeEventQueue.getInstance().addPostprocessor(IdeEventQueue.EventDispatcher { input ->
            if (session === active) {
                if (input is KeyEvent && input.id == KeyEvent.KEY_PRESSED && input.keyCode == KeyEvent.VK_ESCAPE) active.cancelled = true
                if (input is MouseEvent && input.id == MouseEvent.MOUSE_DRAGGED) {
                    val current = layout.tabs.getTabLabel(source)
                    if (active.nativeOut) {
                        val at = SwingUtilities.convertPoint(input.component, input.point, layout.tabs)
                        active.boundary.move(at.x - active.holdX, at.y - active.holdY)
                    } else if (current != null) {
                        active.boundary.move(current.x + current.width / 2.0, current.y + current.height / 2.0)
                    }
                    layout.moveDrag(active.boundary.outside)
                }
            }
            false
        }, active.lifetime)
    }

    private fun finishLater(layout: ProjectTabGroupLayout, source: TabInfo, fromNativeOut: Boolean) {
        val active = session?.takeIf { it.layout === layout && it.source === source } ?: return
        // endDrag(true) вызывается ДО dragOutStarted: нельзя завершать жест на этом промежуточном callback.
        SwingUtilities.invokeLater {
            if (session === active && (!active.nativeOut || fromNativeOut)) finish(active, commit = !active.cancelled)
        }
    }

    private fun finish(active: Session, commit: Boolean) {
        if (active.finishing) return
        active.finishing = true
        Disposer.dispose(active.lifetime)
        // Сначала убираем снимки всех копий; только затем разрешаем запись окончательного разрыва.
        active.peers.forEach { it.endDrag(commit) }
        session = null
        active.peers.filter { it in layouts }.forEach { it.refreshOrder() }
    }

    fun unbind(layout: ProjectTabGroupLayout) {
        layouts.remove(layout)?.forEach { (info, hooks) -> hooks.restore(info) }
        session?.takeIf { it.layout === layout }?.let { finish(it, commit = false) }
    }

    fun invalidate(layout: ProjectTabGroupLayout) {
        session?.takeIf { layout in it.peers }?.let { finish(it, commit = false) }
    }

    /** Windows завершает визуальное состояние и общий порядок в одном событии EDT. */
    fun finishWindowsDrag(tabs: JBTabsImpl, commit: Boolean) {
        session?.takeIf { it.layout.tabs === tabs }?.let { finish(it, commit) }
    }
}

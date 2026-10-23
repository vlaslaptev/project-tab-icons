package dev.vlas.projecttabicons

import com.intellij.openapi.wm.impl.IdeFrameImpl
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JRootPane

internal const val WINDOW_TABS_CONTAINER_KEY = "WINDOW_TABS_CONTAINER_KEY"

/** Наблюдает только дерево верхних вкладок; редактор и tool windows не обходятся. */
internal class FrameObservation(
    private val frame: IdeFrameImpl?,
    val rootPane: JRootPane,
    private val changed: () -> Unit,
    private val titleChanged: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private val containers = identitySet<Container>()
    private val titleListener = PropertyChangeListener { titleChanged() }
    private val tabsListener = PropertyChangeListener { changed() }
    private val activationListener = object : WindowAdapter() {
        override fun windowActivated(event: WindowEvent) = changed()
        override fun windowDeactivated(event: WindowEvent) = changed()
    }
    private val childListener = object : ContainerAdapter() {
        override fun componentAdded(event: ContainerEvent) = changed()
        override fun componentRemoved(event: ContainerEvent) = changed()
    }

    init {
        frame?.addPropertyChangeListener("title", titleListener)
        frame?.addWindowListener(activationListener)
        rootPane.addPropertyChangeListener(WINDOW_TABS_CONTAINER_KEY, tabsListener)
    }

    fun update() {
        if (closed) return
        val present = identitySet<Container>()
        fun collect(container: Container) {
            if (!present.add(container)) return
            container.components.filterIsInstance<Container>().forEach(::collect)
        }
        (rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) as? Container)?.let(::collect)

        containers.filter { it !in present }.forEach { it.removeContainerListener(childListener) }
        present.filter { it !in containers }.forEach { it.addContainerListener(childListener) }
        containers.clear()
        containers.addAll(present)
    }

    override fun close() {
        closed = true
        frame?.removePropertyChangeListener("title", titleListener)
        frame?.removeWindowListener(activationListener)
        rootPane.removePropertyChangeListener(WINDOW_TABS_CONTAINER_KEY, tabsListener)
        containers.forEach { it.removeContainerListener(childListener) }
        containers.clear()
    }
}

internal fun <T> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

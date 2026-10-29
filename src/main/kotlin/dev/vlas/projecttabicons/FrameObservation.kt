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
    private val tabsKey: String? = WINDOW_TABS_CONTAINER_KEY,
) : AutoCloseable {
    private var closed = false
    private val containers = identitySet<Container>()
    private val titleListener = PropertyChangeListener { titleChanged() }
    private val tabsListener = PropertyChangeListener { changed() }
    private val decorationListener = PropertyChangeListener { event ->
        // JBTabsImpl.updateUI applies its native decoration in a later EDT task.
        // Observe that replacement, not our own border installation, so refresh
        // runs after it without creating a decoration/refresh loop.
        if (event.oldValue is ProjectTabBorder && event.newValue !is ProjectTabBorder) changed()
    }
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
        tabsKey?.let { rootPane.addPropertyChangeListener(it, tabsListener) }
    }

    fun update(tabsContainer: Container? = tabsKey?.let { rootPane.getClientProperty(it) as? Container }) {
        if (closed) return
        val present = identitySet<Container>()
        fun collect(container: Container) {
            if (!present.add(container)) return
            container.components.filterIsInstance<Container>().forEach(::collect)
        }
        tabsContainer?.let(::collect)

        containers.filter { it !in present }.forEach {
            it.removeContainerListener(childListener)
            it.removePropertyChangeListener("border", decorationListener)
        }
        present.filter { it !in containers }.forEach {
            it.addContainerListener(childListener)
            it.addPropertyChangeListener("border", decorationListener)
        }
        containers.clear()
        containers.addAll(present)
    }

    override fun close() {
        closed = true
        frame?.removePropertyChangeListener("title", titleListener)
        frame?.removeWindowListener(activationListener)
        tabsKey?.let { rootPane.removePropertyChangeListener(it, tabsListener) }
        containers.forEach {
            it.removeContainerListener(childListener)
            it.removePropertyChangeListener("border", decorationListener)
        }
        containers.clear()
    }
}

internal fun <T> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

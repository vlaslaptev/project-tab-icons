package dev.vlas.projecttabicons

import com.intellij.ui.tabs.JBTabPainter
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.tabs.impl.TabPainterAdapter
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/** Только на время жеста: фон пустого слота под children, без нативной плашки движущегося label. */
internal class ProjectTabDragPainter(
    private val tabs: JBTabsImpl,
    moving: TabInfo?,
    paintSlot: (Graphics2D) -> Unit,
) : AutoCloseable {
    private val original = adapterField.get(tabs) as TabPainterAdapter
    private val background = object : JBTabPainter by original.tabPainter {
        override fun fillBackground(component: Component, g: Graphics2D, rect: Rectangle) {
            original.tabPainter.fillBackground(component, g, rect)
            paintSlot(g)
        }
    }
    private val installed = object : TabPainterAdapter by original {
        override val tabPainter: JBTabPainter get() = background

        override fun paintBackground(label: TabLabel, g: Graphics, tabs: JBTabsImpl) {
            if (label.info !== moving || label.border !is ProjectTabBorder) original.paintBackground(label, g, tabs)
        }
    }

    init { adapterField.set(tabs, installed) }

    override fun close() {
        if (adapterField.get(tabs) === installed) adapterField.set(tabs, original)
    }

    companion object {
        // SDK 262 читает этот public final instance field при каждом paint; setter отсутствует.
        // Доступ проверяется один раз, исходный adapter возвращается до завершения drag / unload.
        private val adapterField by lazy {
            JBTabsImpl::class.java.getField("tabPainterAdapter").apply { isAccessible = true }
        }
    }
}

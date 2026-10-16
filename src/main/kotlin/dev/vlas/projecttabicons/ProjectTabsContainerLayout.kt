package dev.vlas.projecttabicons

import com.intellij.ui.mac.WindowTabsComponent
import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager2

/** Даёт рамке место вокруг содержимого, не заменяя вкладки и обработчики IDEA. */
internal class ProjectTabsContainerLayout(
    val original: LayoutManager2,
    var tabHeight: Int,
) : LayoutManager2 by original {
    override fun preferredLayoutSize(parent: Container): Dimension = withTabHeight(parent, original.preferredLayoutSize(parent))

    override fun minimumLayoutSize(parent: Container): Dimension = withTabHeight(parent, original.minimumLayoutSize(parent))

    private fun withTabHeight(parent: Container, size: Dimension): Dimension {
        val tabs = parent.components.filterIsInstance<WindowTabsComponent>().singleOrNull() ?: return size
        val contentHeight = tabs.tabs.maxOfOrNull { info ->
            val label = tabs.getTabLabel(info)
            label?.layout?.preferredLayoutSize(label)?.height ?: 0
        } ?: 0
        val requestedHeight = JBUI.scale(tabHeight.coerceIn(DEFAULT_TAB_HEIGHT, MAX_TAB_HEIGHT))
        val height = maxOf(requestedHeight, contentHeight) + parent.insets.top + parent.insets.bottom
        return Dimension(size.width, maxOf(size.height, height))
    }
}

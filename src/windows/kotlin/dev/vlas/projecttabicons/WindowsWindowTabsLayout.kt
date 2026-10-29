package dev.vlas.projecttabicons

import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.tabs.impl.singleRow.SingleRowLayout
import com.intellij.ui.tabs.impl.singleRow.SingleRowPassInfo
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

/** Equal window tabs without the editor tab margins and overlapping borders of SingleRowLayout. */
internal class WindowsWindowTabsLayout(private val projectTabs: JBTabsImpl) : SingleRowLayout(projectTabs) {
    override fun prepareLayoutPassInfo(data: SingleRowPassInfo, selectedInfo: TabInfo?) {
        super.prepareLayoutPassInfo(data, selectedInfo)
        data.insets = Insets(0, 0, 0, 0)
    }

    override fun recomputeToLayout(data: SingleRowPassInfo) {
        // This SDK method also fills toLayout with every visible tab.
        calculateRequiredLength(data)
    }

    override fun getRequiredLength(info: TabInfo): Int = projectTabs.width / maxOf(1, projectTabs.tabCount)

    override fun applyTabLayout(data: SingleRowPassInfo, label: TabLabel, length: Int): Boolean {
        val count = data.toLayout.size
        val index = data.toLayout.indexOf(label.info)
        if (count == 0 || index < 0) return super.applyTabLayout(data, label, length)
        // Derive both edges from the total width: no accumulated gaps or rounding loss.
        val left = projectTabs.width * index / count
        val right = projectTabs.width * (index + 1) / count
        projectTabs.layout(label, Rectangle(left, 0, right - left, projectTabs.height))
        label.setAlignmentToCenter(true)
        return true
    }

    override fun getDropIndexFor(point: Point): Int {
        val label = projectTabs.getComponentAt(point) as? TabLabel ?: return -1
        return lastSingRowLayout?.visibleInfos?.indexOf(label.info) ?: -1
    }

    override fun getDropSideFor(point: Point): Int = -1

    override fun isDragOut(tabLabel: TabLabel, deltaX: Int, deltaY: Int): Boolean {
        val bounds = tabLabel.bounds
        return if (bounds.x + bounds.width + deltaX >= 0 && bounds.x + bounds.width <= projectTabs.width)
            abs(deltaY) > tabLabel.height else true
    }
}

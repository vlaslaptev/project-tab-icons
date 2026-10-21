package dev.vlas.projecttabicons

import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.mac.WindowTabsComponent
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import javax.swing.JComponent

internal data class ProjectTabGroupMargins(val left: Int = 0, val right: Int = 0) {
    companion object { val NONE = ProjectTabGroupMargins() }
}

/** Только оформление. Порядок и hit areas вкладок остаются под управлением IDEA. */
internal class ProjectTabGroupLayout(
    private val tabs: WindowTabsComponent,
    private val visualState: ProjectTabGroupVisualState,
    private val isOrdering: () -> Boolean,
    private val visualStateChanged: () -> Unit,
) : AutoCloseable {
    private val surface = tabs.parent as? JComponent ?: tabs
    private val lifetime = Disposer.newDisposable("Project tab group layout")
    private var groups = emptyMap<TabInfo, String>()
    private var visibleGroups = emptyMap<TabInfo, String>()
    private var colors = emptyMap<String, ProjectTabGroupColors>()
    private var appearance = TabAppearanceState()
    private var icons = emptyMap<String, javax.swing.Icon>()
    private var order = emptyList<TabInfo>()
    private var baseMargins = emptyMap<TabInfo, ProjectTabGroupMargins>()
    private var prefixWidths = emptyMap<TabInfo, Int>()
    private var boundaries = emptyList<Pair<TabInfo, TabInfo>>()
    private var scaledSpacing = -1
    private var scaledUserSpacing = -1
    private var scaledIconSlot = -1
    private var lastSuspendedGroups = emptyList<String>()
    private var lastOrdering = false
    private val separator = ProjectTabGroupSeparator()
    private data class GroupRun(val id: String, val first: TabInfo, val last: TabInfo)
    private var runs = emptyList<GroupRun>()
    private var runsByTab = emptyMap<TabInfo, GroupRun>()
    private var orderDirty = true
    private var closed = false
    private fun hasDecorations(): Boolean = appearance.showGroupOutline || appearance.showGroupSeparator || appearance.showGroupIcon
    private val painter = object : AbstractPainter() {
        override fun needsRepaint(): Boolean = !closed && hasDecorations() && groups.isNotEmpty()

        override fun executePaint(component: Component, graphics: Graphics2D) {
            if (closed || !hasDecorations() || !tabs.isShowing) return
            val g = graphics.create() as Graphics2D
            try {
                g.clipRect(0, 0, surface.width, surface.height)
                val offsetX = if (surface === tabs) 0 else tabs.x
                val offsetY = if (surface === tabs) 0 else tabs.y
                if (appearance.showGroupIcon) runs.forEach { run ->
                    val first = tabs.getTabLabel(run.first) ?: return@forEach
                    if (!first.isVisible || first.width <= 0) return@forEach
                    val margin = margins(run.first).left
                    val slot = leadingSlot(run.first)
                    val icon = visibleGroupIcon(icons[run.id], slot, tabs.height, appearance)
                    val iconX = first.x + offsetX + margin - slot + (slot - (icon?.iconWidth ?: 0)) / 2
                    icon?.paintIcon(surface, g, iconX, first.y + offsetY + first.insets.top +
                        (first.height - first.insets.top - first.insets.bottom - icon.iconHeight) / 2)
                }
                if (appearance.showGroupSeparator) boundaries.forEach { (leftInfo, rightInfo) ->
                    val left = tabs.getTabLabel(leftInfo) ?: return@forEach
                    val right = tabs.getTabLabel(rightInfo) ?: return@forEach
                    if (!left.isVisible || !right.isVisible || left.width <= 0 || right.width <= 0) return@forEach
                    val from = left.x + left.width - margins(leftInfo).right
                    val rightMargin = margins(rightInfo).left
                    val to = right.x + rightMargin - leadingSlot(rightInfo)
                    if (to - from < JBUI.scale(appearance.groupSeparatorThickness + 6)) return@forEach
                    val color = if (appearance.groupSeparatorColor == GroupSeparatorColor.GROUP)
                        colors[visibleGroups[leftInfo] ?: visibleGroups[rightInfo]]?.outline ?: neutralGroupSeparatorColor else neutralGroupSeparatorColor
                    separator.paint(g, (from + to) / 2.0 + offsetX, offsetY.toDouble(), tabs.height,
                        appearance.groupSeparatorThickness, appearance.groupSeparatorHeight, color)
                }
            } finally { g.dispose() }
        }
    }

    init {
        IdeGlassPaneUtil.installPainter(surface, painter, lifetime)
        tabs.addListener(object : TabsListener {
            override fun tabsMoved() {
                // Пересчитываем внутренние поля подписи при DnD; сортировку не запускаем.
                refreshOrder()
            }
            override fun tabRemoved(tab: TabInfo) = refreshOrder()
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                refreshOrder()
                if (appearance.showGroupOutline) tabs.repaint()
            }
        }, lifetime)
    }

    fun update(value: TabAppearanceState, memberships: Map<TabInfo, String>, groupColors: Map<String, ProjectTabGroupColors>,
               groupIcons: Map<String, javax.swing.Icon>) {
        if (appearance == value && groups == memberships && colors == groupColors && icons == groupIcons) {
            refreshOrder()
            return
        }
        appearance = value
        groups = memberships
        colors = groupColors
        icons = groupIcons
        orderDirty = true
        refreshOrder()
    }

    fun refreshOrder() {
        if (closed) return
        val next = tabs.tabs
        val spacing = JBUI.scale(groupBoundarySpacing(appearance))
        val userSpacing = JBUI.scale(appearance.groupSpacing)
        val iconSlot = if (appearance.showGroupIcon) groupIconSlotWidth(appearance) else 0
        val ordering = isOrdering()
        if (next == order && scaledSpacing == spacing && scaledUserSpacing == userSpacing && scaledIconSlot == iconSlot &&
            lastSuspendedGroups == visualState.state.suspendedGroupIds && lastOrdering == ordering && !orderDirty) return
        order = next.toList()
        scaledSpacing = spacing
        scaledUserSpacing = userSpacing
        scaledIconSlot = iconSlot
        val ids = order.map(groups::get)
        val intactRanges = contiguousProjectTabGroups(ids)
        if (!ordering) {
            val intactIds = intactRanges.mapNotNull { ids[it.first] }.toSet()
            if (visualState.suspendGroups(ids.filterNotNull().toSet() - intactIds)) visualStateChanged()
        }
        lastSuspendedGroups = visualState.state.suspendedGroupIds
        lastOrdering = ordering
        val suspended = lastSuspendedGroups.toSet()
        // Приостановленная группа ведёт себя как обычные вкладки, включая обе стороны отступов.
        val visibleRanges = intactRanges.filter { ids[it.first] !in suspended }
        val visibleGroupIds = visibleRanges.mapNotNull { ids[it.first] }.toSet()
        val visibleIds = ids.map { it?.takeIf(visibleGroupIds::contains) }
        visibleGroups = buildMap { order.forEachIndexed { index, info -> visibleIds[index]?.let { put(info, it) } } }
        runs = visibleRanges.map { range ->
            GroupRun(groups.getValue(order[range.first]), order[range.first], order[range.last])
        }
        runsByTab = buildMap {
            visibleRanges.zip(runs).forEach { (range, run) -> range.forEach { put(order[it], run) } }
        }
        prefixWidths = buildMap {
            runs.forEach { run ->
                val slot = scaledIconSlot.takeIf { run.id in icons } ?: 0
                if (slot > 0) put(run.first, slot)
            }
        }
        val separatorIndices = if (appearance.showGroupSeparator) projectTabGroupSeparatorBoundaries(visibleIds, visibleRanges) else emptyList()
        boundaries = separatorIndices.map { order[it - 1] to order[it] }
        val boundaryWidths = IntArray(order.size) { index ->
            if (index > 0 && hasGroupBoundary(visibleIds[index - 1], visibleIds[index])) userSpacing else 0
        }
        separatorIndices.forEach { boundaryWidths[it] = spacing }
        baseMargins = buildMap {
            order.forEachIndexed { index, info ->
                val before = boundaryWidths[index] / 2
                val after = if (index < order.lastIndex) boundaryWidths[index + 1] else 0
                val right = after - after / 2
                if (before + right > 0) put(info, ProjectTabGroupMargins(before, right))
            }
        }
        orderDirty = false
        order.forEach { tabs.getTabLabel(it)?.revalidate() }
        surface.revalidate()
        surface.repaint()
        tabs.revalidateAndRepaint()
        painter.setNeedsRepaint(true, surface)
    }

    /** Только успешный явный Arrange снимает приостановку оформления. */
    fun restoreAppearance() {
        if (closed) return
        if (visualState.restore(tabs.tabs.mapNotNull(groups::get).toSet())) visualStateChanged()
        refreshOrder()
    }

    fun minimumTabHeight(): Int = if (!closed && appearance.showGroupIcon && runs.any { it.id in icons })
        groupIconMinimumHeight(appearance) else 0

    /** Скрываем значки участников только пока их общая иконка действительно видна. */
    fun hidesTabIcon(info: TabInfo): Boolean {
        if (closed || !appearance.showGroupIcon || !appearance.hideGroupedTabIcons) return false
        val run = runsByTab[info] ?: return false
        val label = tabs.getTabLabel(run.first) ?: return false
        if (!label.isVisible || label.width <= 0) return false
        val slot = leadingSlot(run.first)
        return visibleGroupIcon(icons[run.id], slot, tabs.height, appearance) != null
    }

    fun margins(info: TabInfo): ProjectTabGroupMargins {
        if (closed) return ProjectTabGroupMargins.NONE
        val iconSlot = if (appearance.showGroupIcon) groupIconSlotWidth(appearance) else 0
        if (scaledSpacing != JBUI.scale(groupBoundarySpacing(appearance)) ||
            scaledUserSpacing != JBUI.scale(appearance.groupSpacing) || scaledIconSlot != iconSlot) refreshOrder()
        val desired = baseMargins[info] ?: ProjectTabGroupMargins.NONE
        val limit = marginLimit(info)
        return ProjectTabGroupMargins(minOf(desired.left, limit) + leadingSlot(info), minOf(desired.right, limit))
    }

    private fun marginLimit(info: TabInfo): Int {
        val width = tabs.getTabLabel(info)?.width?.takeIf { it > 0 } ?: return Int.MAX_VALUE
        return width / 3
    }

    /** Не оставляем пустой префикс, когда общая иконка уже не помещается. */
    private fun leadingSlot(info: TabInfo): Int {
        val wanted = prefixWidths[info] ?: return 0
        val limit = marginLimit(info)
        val available = (limit - (baseMargins[info]?.left ?: 0)).coerceAtLeast(0)
        return wanted.takeIf { it <= available } ?: 0
    }

    fun usesSharedSurface(info: TabInfo): Boolean = !closed && appearance.showGroupOutline &&
        info in runsByTab

    fun paintTabSurface(info: TabInfo, g: Graphics2D, width: Int, height: Int,
                        colors: ProjectTabSurfaceColors): Boolean {
        if (!usesSharedSurface(info)) return false
        val run = runsByTab[info] ?: return false
        val label = tabs.getTabLabel(info) ?: return false
        val first = tabs.getTabLabel(run.first) ?: return false
        val last = tabs.getTabLabel(run.last) ?: return false
        if (!first.isVisible || !last.isVisible || first.width <= 0 || last.width <= 0) return false
        val left = first.x + margins(run.first).left - leadingSlot(run.first)
        val right = last.x + last.width - margins(run.last).right
        val source = projectTabGeometry((right - left).coerceAtLeast(0), height)
        val geometry = source.copy(bounds = Rectangle2D.Double(source.bounds.x + left - label.x,
            source.bounds.y, source.bounds.width, source.bounds.height))
        val edges = margins(info)
        val state = ProjectTabGroupSurfaceState(appearance, geometry,
            Rectangle2D.Double(edges.left.toDouble(), 0.0, (width - edges.left - edges.right).coerceAtLeast(0).toDouble(), height.toDouble()),
            this.colors[run.id] ?: ProjectTabGroupColors(neutralGroupSeparatorColor, neutralGroupSeparatorColor),
            info === run.first, info === run.last, runsByTab[tabs.selectedInfo] === run,
            info !== run.last && tabs.getTabAt(tabs.getIndexOf(info) + 1) === tabs.selectedInfo)
        ProjectTabGroupSurface.paint(g, width, height, state, colors, info === tabs.selectedInfo, tabs.isHoveredOrWithPopup(label))
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        Disposer.dispose(lifetime)
        surface.revalidate()
        surface.repaint()
        tabs.revalidateAndRepaint()
    }
}

/** Учитываются только открытые участники этой полосы; закрытые проекты её не разрывают. */
internal fun contiguousProjectTabGroups(ids: List<String?>): List<IntRange> {
    val indices = linkedMapOf<String, MutableList<Int>>()
    ids.forEachIndexed { index, id -> if (id != null) indices.getOrPut(id) { mutableListOf() }.add(index) }
    return indices.values.mapNotNull { positions ->
        (positions.first()..positions.last()).takeIf { it.count() == positions.size }
    }
}

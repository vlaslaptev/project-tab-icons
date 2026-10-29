package dev.vlas.projecttabicons

import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import javax.swing.JComponent

internal data class ProjectTabGroupMargins(val left: Int = 0, val right: Int = 0) {
    companion object { val NONE = ProjectTabGroupMargins() }
}

/** Только оформление. Порядок и hit areas вкладок остаются под управлением IDEA. */
internal class ProjectTabGroupLayout(
    val tabs: JBTabsImpl,
    private val visualState: ProjectTabGroupVisualState,
    private val drag: ProjectTabGroupDrag,
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
    private data class Decorations(
        val order: List<TabInfo>, val visibleGroups: Map<TabInfo, String>, val runs: List<GroupRun>,
        val runsByTab: Map<TabInfo, GroupRun>, val margins: Map<TabInfo, ProjectTabGroupMargins>,
        val prefixes: Map<TabInfo, Int>, val boundaries: List<Pair<TabInfo, TabInfo>>,
    )
    private class DragState(val source: TabInfo, val moving: Boolean, val group: String?, val original: Decorations,
                            val slots: List<Rectangle>, val width: Int, val height: Int) {
        var outside = false
        var liveOrder = original.order
        var positions = original.order.zip(slots).toMap()
        var frames = emptyMap<String, GroupFrame>()
        var separators = emptyList<SeparatorFrame>()
    }
    private data class GroupFrame(val bounds: Rectangle, val geometry: ProjectTabGeometry, val iconSlot: Int,
                                  val iconY: Int, val surfaceColors: ProjectTabSurfaceColors,
                                  val outline: ProjectTabOutline = ProjectTabOutline())
    private data class SeparatorFrame(val x: Double, val color: java.awt.Color)
    private var dragState: DragState? = null
    private var dragPainter: ProjectTabDragPainter? = null
    private var groupFrames = emptyMap<String, GroupFrame>()
    private var separatorFrames = emptyList<SeparatorFrame>()
    private var cancelledOrder: List<TabInfo>? = null
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
                if (dragState != null) {
                    g.translate(offsetX, offsetY)
                    paintDragDecorations(g)
                    return
                }
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
        drag.bind(this)
        if (appearance == value && groups == memberships && colors == groupColors && icons == groupIcons) {
            refreshOrder()
            return
        }
        if (dragState != null && (appearance != value || groups != memberships || colors != groupColors || icons != groupIcons)) drag.invalidate(this)
        appearance = value
        groups = memberships
        colors = groupColors
        icons = groupIcons
        orderDirty = true
        refreshOrder()
    }

    fun refreshOrder() {
        if (closed) return
        val gesture = dragState
        if (gesture != null && !gesture.outside) return
        // tabs.tabs остаётся закешированным до endDrag() в SDK 262.
        val next = if (gesture != null) tabs.getVisibleInfos() else tabs.tabs
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
        if (!ordering && !drag.isActive && next != cancelledOrder) {
            val intactIds = intactRanges.mapNotNull { ids[it.first] }.toSet()
            if (visualState.suspendGroups(ids.filterNotNull().toSet() - intactIds)) visualStateChanged()
        }
        lastSuspendedGroups = visualState.state.suspendedGroupIds
        lastOrdering = ordering
        val suspended = lastSuspendedGroups.toSet() + listOfNotNull(gesture?.group?.takeIf { gesture.outside })
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
        if (gesture != null) captureDragFrames()
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

    // Preview без общей иконки не должен менять высоту полосы и инвалидировать собственный жест.
    fun minimumTabHeight(): Int = if (!closed && appearance.showGroupIcon &&
        (dragState?.original?.runs ?: runs).any { it.id in icons })
        groupIconMinimumHeight(appearance) else 0

    /** Скрываем значки участников только пока их общая иконка действительно видна. */
    fun hidesTabIcon(info: TabInfo): Boolean {
        if (closed || !appearance.showGroupIcon || !appearance.hideGroupedTabIcons) return false
        val run = runsByTab[info] ?: return false
        if (dragState != null) return groupFrames[run.id]?.let {
            visibleGroupIcon(icons[run.id], it.iconSlot, tabs.height, appearance) != null
        } == true
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
        val width = (dragState?.positions?.get(info)?.width ?: tabs.getTabLabel(info)?.width)?.takeIf { it > 0 } ?: return Int.MAX_VALUE
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

    fun isMovingTab(info: TabInfo): Boolean = dragState?.let { it.moving && it.source === info } == true

    fun paintTabSurface(info: TabInfo, g: Graphics2D, width: Int, height: Int,
                        colors: ProjectTabSurfaceColors): Boolean {
        if (!usesSharedSurface(info)) return false
        val run = runsByTab[info] ?: return false
        val label = tabs.getTabLabel(info) ?: return false
        val edges = margins(info)
        if (isMovingTab(info)) {
            // Видимое тело движется независимо от неподвижной геометрии группы.
            // Поля общей иконки / разделителя остаются прозрачными для соседних label-ов.
            val body = projectTabGeometry((width - edges.left - edges.right).coerceAtLeast(0), height, appearance.tabCorners)
            val geometry = body.copy(bounds = Rectangle2D.Double(body.bounds.x + edges.left,
                body.bounds.y, body.bounds.width, body.bounds.height))
            ProjectTabGroupSurface.paint(g, width, height,
                ProjectTabGroupSurfaceState(appearance, geometry, geometry.bounds,
                    this.colors[run.id] ?: ProjectTabGroupColors(neutralGroupSeparatorColor, neutralGroupSeparatorColor),
                    true, true, false), colors, info === tabs.selectedInfo, tabs.isHoveredOrWithPopup(label),
                outlineVisible = false, clearBackground = false)
            return true
        }
        val fixed = if (dragState != null) groupFrames[run.id] else null
        val first = tabs.getTabLabel(run.first) ?: return false
        val last = tabs.getTabLabel(run.last) ?: return false
        if (fixed == null && (!first.isVisible || !last.isVisible || first.width <= 0 || last.width <= 0)) return false
        val left = fixed?.bounds?.x ?: (first.x + margins(run.first).left - leadingSlot(run.first))
        val right = fixed?.bounds?.let { it.x + it.width } ?: (last.x + last.width - margins(run.last).right)
        val source = fixed?.geometry ?: projectTabGeometry((right - left).coerceAtLeast(0), height, appearance.tabCorners)
        val geometry = source.copy(bounds = Rectangle2D.Double(source.bounds.x + (if (fixed == null) left else 0) - label.x,
            source.bounds.y - if (fixed != null) label.y else 0, source.bounds.width, source.bounds.height))
        val state = ProjectTabGroupSurfaceState(appearance, geometry,
            Rectangle2D.Double(edges.left.toDouble(), 0.0, (width - edges.left - edges.right).coerceAtLeast(0).toDouble(), height.toDouble()),
            this.colors[run.id] ?: ProjectTabGroupColors(neutralGroupSeparatorColor, neutralGroupSeparatorColor),
            if (fixed != null) label.x <= left else info === run.first,
            if (fixed != null) label.x + width >= right else info === run.last,
            info !== run.last && tabs.getVisibleInfos().getOrNull(tabs.getIndexOf(info) + 1) === tabs.selectedInfo)
        ProjectTabGroupSurface.paint(g, width, height, state, colors, info === tabs.selectedInfo, tabs.isHoveredOrWithPopup(label),
            outlineVisible = fixed == null)
        return true
    }

    private fun runBounds(run: GroupRun): Rectangle? {
        val first = dragState?.positions?.get(run.first) ?: tabs.getTabLabel(run.first)?.bounds ?: return null
        val last = dragState?.positions?.get(run.last) ?: tabs.getTabLabel(run.last)?.bounds ?: return null
        if (first.width <= 0 || last.width <= 0) return null
        val left = first.x + margins(run.first).left - leadingSlot(run.first)
        val right = last.x + last.width - margins(run.last).right
        return Rectangle(left, first.y, (right - left).coerceAtLeast(0), first.height)
    }

    fun groupBounds(info: TabInfo): Rectangle? = runsByTab[info]?.let(::runBounds)

    fun beginDrag(owner: Any?, moving: Boolean = true) {
        if (closed) return
        val source = order.firstOrNull { it.`object` === owner } ?: return
        val saved = Decorations(order, visibleGroups, runs, runsByTab, baseMargins, prefixWidths, boundaries)
        val slots = order.map { tabs.getTabLabel(it)?.bounds ?: Rectangle() }
        dragState = DragState(source, moving, visibleGroups[source], saved, slots, tabs.width, tabs.height)
        captureDragFrames()
        dragState?.let { it.frames = groupFrames; it.separators = separatorFrames }
        dragPainter = ProjectTabDragPainter(tabs, source.takeIf { moving }, ::paintDragBackground)
    }

    fun moveDrag(outside: Boolean) {
        val gesture = dragState ?: return
        if (tabs.width != gesture.width || tabs.height != gesture.height) {
            drag.invalidate(this)
            return
        }
        val live = tabs.getVisibleInfos()
        if (gesture.outside == outside && gesture.liveOrder == live) return
        gesture.liveOrder = live.toList()
        gesture.outside = outside
        // WindowTabsLayout делит ширину на равные слоты; движущийся label не является якорем.
        gesture.positions = live.mapIndexed { index, info ->
            val width = tabs.width / live.size
            info to if (live.size == gesture.slots.size) gesture.slots[index] else
                Rectangle(index * width, 0, width + if (index == live.lastIndex) tabs.width % live.size else 0, tabs.height)
        }.toMap()
        if (outside) {
            orderDirty = true
            refreshOrder()
        } else {
            val original = gesture.original
            order = original.order
            visibleGroups = original.visibleGroups
            runs = original.runs
            runsByTab = original.runsByTab
            boundaries = original.boundaries
            // Поля принадлежат слотам. У движущегося таба сохраняем исходное положение подписи.
            baseMargins = live.mapIndexed { index, info ->
                info to (original.margins[if (info === gesture.source) info else original.order.getOrNull(index)] ?: ProjectTabGroupMargins.NONE)
            }.toMap()
            prefixWidths = live.mapIndexed { index, info ->
                info to (original.prefixes[if (info === gesture.source) info else original.order.getOrNull(index)] ?: 0)
            }.toMap()
            // Возврат восстанавливает исходные геометрические якоря, даже если IDEA ещё переставляет участников.
            groupFrames = gesture.frames
            separatorFrames = gesture.separators
            live.forEach { tabs.getTabLabel(it)?.revalidate() }
            tabs.repaint()
            painter.setNeedsRepaint(true, surface)
        }
    }

    fun endDrag(commit: Boolean) {
        if (dragState == null) return
        dragPainter?.close()
        dragPainter = null
        cancelledOrder = if (commit) null else tabs.tabs.toList()
        dragState = null
        groupFrames = emptyMap()
        separatorFrames = emptyList()
        orderDirty = true
    }

    private fun captureDragFrames() {
        groupFrames = runs.mapNotNull { run ->
            val bounds = runBounds(run) ?: return@mapNotNull null
            val first = tabs.getTabLabel(run.first) ?: return@mapNotNull null
            val palette = nativeProjectTabSurfaceColors(first) ?: return@mapNotNull null
            val geometry = projectTabGeometry(bounds.width, bounds.height, appearance.tabCorners).let {
                it.copy(bounds = Rectangle2D.Double(it.bounds.x + bounds.x, it.bounds.y + bounds.y, it.bounds.width, it.bounds.height))
            }
            val icon = visibleGroupIcon(icons[run.id], leadingSlot(run.first), tabs.height, appearance)
            run.id to GroupFrame(bounds, geometry, leadingSlot(run.first), bounds.y + first.insets.top +
                (bounds.height - first.insets.top - first.insets.bottom - (icon?.iconHeight ?: 0)) / 2, palette)
        }.toMap()
        separatorFrames = boundaries.mapNotNull { (leftInfo, rightInfo) ->
            val left = dragState?.positions?.get(leftInfo) ?: return@mapNotNull null
            val right = dragState?.positions?.get(rightInfo) ?: return@mapNotNull null
            val from = left.x + left.width - margins(leftInfo).right
            val to = right.x + margins(rightInfo).left - leadingSlot(rightInfo)
            if (to - from < JBUI.scale(appearance.groupSeparatorThickness + 6)) return@mapNotNull null
            val color = if (appearance.groupSeparatorColor == GroupSeparatorColor.GROUP)
                colors[visibleGroups[leftInfo] ?: visibleGroups[rightInfo]]?.outline ?: neutralGroupSeparatorColor else neutralGroupSeparatorColor
            SeparatorFrame((from + to) / 2.0, color)
        }
    }

    /** Вызывается из fillBackground полосы до children: заливка не может закрыть соседний текст. */
    private fun paintDragBackground(g: Graphics2D) {
        val gesture = dragState ?: return
        val slot = gesture.positions[gesture.source] ?: return
        if (!appearance.showGroupOutline) return
        val background = g.create(slot.x, slot.y, slot.width, slot.height) as Graphics2D
        try {
            background.translate(-slot.x, -slot.y)
            groupFrames.forEach { (id, frame) ->
                if (!slot.intersects(frame.bounds)) return@forEach
                val groupColors = colors[id] ?: return@forEach
                ProjectTabGroupSurface.paint(background, tabs.width, tabs.height,
                    ProjectTabGroupSurfaceState(appearance, frame.geometry, Rectangle2D.Double(), groupColors, false, true, false),
                    frame.surfaceColors, false, outlineVisible = false, clearBackground = false)
            }
        } finally { background.dispose() }
    }

    /** Поверх children остаются только общий контур, иконка и разделители; никаких фоновых плашек. */
    private fun paintDragDecorations(g: Graphics2D) {
        val gesture = dragState ?: return
        groupFrames.forEach { (id, frame) ->
            val groupColors = colors[id] ?: return@forEach
            if (appearance.showGroupOutline) {
                if (appearance.showOutline) {
                    val idle = ProjectTabSurface.resolve(frame.surfaceColors.copy(background = frame.surfaceColors.idleBackground,
                        state = ProjectTabSurfaceState.IDLE), null, 0, appearance.activeTabHighlight).background
                    frame.outline.paint(g, frame.geometry, ProjectTabSurface.blend(idle, groupColors.outline,
                        inactiveTabStrength(false, appearance.inactiveTabDimming)), appearance.outlineThickness, appearance.outlineStyle)
                    val selected = gesture.positions[tabs.selectedInfo]
                    if (selected != null && selected.intersects(frame.bounds)) {
                        val active = g.create() as Graphics2D
                        try {
                            active.clip(selected)
                            frame.outline.paint(active, frame.geometry, groupColors.outline, appearance.outlineThickness, appearance.outlineStyle)
                        } finally { active.dispose() }
                    }
                }
            }
            if (appearance.showGroupIcon) {
                visibleGroupIcon(icons[id], frame.iconSlot, tabs.height, appearance)?.let { icon ->
                    icon.paintIcon(surface, g, frame.bounds.x + (frame.iconSlot - icon.iconWidth) / 2, frame.iconY)
                }
            }
        }
        if (appearance.showGroupSeparator) separatorFrames.forEach {
            separator.paint(g, it.x, 0.0, tabs.height, appearance.groupSeparatorThickness, appearance.groupSeparatorHeight, it.color)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        drag.unbind(this)
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

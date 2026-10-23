package dev.vlas.projecttabicons

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.KeyboardFocusManager
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** Те же колонки и реальные Swing-контролы, что у проектов; форма редактирования — часть строки. */
internal class ProjectGroupsTable(
    private val selectedProjects: () -> Set<String>,
    private val save: (String?, String, Set<String>, Map<String, Int>) -> String?,
    private val delete: (String) -> Unit,
    private val assign: (String) -> Unit,
    private val move: (String, Int) -> Unit,
    private val showProjects: (String) -> Unit,
    private val chooseIcon: (String) -> Unit,
    private val chooseColor: (String, ProjectColorTarget) -> Unit,
    private val editingChanged: () -> Unit,
) : JPanel(BorderLayout()) {
    private class Input(val id: String?, name: String, val projects: MutableSet<String>, val knownKeys: MutableSet<String>,
                        val positions: MutableMap<String, Int>) {
        val nameField = JBTextField(name).apply {
            emptyText.text = "Group name"
            accessibleContext.accessibleName = if (id == null) "New project group name" else "Edit project group name"
            putClientProperty("projectGroupControl", "name")
        }
        var error: String? = null
    }
    private var input: Input? = null
    val isEditing get() = input != null
    private var state = ProjectGroupsState()
    private var baseline = ProjectGroupsState()
    private var projects = emptyList<ProjectIconSettingsRow>()
    private var editingEnabled = true
    private var canAssign = false
    private val columns = ProjectColumns()
    private val rows = ProjectRowsPanel()
    private val scroll = JBScrollPane(rows).apply {
        border = JBUI.Borders.customLine(JBColor.border())
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        minimumSize = JBUI.size(0, 0)
        preferredSize = JBUI.size(660, 300)
        viewport.background = UIUtil.getListBackground()
        accessibleContext.accessibleName = "Project groups table"
        setColumnHeaderView(JPanel(columns.layout(header = true)).apply {
            isOpaque = false
            listOf("Group", "Projects", "Icon", "Icon color", "Outline color", "Tab color", "Actions").forEach { add(settingsHint(it)) }
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        })
    }
    private var memberScroll: JBScrollPane? = null
    private val availability = mutableListOf<Pair<JComponent, () -> Boolean>>()

    init {
        isOpaque = false; add(scroll, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { memberScroll?.revalidate(); rows.revalidate() }
        })
        refresh()
    }

    fun showGroups(value: ProjectGroupsState, saved: ProjectGroupsState, projectRows: List<ProjectIconSettingsRow>) {
        state = value; baseline = saved; projects = projectRows.filter { it.target.path != null && !it.target.project.isDisposed }
        input?.let { pending ->
            if (pending.id != null && state.groups.none { it.id == pending.id }) input = null
            projects.forEach { row ->
                if (pending.knownKeys.add(row.key) && pending.id != null && state.memberships[row.key] == pending.id) pending.projects += row.key
            }
        }
        refresh()
    }
    fun cancelInput() {
        if (input != null) { input = null; memberScroll = null; refresh(); editingChanged() }
    }
    fun setEditingEnabled(enabled: Boolean, selected: Boolean) {
        editingEnabled = enabled; canAssign = selected
        availability.forEach { (control, allowed) ->
            control.isEnabled = enabled && allowed()
            if (control is SettingsValueButton) control.muteDisabledIcon = !enabled || control.getClientProperty("preserveGroupIcon") != true
        }
        fun dim(view: Component) {
            if (view is JBLabel || view is ProjectRowPanel) view.isEnabled = enabled
            if (view is Container) view.components.forEach(::dim)
        }
        scroll.columnHeader.view?.let(::dim)
        dim(rows)
        rows.isEnabled = enabled
        rows.repaint()
    }
    private fun begin(id: String? = null) {
        if (!editingEnabled || input != null || (id == null && state.groups.size >= 50)) return
        val group = id?.let { key -> state.groups.firstOrNull { it.id == key } ?: return }
        val keys = (projects.map { it.key } + state.memberships.filterValues { it == id }.keys).toMutableSet()
        input = Input(id, group?.name.orEmpty(), if (id == null) selectedProjects().intersect(keys).toMutableSet()
            else state.memberships.filterValues { it == id }.keys.toMutableSet(), keys,
            group?.memberPositions.orEmpty().toMutableMap()).also { pending ->
            pending.nameField.addActionListener { submit() }
        }
        memberScroll = null
        refresh(focusName = true)
        editingChanged()
    }
    private fun submit() {
        if (!editingEnabled) return
        val pending = input ?: return
        input = null
        val error = save(pending.id, pending.nameField.text.trim(), pending.projects.toSet(),
            pending.positions.toMap())
        if (error != null) {
            pending.error = error
            input = pending
            refresh(focusName = true)
        } else {
            // save уже обновил таблицу через родительский showGroups().
            memberScroll = null
        }
        editingChanged()
    }
    private fun register(control: JComponent, enabled: Boolean = true): JComponent = registerWhen(control) { enabled }
    private fun registerWhen(control: JComponent, allowed: () -> Boolean): JComponent {
        availability += control to allowed
        control.isEnabled = editingEnabled && allowed()
        return control
    }
    private fun refresh(focusName: Boolean = false) {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JComponent
        val focusKey = owner?.takeIf { SwingUtilities.isDescendingFrom(it, rows) }?.getClientProperty("projectGroupControl")
        val y = scroll.verticalScrollBar.value
        val memberY = memberScroll?.verticalScrollBar?.value ?: 0
        rows.removeAll(); availability.clear()
        var index = 0
        fun addRow(view: JComponent) {
            rows.add(view, GridBagConstraints().apply { gridx = 0; gridy = index++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        }
        val plus = ProjectRowPanel(columns.layout()).apply {
            add(register(SettingsValueButton("Add group", AllIcons.General.Add).apply {
                accessibleContext.accessibleName = "Add project group"
                toolTipText = if (state.groups.size >= 50) "You can create up to 50 groups." else "Add a group directly in this table"
                addActionListener { begin() }
            }, input == null && state.groups.size < 50))
            repeat(6) { add(JBLabel()) }
            trackHover()
        }
        addRow(plus)
        if (input?.id == null && input != null) addRow(inputRow(null))
        state.groups.forEach { group ->
            val members = projects.filter { state.memberships[it.key] == group.id }
            if (input?.id == group.id) addRow(inputRow(group)) else addRow(groupRow(group, members))
        }
        if (state.groups.isEmpty() && input == null) addRow(settingsHint("No groups yet. Use + to add a group.").apply {
            border = JBUI.Borders.empty(16, 12)
        })
        rows.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = index; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
        })
        rows.revalidate(); rows.repaint(); revalidate()
        SwingUtilities.invokeLater {
            if (!isShowing) return@invokeLater
            scroll.verticalScrollBar.value = y
            memberScroll?.verticalScrollBar?.value = memberY
            fun find(view: Component): JComponent? {
                if (focusKey == null) return null
                if (view is JComponent && view.getClientProperty("projectGroupControl") == focusKey) return view
                return (view as? Container)?.components?.firstNotNullOfOrNull(::find)
            }
            val focus = if (focusName) input?.nameField else find(rows)
            focus?.takeIf { it.isEnabled }?.let {
                it.scrollRectToVisible(java.awt.Rectangle(0, 0, it.width, it.height)); it.requestFocusInWindow()
            }
        }
    }
    private fun identity(group: ProjectTabGroup): JComponent = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
        isOpaque = false
        add(register(SettingsValueButton(settingsLiteral(group.name)).apply {
            accessibleContext.accessibleName = "Show projects in group ${group.name}"
            toolTipText = "Show this group's projects in the Projects tab"
            putClientProperty("projectGroupControl", "${group.id}/projects")
            addActionListener { showProjects(group.id) }
        }), BorderLayout.NORTH)
    }
    private fun groupRow(group: ProjectTabGroup, members: List<ProjectIconSettingsRow>): JComponent = ProjectRowPanel(columns.layout()).apply {
        accessibleContext.accessibleName = "Project group ${group.name}"
        add(identity(group))
        val total = state.memberships.count { it.value == group.id }
        add(settingsHint("${members.size} open" + if (total != members.size) " · $total total" else "").apply {
            toolTipText = "Group members are arranged together on the left when windows merge"
        })
        addAppearance(this, group, enabled = true)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            fun action(name: String, icon: javax.swing.Icon, allowed: () -> Boolean, run: () -> Unit) {
                add(registerWhen(SettingsValueButton("", icon).apply {
                    preferredSize = JBUI.size(32, 32)
                    toolTipText = name; accessibleContext.accessibleName = name
                    putClientProperty("projectGroupControl", "${group.id}/$name")
                    addActionListener { if (editingEnabled) run() }
                }, allowed))
            }
            action("Assign selected projects to ${group.name}", AllIcons.Actions.Checked, { input == null && canAssign }) { assign(group.id) }
            action("Edit project group ${group.name}", AllIcons.Actions.Edit, { input == null }) { begin(group.id) }
            action("Delete project group ${group.name}", AllIcons.General.Remove, { input == null }) { delete(group.id) }
            val index = state.groups.indexOfFirst { it.id == group.id }
            action("Move group ${group.name} up", AllIcons.Actions.MoveUp, { input == null && index > 0 }) { move(group.id, -1) }
            action("Move group ${group.name} down", AllIcons.Actions.MoveDown, { input == null && index < state.groups.lastIndex }) { move(group.id, 1) }
        })
        trackHover()
    }
    private fun inputRow(group: ProjectTabGroup?): JComponent {
        val pending = requireNotNull(input)
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            background = UIUtil.getListBackground()
            accessibleContext.accessibleName = if (group == null) "New project group row" else "Edit project group ${group.name} inline"
            add(ProjectRowPanel(columns.layout()).apply {
                add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                    isOpaque = false
                    add(register(pending.nameField), BorderLayout.NORTH)
                    add(settingsHint("Name · 40 max"), BorderLayout.SOUTH)
                })
                add(settingsHint("Select below"))
                addAppearance(this, group, enabled = false)
                add(settingsHint(if (group == null) "New group" else "Editing…"))
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(6))).apply {
                isOpaque = false; border = JBUI.Borders.empty(0, 12, 12, 12)
                add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    isOpaque = false
                    pending.error?.let { error ->
                        add(JBLabel(error).apply {
                            foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                            toolTipText = error
                        }, BorderLayout.NORTH)
                    }
                    add(JBLabel("Projects (optional):"), BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                val members = memberOptions(group, pending)
                memberScroll = object : JBScrollPane(members) {
                    override fun getMinimumSize() = Dimension(0, preferredSize.height)
                    override fun getPreferredSize(): Dimension {
                        // Высота формы следует доступному месту таблицы при изменении Settings.
                        // Длинный список прокручивается внутри, кнопки Add/Save остаются под ним.
                        val room = (this@ProjectGroupsTable.height - JBUI.scale(240)).coerceAtLeast(JBUI.scale(100))
                        return Dimension(JBUI.scale(300), minOf(members.preferredSize.height + JBUI.scale(4), room))
                    }
                }.apply {
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBar.unitIncrement = JBUI.scale(52)
                    accessibleContext.accessibleName = "Group members and fixed tab positions"
                }
                add(requireNotNull(memberScroll), BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    isOpaque = false
                    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        isOpaque = false
                        add(register(JButton(if (group == null) "Add" else "Save").apply {
                            accessibleContext.accessibleName = if (group == null) "Create project group" else "Save project group"
                            putClientProperty("projectGroupControl", "save")
                            addActionListener { submit() }
                        }))
                        add(register(JButton("Cancel").apply {
                            accessibleContext.accessibleName = "Cancel project group editing"
                            addActionListener { cancelInput() }
                        }))
                    }, BorderLayout.NORTH)
                    add(settingsHint("Grouped tabs go on the left on merge or Arrange Tabs by Group · Save with Settings Apply"), BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }
    }
    private fun memberOptions(group: ProjectTabGroup?, pending: Input): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
        data class Member(val key: String, val name: String, val path: String, val closed: Boolean = false)
        val open = projects.map { Member(it.key, it.target.name, it.target.path.toString()) }
        val closedKeys = (state.memberships.filterValues { it == group?.id }.keys + pending.projects) - open.map { it.key }.toSet()
        val members = open + closedKeys.map { Member(it, Path.of(it).fileName?.toString() ?: it, it, true) }
        if (members.isEmpty()) add(settingsHint("No open local projects."))
        members.forEach { member ->
            val include = JBCheckBox(settingsLiteral(member.name + if (member.closed) " · Closed" else ""), member.key in pending.projects).apply {
                isOpaque = false; toolTipText = settingsLiteral(member.path)
                accessibleContext.accessibleName = "Include project ${member.name} · ${member.path}"
                putClientProperty("projectGroupControl", "member/${member.key}")
                addItemListener {
                    if (isSelected) pending.projects += member.key else pending.projects -= member.key
                    editingChanged()
                }
            }
            val position = ComboBox((listOf("Auto") + (1..members.size).map(Int::toString)).toTypedArray()).apply {
                selectedItem = pending.positions[member.key]?.toString() ?: "Auto"
                preferredSize = JBUI.size(88, preferredSize.height)
                accessibleContext.accessibleName = "Tab position for ${member.name} · ${member.path}"
                toolTipText = "Position within this group on merge. Auto keeps the relative order of unpinned members."
                putClientProperty("projectGroupControl", "position/${member.key}")
                addActionListener {
                    (selectedItem as? String)?.toIntOrNull()?.let { pending.positions[member.key] = it }
                        ?: pending.positions.remove(member.key)
                }
            }
            add(JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                isOpaque = false; border = JBUI.Borders.empty(4, 8)
                add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(register(include), BorderLayout.NORTH)
                    add(settingsHint(settingsLiteral(shortProjectPath(member.path))).apply {
                        border = JBUI.Borders.emptyLeft(28)
                        toolTipText = settingsLiteral(member.path)
                    }, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(settingsHint("Position:"))
                    add(registerWhen(position) { include.isSelected })
                }, BorderLayout.EAST)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
    }
    private fun addAppearance(line: JPanel, group: ProjectTabGroup?, enabled: Boolean) {
        val style = group ?: ProjectTabGroup()
        val choice = GroupAppearanceIcons.choice(style.icon)
        val ready = enabled && group != null && choice != null
        val previous = baseline.groups.firstOrNull { it.id == group?.id }
        fun value(button: SettingsValueButton, id: String, allowed: Boolean, pending: Boolean, run: () -> Unit) {
            button.putClientProperty("projectGroupControl", "${group?.id}/$id")
            SettingsPendingChange(button).update(group != null && pending)
            button.addActionListener { if (editingEnabled && allowed) run() }
            line.add(register(button, allowed))
        }
        val iconLabel = choice?.asset?.textIcon?.let { "Text: ${it.text}" }
            ?: choice?.asset?.builder?.contourId?.let { id -> ContourCatalog.entries.firstOrNull { it.id == id }?.title }
            ?: choice?.asset?.label ?: "Loading…"
        val artwork = choice?.let { com.intellij.ide.RecentProjectIconHelper.createIcon(it.asset.tabArtwork() ?: it.asset.bytes, true, 24) }
        value(SettingsValueButton(settingsLiteral(iconLabel), artwork).apply {
            toolTipText = "Group icon · Inherited by all members, including closed and newly assigned projects"
            accessibleContext.accessibleName = "Change icon for group ${group?.name.orEmpty()}"
        }, "icon", ready, previous?.icon != style.icon) { chooseIcon(requireNotNull(group).id) }
        ProjectColorTarget.entries.forEach { target ->
            val original = target == ProjectColorTarget.ICON && choice?.asset?.builder == null && choice?.asset?.textIcon == null
            val automatic = target != ProjectColorTarget.ICON &&
                (if (target == ProjectColorTarget.OUTLINE) style.outlineColor else style.tabColor).isBlank()
            val color = when (target) {
                ProjectColorTarget.ICON -> choice?.color
                ProjectColorTarget.OUTLINE -> parseIconColor(style.outlineColor) ?: choice?.color
                ProjectColorTarget.TAB -> parseIconColor(style.tabColor) ?: choice?.color
            }
            val label = when {
                original -> "Original"
                automatic -> "Automatic"
                else -> color?.let(::colorName) ?: "Custom"
            }
            val changed = when (target) {
                ProjectColorTarget.ICON -> previous?.icon != style.icon
                ProjectColorTarget.OUTLINE -> previous?.outlineColor != style.outlineColor
                ProjectColorTarget.TAB -> previous?.tabColor != style.tabColor
            }
            value(SettingsValueButton(label, color?.let { ColorSwatch(it) } ?: EmptyIcon.create(JBUI.scale(18))).apply {
                toolTipText = if (original) "Original SVG colors are preserved. Choose a built-in or text icon to recolor it."
                    else "Group ${target.title.lowercase()} · Inherited by every member" + if (automatic) " · Matches the group icon" else ""
                accessibleContext.accessibleName = "Change ${target.title.lowercase()} for group ${group?.name.orEmpty()}"
                putClientProperty("preserveGroupIcon", original)
                if (original && editingEnabled) { muteDisabledIcon = false; disabledIcon = icon }
            }, target.name, ready && !original, changed) { chooseColor(requireNotNull(group).id, target) }
        }
    }
}

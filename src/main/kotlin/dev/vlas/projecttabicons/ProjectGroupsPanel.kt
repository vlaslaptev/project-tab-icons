package dev.vlas.projecttabicons

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class ProjectGroupsPanel(
    width: () -> Int,
    // Родитель синхронно обновляет обе таблицы через updateRows(), сохраняя один проход.
    private val changed: () -> Unit,
    showProjects: () -> Unit,
    chooseGroupIcon: (String) -> Unit,
    chooseGroupColor: (String, ProjectColorTarget) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private data class Filter(val id: String?, val title: String) { override fun toString() = title }
    private val filter = ComboBox<Filter>().apply {
        accessibleContext.accessibleName = "Filter projects by group"
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
    }
    private val selectVisible = JBCheckBox("Select visible")
    private val selection = JBLabel("0 selected")
    private val groupHint = JBLabel()
    private val clear = JButton("Clear selection")
    private val ungroup = JButton("Ungroup").apply { toolTipText = "Remove selected projects from their groups" }
    private var allRows = emptyList<ProjectIconSettingsRow>()
    private val selected = linkedSetOf<String>()
    private var baseline = ProjectGroupsState()
    var draft = baseline
        private set
    private var filterId: String? = null
    private var syncingFilter = false
    private var editingEnabled = true
    private var disposed = false
    private val table = ProjectGroupsTable(
        selectedProjects = { selected.toSet() },
        save = ::saveGroup,
        delete = ::deleteGroup,
        assign = ::assignSelected,
        move = ::moveGroup,
        showProjects = { id -> filterId = id; changed(); showProjects() },
        chooseIcon = chooseGroupIcon,
        chooseColor = chooseGroupColor,
        editingChanged = ::refreshControls,
    )
    val modified get() = draft != baseline
    val projectControls: JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        isOpaque = false
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel("Show:").apply { labelFor = filter }); add(filter)
        }, BorderLayout.NORTH)
        add(SettingsRadioRow(selectVisible, listOf(selection, clear, ungroup),
            wrapChoices = true, availableWidth = { width() - JBUI.scale(24) }), BorderLayout.CENTER)
    }

    init {
        isOpaque = false
        add(table, BorderLayout.CENTER)
        add(groupHint, BorderLayout.SOUTH)
        filter.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean) = super.getListCellRendererComponent(list,
                settingsLiteral((value as? Filter)?.title.orEmpty()), index, isSelected, cellHasFocus)
        }
        filter.addActionListener {
            if (!syncingFilter) { filterId = (filter.selectedItem as? Filter)?.id; changed() }
        }
        selectVisible.addItemListener {
            if (!syncingFilter) {
                visibleRows().filter { it.target.path != null }.forEach { row ->
                    if (selectVisible.isSelected) selected += row.key else selected -= row.key
                }
                changed()
            }
        }
        clear.addActionListener { selected.clear(); changed() }
        ungroup.addActionListener {
            if (editingEnabled && !table.isEditing) {
                val keys = selectedRows().map { it.key }.toSet()
                draft = draft.copy(memberships = draft.memberships.filterKeys { it !in keys }).normalized()
                changed()
            }
        }
        refreshTable()
    }

    private fun saveGroup(id: String?, name: String, projectKeys: Set<String>,
                          positions: Map<String, Int>): String? {
        if (!editingEnabled || disposed) return "Group editing is unavailable."
        if (id != null && draft.groups.none { it.id == id }) return "This group no longer exists."
        val error = groupNameError(name) ?: when {
            id == null && draft.groups.size >= 50 -> "You can create up to 50 groups."
            draft.groups.any { it.id != id && it.name.equals(name, true) } -> "A group with this name already exists."
            else -> null
        }
        if (error != null) return error
        val positionsToSave = positions.filterKeys { it in projectKeys }
        if (positionsToSave.values.any { it !in 1..projectKeys.size } || positionsToSave.values.distinct().size != positionsToSave.size)
            return "Use a different position from 1 to ${projectKeys.size} for each member, or choose Auto."
        val group = (if (id == null) ProjectTabGroup(UUID.randomUUID().toString(), name)
            else draft.groups.first { it.id == id }.copy(name = name)).copy(memberPositions = positionsToSave)
        val localKeys = allRows.filter { it.target.path != null && !it.target.project.isDisposed }.map { it.key }.toSet()
        val editableKeys = localKeys + draft.memberships.filterValues { it == group.id }.keys
        val memberships = draft.memberships.filterValues { it != group.id }.toMutableMap()
        projectKeys.filter { it in editableKeys }.forEach { memberships[it] = group.id }
        draft = draft.copy(groups = if (id == null) draft.groups + group else draft.groups.map { if (it.id == id) group else it },
            memberships = memberships).normalized()
        selected.clear()
        changed()
        return null
    }
    private fun deleteGroup(id: String) {
        if (!editingEnabled || disposed) return
        draft = draft.copy(groups = draft.groups.filterNot { it.id == id }, memberships = draft.memberships.filterValues { it != id })
        if (filterId == id) filterId = null
        changed()
    }
    private fun assignSelected(id: String) {
        if (!editingEnabled || disposed || draft.groups.none { it.id == id }) return
        draft = draft.copy(memberships = draft.memberships.toMutableMap().apply { selectedRows().forEach { put(it.key, id) } }).normalized()
        changed()
    }
    private fun moveGroup(id: String, direction: Int) {
        if (!editingEnabled || disposed || table.isEditing) return
        val index = draft.groups.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in draft.groups.indices) return
        val groups = draft.groups.toMutableList()
        java.util.Collections.swap(groups, index, target)
        draft = draft.copy(groups = groups)
        changed()
    }
    private fun refreshTable() {
        GroupAppearanceIcons.touch(draft)
        allRows.forEach { row ->
            val group = draft.groupFor(row.key)
            val saved = baseline.groupFor(row.key)
            row.inherit(group, group?.id != saved?.id || group?.icon != saved?.icon ||
                group?.outlineColor != saved?.outlineColor || group?.tabColor != saved?.tabColor)
        }
        table.showGroups(draft, baseline, allRows)
        syncingFilter = true
        try {
            val choices = listOf(Filter(null, "All projects (${allRows.size})"),
                Filter("", "Ungrouped (${allRows.count { it.key !in draft.memberships }})")) + draft.groups.map { group ->
                Filter(group.id, "${group.name} (${allRows.count { draft.memberships[it.key] == group.id }})")
            }
            filter.model = DefaultComboBoxModel(choices.toTypedArray())
            filter.selectedItem = choices.firstOrNull { it.id == filterId } ?: choices.first()
        } finally { syncingFilter = false }
        refreshControls()
    }
    fun reset(value: ProjectGroupsState) {
        table.cancelInput()
        baseline = value; draft = value; selected.clear(); filterId = null; refreshTable()
    }
    fun saved() { baseline = draft; refreshControls() }
    fun updateGroup(id: String, transform: (ProjectTabGroup) -> ProjectTabGroup) {
        if (!editingEnabled || disposed) return
        val group = draft.groups.firstOrNull { it.id == id } ?: return
        val updated = transform(group)
        val groups = draft.groups.map { if (it.id == id) updated else it }
        require(groups.sumOf { it.icon.svg.length.toLong() } <= MAX_LIBRARY_BYTES * 4L / 3) {
            "Group icons can use up to 16 MiB in total. Choose a smaller SVG."
        }
        draft = draft.copy(groups = groups)
        changed()
    }
    fun updateRows(rows: List<ProjectIconSettingsRow>) {
        allRows = rows
        selected.retainAll(rows.filter { it.target.path != null }.map { it.key }.toSet())
        refreshTable()
    }
    fun visibleRows(): List<ProjectIconSettingsRow> {
        val id = filterId ?: return allRows
        return allRows.filter { if (id.isEmpty()) it.key !in draft.memberships else draft.memberships[it.key] == id }
    }
    private fun selectedRows() = allRows.filter { it.target.path != null && it.key in selected }
    fun selectionControl(row: ProjectIconSettingsRow) = JBCheckBox("", row.key in selected).apply {
        isOpaque = false; isEnabled = row.target.path != null && editingEnabled
        putClientProperty("projectGroupSelectable", row.target.path != null)
        accessibleContext.accessibleName = "Select project ${row.target.name} · ${row.target.path}"
        addItemListener { if (isSelected) selected += row.key else selected -= row.key; refreshControls() }
    }
    fun setEditingEnabled(value: Boolean) { editingEnabled = value; refreshControls() }
    fun disposeDialogs() { disposed = true; table.cancelInput() }
    private fun refreshControls() {
        val rows = selectedRows()
        val visible = visibleRows()
        val visibleKeys = visible.map { it.key }.toSet()
        val hidden = rows.count { it.key !in visibleKeys }
        selection.text = "${rows.size} selected" + if (hidden > 0) " · $hidden hidden" else ""
        selection.toolTipText = "Assign or ungroup selected projects, including those hidden by the filter."
        groupHint.text = if (rows.isEmpty()) "Projects inherit their group's icon and colors, including new members · Save with Apply"
            else "${rows.size} project(s) selected in Projects · Use Assign to change their group"
        groupHint.foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        groupHint.toolTipText = groupHint.text
        val local = visible.filter { it.target.path != null }
        syncingFilter = true
        try { selectVisible.isSelected = local.isNotEmpty() && local.all { it.key in selected } }
        finally { syncingFilter = false }
        selectVisible.isEnabled = editingEnabled && local.isNotEmpty()
        clear.isEnabled = editingEnabled && rows.isNotEmpty()
        ungroup.isEnabled = editingEnabled && !table.isEditing && rows.any { it.key in draft.memberships }
        filter.isEnabled = editingEnabled
        table.setEditingEnabled(editingEnabled, rows.isNotEmpty())
    }
}

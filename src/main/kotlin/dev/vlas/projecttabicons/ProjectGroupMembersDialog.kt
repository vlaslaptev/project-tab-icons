package dev.vlas.projecttabicons

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants

internal data class ProjectGroupMemberOption(val key: String, val name: String, val path: String, val group: String?)

/** Быстрое добавление не затрагивает имя, оформление и существующих участников группы. */
internal class ProjectGroupMembersDialog(
    parent: JComponent,
    groupName: String,
    private var projects: List<ProjectGroupMemberOption>,
) : DialogWrapper(parent, true) {
    private val search = JBTextField().apply {
        emptyText.text = "Search by project name, path or group"
        accessibleContext.accessibleName = "Search projects to add to group"
    }
    private val members = object : JPanel(), Scrollable {
        init { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(52)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
            (visible.height - JBUI.scale(52)).coerceAtLeast(JBUI.scale(52))
    }
    private val selected = linkedSetOf<String>()
    private val selection = settingsHint("")
    private val transfer = settingsHint("")
    private val clear = JButton("Clear selection")
    private val scroll = JBScrollPane(members).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(52)
        accessibleContext.accessibleName = "Open local projects available to add"
    }

    init {
        title = "Add projects to $groupName"
        search.document.addDocumentListener(onTextChanged(::refreshMembers))
        clear.addActionListener { selected.clear(); refreshMembers() }
        init()
        refreshMembers()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
        preferredSize = JBUI.size(520, 360)
        minimumSize = JBUI.size(360, 240)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(JBLabel("Choose open projects to add:"), BorderLayout.NORTH)
            add(search, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(selection, BorderLayout.CENTER)
                add(clear, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(transfer, BorderLayout.CENTER)
            add(settingsHint("Group icon and colors are inherited · Save with Apply"), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = search

    fun updateProjects(groupName: String, values: List<ProjectGroupMemberOption>) {
        title = "Add projects to $groupName"
        if (projects == values) return
        projects = values
        selected.retainAll(values.map { it.key }.toSet())
        refreshMembers()
    }

    fun selectedProjects(): Set<String> = selected.toSet()

    private fun matchingProjects(): List<ProjectGroupMemberOption> {
        val query = search.text.trim()
        return projects.filter { project ->
            query.isEmpty() || listOfNotNull(project.name, project.path, project.group).any { it.contains(query, ignoreCase = true) }
        }
    }

    private fun refreshMembers() {
        val visible = matchingProjects()
        val previousY = scroll.verticalScrollBar.value
        members.removeAll()
        if (visible.isEmpty()) members.add(settingsHint(when {
            projects.isEmpty() -> "No other open local projects. Open a project to add it here."
            else -> "No matching projects. Try another name or path."
        }).apply { border = JBUI.Borders.empty(16, 12) })
        visible.forEach { project ->
            val include = JBCheckBox(settingsLiteral(project.name), project.key in selected).apply {
                isOpaque = false
                toolTipText = settingsLiteral(project.path)
                accessibleContext.accessibleName = "Add ${project.name} · ${project.path}" +
                    (project.group?.let { " · Move from $it" } ?: " · Ungrouped")
                addFocusListener(object : FocusAdapter() {
                    override fun focusGained(event: FocusEvent) {
                        scrollRectToVisible(Rectangle(0, 0, width, height))
                    }
                })
                addItemListener {
                    if (isSelected) selected += project.key else selected -= project.key
                    refreshSelection()
                }
            }
            members.add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 8)
                add(include, BorderLayout.NORTH)
                add(settingsHint(settingsLiteral(shortProjectPath(project.path))).apply {
                    border = JBUI.Borders.emptyLeft(28)
                    toolTipText = settingsLiteral(project.path)
                }, BorderLayout.CENTER)
                add(settingsHint(settingsLiteral(project.group?.let { "Move from $it" } ?: "Ungrouped")).apply {
                    border = JBUI.Borders.emptyLeft(28)
                    toolTipText = text
                }, BorderLayout.SOUTH)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
        refreshSelection()
        members.revalidate()
        members.repaint()
        scroll.verticalScrollBar.value = previousY
    }

    private fun refreshSelection() {
        val visibleKeys = matchingProjects().map { it.key }.toSet()
        val hidden = selected.count { it !in visibleKeys }
        selection.text = "${selected.size} selected" + if (hidden > 0) " · $hidden hidden by search" else ""
        val moving = projects.count { it.key in selected && it.group != null }
        transfer.text = if (moving > 0) "$moving selected project${if (moving == 1) " will" else "s will"} move from another group."
            else "Projects already in this group are excluded."
        clear.isEnabled = selected.isNotEmpty()
        setOKButtonText(if (selected.isEmpty()) "Add projects" else "Add ${selected.size} project${if (selected.size == 1) "" else "s"}")
        isOKActionEnabled = selected.isNotEmpty()
    }
}

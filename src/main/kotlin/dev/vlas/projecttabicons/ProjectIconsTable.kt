package dev.vlas.projecttabicons

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.EmptyIcon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.KeyboardFocusManager
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Строки проектов естественной высоты, доступность действий и сохранение фокуса. */
internal class ProjectIconsTable(
    private val groups: ProjectGroupsPanel,
    private val chooseIcon: (ProjectIconSettingsRow) -> Unit,
    private val chooseColor: (ProjectIconSettingsRow, ProjectColorTarget) -> Unit,
    private val reloadRow: (ProjectIconSettingsRow) -> Unit,
    private val editGroup: (String) -> Unit,
) {
    private val projects = ProjectRowsPanel()
    private val table = JPanel(BorderLayout())
    private val columns = ProjectColumns()
    private val countLabel = JBLabel("Open projects").apply { font = font.deriveFont(Font.BOLD) }
    private data class ProjectAction(val button: SettingsValueButton, val available: Boolean,
                                     val preserveIconWhenDisabled: Boolean)
    private val projectActions = mutableListOf<ProjectAction>()
    private var editingEnabled = true
    private val header: JPanel
    private val columnHeader: JPanel
    val component = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12, 12, 12)
    }

    init {
        columnHeader = JPanel(columns.layout(header = true)).apply {
            isOpaque = false
            listOf("Project", "Group", "Icon", "Icon color", "Outline color", "Tab color", "").forEach { add(settingsHint(it)) }
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }
        table.apply {
            isOpaque = false
            border = JBUI.Borders.customLine(JBColor.border())
            add(columnHeader, BorderLayout.NORTH)
            add(projects, BorderLayout.CENTER)
        }
        header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(countLabel, BorderLayout.NORTH)
            add(groups.projectControls, BorderLayout.CENTER)
        }
        component.add(header, BorderLayout.NORTH)
        component.add(table, BorderLayout.CENTER)
    }

    fun setEditingEnabled(enabled: Boolean, force: Boolean = false) {
        if (!force && editingEnabled == enabled) return
        editingEnabled = enabled
        countLabel.isEnabled = enabled
        groups.setEditingEnabled(enabled)
        fun update(view: Component) {
            if (view !is SettingsValueButton) view.isEnabled = enabled &&
                (view !is JComponent || view.getClientProperty("projectGroupSelectable") != false)
            if (view is Container) view.components.forEach(::update)
        }
        update(projects)
        update(columnHeader)
        projectActions.forEach { action ->
            action.button.isEnabled = enabled && action.available
            action.button.muteDisabledIcon = !enabled || !action.preserveIconWhenDisabled
            action.button.readOnlyValue = enabled && action.preserveIconWhenDisabled
            // Original сохраняет исходный цвет только при включённом плагине.
            action.button.disabledIcon = if (enabled && action.preserveIconWhenDisabled) action.button.icon else null
        }
        table.repaint()
    }

    fun showRows(rows: List<ProjectIconSettingsRow>, appearance: TabAppearanceState) {
        val focusKey = (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JComponent)
            ?.getClientProperty("projectIconAction")
        var restoredFocus: JComponent? = null
        var fallbackFocus: JComponent? = null
        projects.removeAll()
        projectActions.clear()
        groups.updateRows(rows)
        val visibleRows = groups.visibleRows()
        countLabel.text = "Open projects (${rows.size})" + if (visibleRows.size != rows.size) " · ${visibleRows.size} shown" else ""
        visibleRows.forEachIndexed { index, row ->
            val line = ProjectRowPanel(columns.layout())
            val problem = when {
                row.conflicted -> "Changed outside Settings. Reload to continue."
                row.failure != null -> settingsMessage(row.failure.orEmpty())
                else -> null
            }
            val path = row.target.path?.toString() ?: "No local project directory"
            val identity = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBLabel(settingsLiteral(row.target.name)).apply {
                        toolTipText = settingsLiteral(row.target.name)
                    }, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                    isOpaque = false
                    add(settingsHint(problem ?: settingsLiteral(shortProjectPath(path))).apply {
                        if (problem != null) foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                        toolTipText = problem ?: settingsLiteral(path)
                    }, BorderLayout.NORTH)
                    row.inheritedGroup?.let { group ->
                        add(settingsHint("Inherited from ${settingsLiteral(group.name)}").apply {
                            toolTipText = "Icon and colors inherited from ${settingsLiteral(group.name)} · Use Edit group to change them"
                        }, BorderLayout.SOUTH)
                    }
                }, BorderLayout.SOUTH)
                minimumSize = Dimension(0, preferredSize.height)
            }
            line.add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(groups.selectionControl(row), BorderLayout.WEST)
                add(identity, BorderLayout.CENTER)
            })
            line.add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                add(JBLabel(settingsLiteral(row.inheritedGroup?.name ?: "—")).apply {
                    toolTipText = row.inheritedGroup?.let { "Icon and colors inherited from ${settingsLiteral(it.name)}" } ?: "No project group"
                    accessibleContext.accessibleName = "Group for ${row.target.name}: ${row.inheritedGroup?.name ?: "—"}"
                }, BorderLayout.NORTH)
                row.inheritedGroup?.let { group ->
                    add(SettingsValueButton("Edit group").apply {
                        border = JBUI.Borders.empty(0)
                        toolTipText = "Edit ${settingsLiteral(group.name)} in Project groups"
                        accessibleContext.accessibleName = "Edit group ${group.name} for ${row.target.name}"
                        putClientProperty("projectIconAction", "${row.key}/group")
                        if (getClientProperty("projectIconAction") == focusKey) restoredFocus = this
                        projectActions += ProjectAction(this, true, false)
                        addActionListener { if (editingEnabled) editGroup(group.id) }
                    }, BorderLayout.SOUTH)
                }
            })
            fun action(button: SettingsValueButton, name: String, preserveIconWhenDisabled: Boolean = false,
                       run: () -> Unit) {
                projectActions.add(ProjectAction(button, button.isEnabled, preserveIconWhenDisabled))
                button.putClientProperty("projectIconAction", "${row.key}/$name")
                if (name == "icon") SettingsPendingChange(button).update(row.svgModified || row.inheritedModified)
                ProjectColorTarget.entries.firstOrNull { name == it.name.lowercase() + "Color" }?.let {
                    SettingsPendingChange(button).update(row.colorModified(it) || row.inheritedModified)
                }
                if (button.getClientProperty("projectIconAction") == focusKey) restoredFocus = button
                if (name == "icon" && (focusKey as? String)?.startsWith("${row.key}/") == true) fallbackFocus = button
                button.addActionListener { if (editingEnabled) run() }
                line.add(button)
            }
            val iconName = projectSettingsIconName(row)
            val artwork = projectSettingsArtwork(row, appearance)
            action(SettingsValueButton(iconName, artwork).apply {
                isEnabled = row.inheritedGroup == null && row.ready && !row.conflicted
                toolTipText = row.inheritedGroup?.let { "Inherited from ${settingsLiteral(it.name)} · Edit in Project groups" }
                    ?: "Choose icon · ${settingsLiteral(row.activeChoice?.asset?.label ?: iconName)}"
                accessibleContext.accessibleName = row.inheritedGroup?.let {
                    "Inherited icon for ${row.target.name} from ${it.name}"
                } ?: "Change icon for ${row.target.name} · ${row.target.path}"
            }, "icon", preserveIconWhenDisabled = row.inheritedGroup != null) { chooseIcon(row) }
            ProjectColorTarget.entries.forEach { target ->
                val original = target == ProjectColorTarget.ICON && !row.editableIconColor
                val inherited = row.inheritedGroup
                val automatic = !original && (if (inherited == null) row.colorOverrides.value(target) == null else
                    (if (target == ProjectColorTarget.OUTLINE) inherited.outlineColor else inherited.tabColor).isBlank()) &&
                    (target != ProjectColorTarget.ICON || row.automaticIcon)
                val value = row.color(target)
                action(SettingsValueButton(when {
                    original -> "Original"
                    automatic -> "Automatic"
                    else -> value?.let(::colorName) ?: "Custom"
                }, value?.let { ColorSwatch(it) } ?: EmptyIcon.create(JBUI.scale(18))).apply {
                    isEnabled = inherited == null && row.ready && !row.conflicted && !original
                    toolTipText = inherited?.let { "Inherited from ${settingsLiteral(it.name)} · Edit in Project groups" } ?: when {
                        original -> "Original SVG/PNG colors are preserved"
                        automatic -> "${value?.hex() ?: "Automatic"} · " +
                            if (target == ProjectColorTarget.ICON) "Follows the IDE project color" else "Matches the icon color"
                        else -> "${value?.hex() ?: "Custom"} · Change ${target.title.lowercase()}"
                    } + if (target == ProjectColorTarget.TAB) " · Uses Intensity in Style → Fill" else ""
                    accessibleContext.accessibleName = if (inherited != null)
                        "Inherited ${target.title.lowercase()} for ${row.target.name} from ${inherited.name}" else
                        "${if (original) "Original" else "Change"} ${target.title.lowercase()} for ${row.target.name} · ${row.target.path}"
                }, target.name.lowercase() + "Color", preserveIconWhenDisabled = original || inherited != null) { chooseColor(row, target) }
            }
            val reload = row.conflicted || row.failure != null
            action(SettingsValueButton("", if (reload) AllIcons.Actions.Refresh else AllIcons.Actions.Rollback).apply {
                isEnabled = row.modified || reload
                hideWhenDisabled = true
                toolTipText = if (reload) "Reload project icon" else "Revert this project's pending changes"
                accessibleContext.accessibleName = "${if (reload) "Reload" else "Revert"} changes for ${row.target.name} · ${row.target.path}"
            }, "revert") { reloadRow(row) }
            line.trackHover()
            projects.add(line, GridBagConstraints().apply {
                gridx = 0; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
        }
        if (visibleRows.isEmpty()) projects.add(settingsHint(if (rows.isEmpty()) "Open a project to assign its icon and color." else "No open projects in this group.").apply {
            border = JBUI.Borders.empty(20, 12)
        }, GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = GridBagConstraints.NORTHWEST })
        projects.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = rows.size + 1; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
        })
        setEditingEnabled(appearance.enabled, force = true)
        projects.revalidate()
        projects.repaint()
        component.revalidate()
        SwingUtilities.invokeLater {
            if (projects.isShowing) {
                (restoredFocus?.takeIf { it.isEnabled } ?: fallbackFocus?.takeIf { it.isEnabled })?.requestFocusInWindow()
            }
        }
    }
}

internal fun shortProjectPath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
    return if (parts.size > 3) "…/" + parts.takeLast(3).joinToString("/") else path
}

package dev.vlas.projecttabicons

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import javax.swing.JComponent

private fun supportsProjectTabs(): Boolean = SystemInfoRt.isMac || SystemInfoRt.isWindows

class ProjectTabActionsGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabledAndVisible = supportsProjectTabs()
    }
}

abstract class SupportedProjectTabAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = supportsProjectTabs()
    }
    final override fun actionPerformed(event: AnActionEvent) {
        if (supportsProjectTabs()) perform(event)
    }
    protected abstract fun perform(event: AnActionEvent)
}

/** Быстрые действия используют тот же редактор и атомарное сохранение, что и Settings. */
abstract class ProjectTabQuickAction(private val mode: String) : SupportedProjectTabAction() {
    override fun update(event: AnActionEvent) {
        super.update(event)
        if (!event.presentation.isVisible) return
        val project = event.project
        val path = project?.takeUnless { it.isDisposed || it.isDefault }
            ?.let { RecentProjectsManagerBase.getInstanceEx().getProjectPath(it) }
        event.presentation.isEnabledAndVisible = path != null
        if (event.presentation.isEnabled) event.presentation.isEnabled = ApplicationManager.getApplication()
            .getService(TabAppearanceSettings::class.java).state.enabled
        val group = path?.let { ApplicationManager.getApplication().getService(ProjectGroups::class.java)
            .resolvedState.groupFor(projectIconPathKey(it)) }
        if (group != null) {
            event.presentation.isEnabled = false
            event.presentation.description = "Inherited from ${group.name}. Edit its appearance in Project groups."
        } else event.presentation.description = "Edit this project's own icon or color"
    }
    override fun perform(event: AnActionEvent) {
        val project = event.project ?: return
        val parent = WindowManager.getInstance().getFrame(project)?.rootPane ?: return
        val target = openProjectIconTargets().firstOrNull { it.project === project && it.path != null } ?: return
        val path = target.path ?: return
        val app = ApplicationManager.getApplication()
        if (app.getService(ProjectGroups::class.java).resolvedState.groupFor(projectIconPathKey(path)) != null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, IconSettingsConfigurable::class.java)
            return
        }
        var loaded: LoadedProjectIcon? = null
        var sets = emptyList<ImportedIconSet>()
        var gallery = emptyList<IconChoice>()
        if (!quickWork(project, parent, "Loading project icon") {
                loaded = loadProjectIcon(target, !JBColor.isBright())
                if (mode == "icon") {
                    sets = app.getService(IconSetLibrary::class.java).readSets()
                    gallery = ProjectIconStore.gallery(listOf(ProjectIconSource(path, target.name, loaded.snapshot)))
                        .assets.map { asset -> IconChoice(asset, try { svgIconColor(asset) }
                            catch (error: ProcessCanceledException) { throw error }
                            catch (_: Exception) { null }) }
                }
            }) return
        if (project.isDisposed) return
        val colorStore = app.getService(ProjectOutlineColors::class.java)
        val row = ProjectIconSettingsRow(loaded ?: return, colorStore.overrides(path))
        if (!row.ready) { Messages.showErrorDialog(project, row.failure ?: "Cannot load this project icon.", "Project Tab Icons"); return }
        var additions = emptyList<ImportedIconSet>()
        while (!project.isDisposed) {
            if (mode == "icon") {
                val dialog = IconSelectionDialog(parent, project, project.name, row.color ?: COLOR_PRESETS.first().bright,
                    row.activeChoice, gallery, (sets + additions).associateBy { it.id }.values.toList(), immediate = true)
                if (!dialog.showAndGet() || project.isDisposed) return
                dialog.selection()?.let(row::choose) ?: return
                additions += dialog.importedSets()
            } else {
                val channel = ProjectColorTarget.valueOf(mode)
                if (channel == ProjectColorTarget.ICON && !row.editableIconColor) {
                    Messages.showInfoMessage(project, "This SVG or PNG keeps its original colors. Choose a built-in or text icon to edit its color.", "Project Tab Icons")
                    return
                }
                val dialog = ProjectColorDialog(parent, project, row, channel, app.getService(TabAppearanceSettings::class.java).state, immediate = true)
                if (!dialog.showAndGet() || project.isDisposed) return
                row.changeColor(channel, dialog.selection())
            }
            if (!row.modified && additions.isEmpty()) return
            var installed: IconSnapshot? = null
            var library: IconSetLibraryState? = null
            var colorConflict = false
            val success = quickWork(project, parent, "Saving project icon", failed = {
                colorConflict = it is ProjectColorConflictException && it.suppressed.isEmpty()
            }) {
                check(!project.isDisposed) { "The project has been closed." }
                if (additions.isNotEmpty()) library = app.getService(IconSetLibrary::class.java).prepare(additions)
                val snapshot = ProjectIconStore.snapshot(path)
                check(snapshot.fingerprint == row.snapshot?.fingerprint && snapshot.directory == row.snapshot?.directory) {
                    "The project icon changed while the editor was open. Open the action again."
                }
                val writes = if (row.svgModified) listOf(ProjectIconWrite(project, path, row.snapshot!!, row.activeChoice!!.asset))
                    else emptyList()
                installed = ProjectIconStore.installAll(writes) { colorStore.apply(mapOf(path to row.colorChanges())) }[path]
            }
            if (!success) {
                row.snapshot?.let { ProjectIconStore.refreshUi(project, path, it) }
                if (colorConflict && !project.isDisposed) {
                    // Сообщение уже показано. Выбор не теряется; повторное применение требует нового OK.
                    row.reviewColorConflict(colorStore.overrides(path))
                    continue
                }
                return
            }
            library?.let { app.getService(IconSetLibrary::class.java).apply(it) }
            installed?.let { ProjectIconStore.refreshUi(project, path, it) }
            app.getService(ProjectTabIconsService::class.java).requestRefresh()
            return
        }
    }
}
class ChangeProjectTabIconAction : ProjectTabQuickAction("icon")
class ChangeProjectTabIconColorAction : ProjectTabQuickAction("ICON")
class ChangeProjectTabOutlineColorAction : ProjectTabQuickAction("OUTLINE")
class ChangeProjectTabFillColorAction : ProjectTabQuickAction("TAB")

private fun quickWork(project: Project, parent: JComponent, title: String,
                      failed: (Exception) -> Unit = {}, work: () -> Unit): Boolean {
    var failure: Exception? = null
    val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(Runnable {
        try { work() }
        catch (error: ProcessCanceledException) { throw error }
        catch (error: Exception) { failure = error }
    }, title, false, project, parent)
    if (!completed || project.isDisposed) return false
    failure?.let {
        failed(it)
        if (it.suppressed.isNotEmpty()) com.intellij.openapi.diagnostic.Logger.getInstance(ProjectTabQuickAction::class.java)
            .warn("Cannot save project icons; rollback was incomplete", it)
        val recovery = if (it.suppressed.isEmpty()) "" else " Some previous icon variants could not be restored. See the IDE log for details."
        Messages.showErrorDialog(parent, (it.message ?: "Cannot update project tabs.") + recovery, "Project Tab Icons")
        return false
    }
    return true
}

private data class AppearanceChoice(val title: String, val state: TabAppearanceState) { override fun toString() = title }
private fun appearanceChoices(): List<AppearanceChoice> {
    val app = ApplicationManager.getApplication()
    val current = app.getService(TabAppearanceSettings::class.java).state
    val enabled = current.enabled
    return TabAppearancePreset.entries.map { preset ->
        AppearanceChoice(preset.title, if (preset == TabAppearancePreset.CUSTOM)
            (app.getService(LastCustomAppearanceSettings::class.java).state.appearance ?: TabAppearanceState.defaults().asCustomAppearance()).copy(enabled = enabled)
            else TabAppearanceState.forPreset(preset, enabled).withGroupLayoutFrom(current))
    } + app.getService(UserAppearancePresets::class.java).state.presets.map {
        AppearanceChoice(it.name, it.appearance.copy(enabled = enabled, userPresetId = it.id))
    }
}
private fun applyAppearance(choice: AppearanceChoice) {
    val app = ApplicationManager.getApplication()
    val settings = app.getService(TabAppearanceSettings::class.java)
    if (settings.state.preset == TabAppearancePreset.CUSTOM && settings.state.userPresetId == null)
        app.getService(LastCustomAppearanceSettings::class.java).remember(settings.state)
    settings.apply(choice.state)
}
class ChooseProjectTabPresetAction : SupportedProjectTabAction() {
    override fun perform(event: AnActionEvent) {
        val choices = appearanceChoices()
        val current = ApplicationManager.getApplication().getService(TabAppearanceSettings::class.java).state
        val selected = choices.firstOrNull { it.state.userPresetId == current.userPresetId && it.state.preset == current.preset }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setSelectedValue(selected ?: choices.first(), true)
            .setTitle("Project Tab Icons · Appearance preset")
            .setItemChosenCallback { applyAppearance(it) }.createPopup().showInFocusCenter()
    }
}
class NextProjectTabPresetAction : SupportedProjectTabAction() {
    override fun perform(event: AnActionEvent) {
        val current = ApplicationManager.getApplication().getService(TabAppearanceSettings::class.java).state
        val choices = appearanceChoices()
        val index = choices.indexOfFirst { it.state.userPresetId == current.userPresetId && it.state.preset == current.preset }
        applyAppearance(choices[(index + 1) % choices.size])
    }
}
class OpenProjectTabSettingsAction : SupportedProjectTabAction() {
    override fun perform(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(event.project, IconSettingsConfigurable::class.java)
    }
}

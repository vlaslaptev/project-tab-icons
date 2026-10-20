package dev.vlas.projecttabicons

import com.intellij.ide.DataManager
import com.intellij.ide.ProjectColorChangeListener
import com.intellij.ide.ProjectWindowCustomizerService
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/** Контекст текущего окна нужен диалогам; таблица содержит все открытые проекты. */
class IconSettingsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll, Configurable.NoMargin {
    private var panel: IconSettingsPanel? = null
    private var lifetime: Disposable? = null
    private var worker: Future<*>? = null
    private var generation = 0
    @Volatile private var disposed = true
    @Volatile private var iconDirectories = emptySet<String>()
    private var applying = false
    private val reloadQueued = AtomicBoolean()
    private val models = linkedMapOf<String, ProjectIconSettingsRow>()
    private var gallery = emptyList<IconChoice>()
    private var iconSets = emptyList<ImportedIconSet>()
    private var pendingIconSets = emptyList<ImportedIconSet>()
    private val dialogs = linkedMapOf<DialogWrapper, String>()

    override fun getDisplayName(): String = "Project Tab Icons"

    override fun createComponent(): JComponent {
        panel?.let { return it.component }
        disposed = false
        val scope = Disposer.newDisposable("Project Tab Icons settings")
        lifetime = scope
        val view = IconSettingsPanel(::chooseIcon, ::chooseColor, ::reloadRow, ::refreshSettingsState,
            ::importStyles, ::exportStyles, ::chooseGroupIcon, ::chooseGroupColor)
        panel = view
        Disposer.register(scope, Disposable { worker?.cancel(true) })
        val bus = ApplicationManager.getApplication().messageBus.connect(scope)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && panel === view) view.refreshPreviewTheme()
            }, ModalityState.any())
            scheduleReload()
        })
        bus.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectOpened(project: Project) = scheduleReload()
            override fun projectClosed(project: Project) = scheduleReload()
        })
        bus.subscribe(ProjectWindowCustomizerService.PROJECT_COLOR_CHANGE_TOPIC, object : ProjectColorChangeListener {
            override fun projectColorChanged(projectPath: String) = scheduleReload()
        })
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { event ->
                    val path = event.path.replace('\\', '/')
                    path.substringAfterLast('/') in PROJECT_ICON_FILES &&
                        (path.contains("/.idea/") || path.substringBeforeLast('/') in iconDirectories)
                }) scheduleReload()
            }
        })
        reset()
        return view.component
    }

    private fun scheduleReload() {
        if (!reloadQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            reloadQueued.set(false)
            if (!disposed && !project.isDisposed) reload(keepDraft = true)
        }, ModalityState.any())
    }

    override fun getPreferredFocusedComponent(): JComponent? = panel?.preferredFocus
    override fun isModified(): Boolean = panel?.modified == true || pendingIconSets.isNotEmpty()

    private fun refreshSettingsState() {
        val view = panel ?: return
        if (disposed) return
        // Модальный пикер не посылает события ввода в Settings; обновляем Apply явно.
        val context = DataManager.getInstance().getDataContext(view.component)
        Settings.KEY.getData(context)?.revalidate()
    }

    override fun reset() {
        pendingIconSets = emptyList()
        panel?.showImportedCategories(0)
        panel?.resetAppearance()
        reload(keepDraft = false)
    }

    private fun reload(keepDraft: Boolean) {
        val view = panel ?: return
        if (applying) return
        val scope = lifetime ?: return
        val targets = openProjectIconTargets()
        val keys = targets.map { it.key }.toSet()
        val groupKeys = view.projectGroups().groups.map { "group:${it.id}" }.toSet()
        dialogs.filterValues { it != "styles" && it !in keys && it !in groupKeys }.keys.toList().forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val dark = !JBColor.isBright()
        val token = ++generation
        val groupDraft = view.projectGroups()
        worker?.cancel(true)
        if (!keepDraft || models.isEmpty()) view.loading()
        worker = ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                ContourCatalog.entries
                ContourCatalog.licenses
                GroupAppearanceIcons.prepare(groupDraft)
                val previewArtwork = WindowTabsPreview.loadArtwork()
                val loaded = targets.map { loadProjectIcon(it, dark) }
                val sources = loaded.mapNotNull { row ->
                    row.target.path?.let { ProjectIconSource(it, row.target.name, row.snapshot) }
                }
                val knownColors = loaded.mapNotNull { it.choice }.associate { it.asset.digest to it.color }
                val assets = ProjectIconStore.gallery(sources).assets.map { asset ->
                    val color = if (knownColors.containsKey(asset.digest)) knownColors[asset.digest] else try {
                        svgIconColor(asset)
                    } catch (error: ProcessCanceledException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    IconChoice(asset, color)
                }
                val sets = ApplicationManager.getApplication().getService(IconSetLibrary::class.java).readSets()
                Result.success(LoadedOpenProjects(loaded, assets, previewArtwork, sets))
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || token != generation || lifetime !== scope || project.isDisposed) return@invokeLater
                result.fold(onSuccess = { loaded ->
                    val colors = ApplicationManager.getApplication().getService(ProjectOutlineColors::class.java)
                    val active = loaded.rows.filterNot { it.target.project.isDisposed }.map { row ->
                        // DeferredIcon может ещё показывать EmptyIcon. Цвет буквенного аватара
                        // получаем из того же штатного источника на EDT, независимо от paintIcon.
                        if (row.color == null && row.snapshot?.files?.isEmpty() == true) {
                            row.copy(color = row.target.path?.let {
                                ProjectWindowCustomizerService.getInstance().getRecentProjectIconColor(it).first
                            })
                        } else row
                    }
                    models.keys.retainAll(active.map { it.target.key }.toSet())
                    active.forEach { row ->
                        val overrides = row.target.path?.let(colors::overrides) ?: ProjectColorOverrides()
                        val existing = models[row.target.key]
                        if (existing == null) models[row.target.key] = ProjectIconSettingsRow(row, overrides)
                        else existing.reload(row, overrides, keepDraft)
                    }
                    iconDirectories = active.mapNotNull { it.snapshot?.directory?.toString()?.replace('\\', '/') }.toSet()
                    gallery = loaded.gallery
                    iconSets = loaded.iconSets
                    view.loaded(active.mapNotNull { models[it.target.key] }, loaded.previewArtwork)
                }, onFailure = { error ->
                    view.failed(error.message ?: "Cannot load open project icons.")
                })
            }, ModalityState.any())
        }
    }

    private fun stillOpen(row: ProjectIconSettingsRow): Boolean =
        !disposed && !row.target.project.isDisposed && models[row.key] === row &&
            ProjectManager.getInstance().openProjects.any { it === row.target.project }

    private fun chooseIcon(row: ProjectIconSettingsRow) {
        val view = panel ?: return
        if (!stillOpen(row) || row.inheritedGroup != null || !row.ready || row.conflicted) return
        val initialColor = row.builder?.colorHex?.let(::parseIconColor) ?: row.color ?: COLOR_PRESETS.first().bright
        val expected = row.snapshot
        val dialog = IconSelectionDialog(view.component, project, row.target.name, initialColor,
            row.activeChoice, gallery + models.values.mapNotNull { it.activeChoice }, effectiveIconSets())
        dialogs[dialog] = row.key
        try {
            if (dialog.showAndGet() && stillOpen(row) && row.inheritedGroup == null) {
                dialog.selection()?.let(row::choose)
                pendingIconSets = pendingIconSets + dialog.importedSets()
                view.showImportedCategories(pendingIconSets.size)
                if (row.snapshot?.fingerprint != expected?.fingerprint || row.snapshot?.directory != expected?.directory) {
                    row.markConflict()
                }
                view.refreshRows()
            }
        } finally {
            dialogs.remove(dialog)
        }
    }

    private fun chooseColor(row: ProjectIconSettingsRow, target: ProjectColorTarget) {
        val view = panel ?: return
        if (!stillOpen(row) || row.inheritedGroup != null || !row.ready || row.conflicted) return
        val expected = row.snapshot
        if (target == ProjectColorTarget.ICON && !row.editableIconColor) return
        val dialog = ProjectColorDialog(view.component, project, row, target, view.appearance())
        dialogs[dialog] = row.key
        try {
            if (dialog.showAndGet() && stillOpen(row) && row.inheritedGroup == null) {
                if (row.snapshot?.fingerprint != expected?.fingerprint || row.snapshot?.directory != expected?.directory) {
                    row.markConflict()
                } else row.changeColor(target, dialog.selection())
                view.refreshRows()
            }
        } finally {
            dialogs.remove(dialog)
        }
    }

    private fun transferWork(title: String, action: () -> Unit): Boolean {
        val view = panel ?: return false
        var failure: Exception? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(Runnable {
            try { action() } catch (error: Exception) { failure = error }
        }, title, false, project, view.component)
        failure?.let {
            LOG.warn(title, it)
            com.intellij.openapi.ui.Messages.showErrorDialog(view.component, it.message ?: "Cannot transfer styles.", title)
            return false
        }
        return !disposed && !project.isDisposed
    }

    private fun importStyles() {
        val view = panel ?: return
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Project Tab Icons styles")
        val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, view.component, project, null) ?: return
        var bundle: StyleBundle? = null
        if (!transferWork("Reading style bundle") { bundle = readStyleBundle(file.toNioPath()) }) return
        val dialog = StyleBundleDialog(view.component, bundle ?: return, true)
        dialogs[dialog] = "styles"
        try {
            if (!dialog.showAndGet() || disposed) return
            val selected = dialog.selected()
            val conflict = dialog.conflict()
            var merged: StyleBundle? = null
            val current = StyleBundle(view.userPresets(), effectiveIconSets())
            if (!transferWork("Preparing imported styles") {
                val result = mergeStyleBundle(current, selected, conflict)
                ApplicationManager.getApplication().getService(IconSetLibrary::class.java).prepare(result.categories)
                merged = result
            }) return
            val result = merged ?: return
            // Обе коллекции меняются вместе только после полной проверки размеров и SVG.
            pendingIconSets = result.categories.filter { incoming -> iconSets.none { it === incoming } }
            view.replaceUserPresets(result.presets)
            view.showImportedCategories(pendingIconSets.size)
        } finally { dialogs.remove(dialog) }
    }

    private fun exportStyles() {
        val view = panel ?: return
        val sets = effectiveIconSets().toMutableList()
        val textIcons = models.values.mapNotNull { it.activeChoice }.filter { it.asset.textIcon != null }
            .distinctBy { it.asset.digest }.filter { icon -> sets.none { set -> set.icons.any { it.asset.digest == icon.asset.digest } } }
        if (textIcons.isNotEmpty()) {
            var name = "Text icons"
            var suffix = 2
            while (sets.any { it.name.equals(name, true) }) name = "Text icons (${suffix++})"
            sets += ImportedIconSet(java.util.UUID.randomUUID().toString(), name, textIcons.map { icon ->
                icon.copy(asset = icon.asset.copy(label = "Text ${icon.asset.textIcon?.text.orEmpty()} · $name"))
            })
        }
        val bundle = StyleBundle(view.userPresets(), sets)
        if (bundle.empty) {
            com.intellij.openapi.ui.Messages.showInfoMessage(view.component,
                "Save a user preset, import an SVG category or create a text icon first.", "Export styles")
            return
        }
        val dialog = StyleBundleDialog(view.component, bundle, false)
        dialogs[dialog] = "styles"
        try {
            if (!dialog.showAndGet() || disposed) return
            val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor("Export Project Tab Icons styles",
                "Choose a location for the portable style bundle.", "json")
            val file = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project).save(null as com.intellij.openapi.vfs.VirtualFile?, "project-tab-styles.json")?.file ?: return
            if (file.exists() && com.intellij.openapi.ui.Messages.showYesNoDialog(view.component,
                    "Replace ${file.name}?", "Export styles", com.intellij.openapi.ui.Messages.getQuestionIcon()) != com.intellij.openapi.ui.Messages.YES) return
            val selected = dialog.selected()
            transferWork("Exporting style bundle") { writeStyleBundle(file.toPath(), selected) }
        } finally { dialogs.remove(dialog) }
    }

    private fun effectiveIconSets(): List<ImportedIconSet> = (iconSets + pendingIconSets).associateBy { it.id }.values.toList()

    private fun chooseGroupIcon(id: String) {
        val view = panel ?: return
        val group = view.projectGroups().groups.firstOrNull { it.id == id } ?: return
        val choice = GroupAppearanceIcons.choice(group.icon) ?: return
        val dialog = IconSelectionDialog(view.component, project, "Group: ${group.name}",
            choice.color ?: COLOR_PRESETS.first().bright, choice,
            gallery + models.values.mapNotNull { it.activeChoice }, effectiveIconSets())
        dialogs[dialog] = "group:$id"
        try {
            if (!dialog.showAndGet() || disposed || project.isDisposed) return
            val selected = dialog.selection() ?: return
            try {
                val stored = GroupAppearanceIcons.store(selected)
                view.updateGroup(id) { it.copy(icon = stored) }
                pendingIconSets += dialog.importedSets()
                view.showImportedCategories(pendingIconSets.size)
            } catch (error: IllegalArgumentException) {
                com.intellij.openapi.ui.Messages.showErrorDialog(view.component, error.message ?: "Cannot save the group icon.", "Project Tab Icons")
            }
        } finally { dialogs.remove(dialog) }
    }

    private fun chooseGroupColor(id: String, target: ProjectColorTarget) {
        val view = panel ?: return
        val group = view.projectGroups().groups.firstOrNull { it.id == id } ?: return
        val choice = GroupAppearanceIcons.choice(group.icon) ?: return
        // Редактор цвета получает самостоятельную модель группы, а не первый проект.
        val row = ProjectIconSettingsRow(LoadedProjectIcon(
            OpenProjectIconTarget(project, null, null, displayName = group.name), null, choice, choice.color), group.colors(choice))
        if (target == ProjectColorTarget.ICON && !row.editableIconColor) return
        val dialog = ProjectColorDialog(view.component, project, row, target, view.appearance(),
            label = "${target.title} · Group: ${group.name}", allowAutomatic = target != ProjectColorTarget.ICON)
        dialogs[dialog] = "group:$id"
        try {
            if (!dialog.showAndGet() || disposed || project.isDisposed) return
            val color = dialog.selection()
            try {
                val storedIcon = if (target == ProjectColorTarget.ICON) {
                    row.changeColor(target, color)
                    GroupAppearanceIcons.store(requireNotNull(row.ownChoice))
                } else null
                view.updateGroup(id) { current -> when (target) {
                    ProjectColorTarget.ICON -> current.copy(icon = requireNotNull(storedIcon))
                    ProjectColorTarget.OUTLINE -> current.copy(outlineColor = color?.hex().orEmpty())
                    ProjectColorTarget.TAB -> current.copy(tabColor = color?.hex().orEmpty())
                } }
            } catch (error: IllegalArgumentException) {
                com.intellij.openapi.ui.Messages.showErrorDialog(view.component, error.message ?: "Cannot save the group color.", "Project Tab Icons")
            }
        } finally { dialogs.remove(dialog) }
    }

    private fun reloadRow(row: ProjectIconSettingsRow) {
        if (!stillOpen(row)) return
        row.discardDraft()
        panel?.refreshRows()
        reload(keepDraft = true)
    }

    override fun apply() {
        val view = panel ?: return
        if (!isModified()) return
        val pending = view.rows.filter { it.modified }
        pending.forEach { row ->
            if (!stillOpen(row)) throw ConfigurationException("${row.target.name} has been closed.")
            if (row.conflicted) throw ConfigurationException("Reload the changed icon for ${row.target.name} before applying.")
            if (!row.ready) throw ConfigurationException("Wait for ${row.target.name}'s icon to load.")
        }
        val writes = pending.filter { it.svgModified }.map { row ->
            ProjectIconWrite(row.target.project,
                row.target.path ?: throw ConfigurationException("A local project directory is required."),
                row.snapshot ?: throw ConfigurationException("Wait for the project icons to load."),
                row.ownChoice?.asset ?: throw ConfigurationException("Choose a project icon."))
        }
        val colors = pending.filter { it.colorModified }.associate { row ->
            (row.target.path ?: throw ConfigurationException("A local project directory is required.")) to row.colorOverrides
        }
        generation++
        worker?.cancel(true)
        applying = true
        try {
            var installed: Map<Path, IconSnapshot> = emptyMap()
            var failure: Exception? = null
            var preparedLibrary: IconSetLibraryState? = null
            if (writes.isNotEmpty() || pendingIconSets.isNotEmpty()) ProgressManager.getInstance().runProcessWithProgressSynchronously(Runnable {
                try {
                    if (pendingIconSets.isNotEmpty()) preparedLibrary = ApplicationManager.getApplication()
                        .getService(IconSetLibrary::class.java).prepare(pendingIconSets)
                    installed = ProjectIconStore.installAll(writes)
                } catch (error: Exception) {
                    failure = error
                }
            }, "Saving project icons", false, project, view.component)
            failure?.let { error ->
                writes.forEach { ProjectIconStore.refreshUi(it.project, it.path, it.expected) }
                LOG.warn("Cannot save project SVG icons", error)
                val recovery = if (error.suppressed.isEmpty()) "" else
                    " Some previous icon variants could not be restored. See the IDE log for details."
                throw ConfigurationException((error.message ?: "Cannot save the project icons.") + recovery)
            }
            val app = ApplicationManager.getApplication()
            app.getService(ProjectOutlineColors::class.java).apply(colors)
            app.getService(TabAppearanceSettings::class.java).apply(view.appearance())
            app.getService(LastCustomAppearanceSettings::class.java).remember(view.customAppearance())
            app.getService(UserAppearancePresets::class.java).apply(view.userPresets())
            app.getService(ProjectGroups::class.java).apply(view.projectGroups())
            preparedLibrary?.let { app.getService(IconSetLibrary::class.java).apply(it) }
            iconSets = effectiveIconSets()
            pendingIconSets = emptyList()
            view.showImportedCategories(0)
            pending.forEach { row -> row.saved(row.target.path?.let(installed::get)) }
            writes.forEach { write -> installed[write.path]?.let { ProjectIconStore.refreshUi(write.project, write.path, it) } }
            app.getService(ProjectTabIconsService::class.java).requestRefresh()
            view.saved()
        } finally {
            applying = false
            // В том числе перепроверяем файлы после успешного или частичного восстановления.
            reload(keepDraft = true)
        }
    }

    override fun disposeUIResources() {
        disposed = true
        generation++
        worker?.cancel(true)
        dialogs.keys.toList().forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        dialogs.clear()
        panel?.disposeDialogs()
        lifetime?.let(Disposer::dispose)
        lifetime = null
        panel = null
        worker = null
        models.clear()
        iconDirectories = emptySet()
        gallery = emptyList()
        iconSets = emptyList()
        pendingIconSets = emptyList()
    }

    companion object {
        private val LOG = Logger.getInstance(IconSettingsConfigurable::class.java)
    }
}

private data class LoadedOpenProjects(val rows: List<LoadedProjectIcon>, val gallery: List<IconChoice>,
                                      val previewArtwork: PreviewArtwork, val iconSets: List<ImportedIconSet>)

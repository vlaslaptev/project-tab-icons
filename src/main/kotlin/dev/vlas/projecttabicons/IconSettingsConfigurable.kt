package dev.vlas.projecttabicons

import com.intellij.ide.DataManager
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ide.RecentProjectsManagerBase
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
import com.intellij.ui.DeferredIconImpl
import com.intellij.ui.JBColor
import com.intellij.util.ui.AvatarIcon
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
    private val dialogs = linkedMapOf<DialogWrapper, String>()

    override fun getDisplayName(): String = "Project Tab Icons"

    override fun createComponent(): JComponent {
        panel?.let { return it.component }
        disposed = false
        val scope = Disposer.newDisposable("Project Tab Icons settings")
        lifetime = scope
        val view = IconSettingsPanel(::chooseIcon, ::chooseColor, ::reloadRow, ::refreshSettingsState)
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
    override fun isModified(): Boolean = panel?.modified == true

    private fun refreshSettingsState() {
        val view = panel ?: return
        if (disposed) return
        // Модальный пикер не посылает события ввода в Settings; обновляем Apply явно.
        val context = DataManager.getInstance().getDataContext(view.component)
        Settings.KEY.getData(context)?.revalidate()
    }

    override fun reset() {
        panel?.resetAppearance()
        reload(keepDraft = false)
    }

    private fun openTargets(): List<OpenProjectIconTarget> {
        val recent = RecentProjectsManagerBase.getInstanceEx()
        return ProjectManager.getInstance().openProjects.filterNot { it.isDisposed || it.isDefault }.map { target ->
            val path = recent.getProjectPath(target)
            val delegate = path?.let { recent.getProjectIcon(it, true, 28, target.name) }
            val resolved = if (delegate is DeferredIconImpl<*>) delegate.currentlyPaintedIcon() else delegate
            OpenProjectIconTarget(target, path, delegate?.let { CenteredProjectIcon(it, 24, projectName = { target.name }) },
                (resolved as? AvatarIcon)?.presentation?.color1)
        }
    }

    private fun reload(keepDraft: Boolean) {
        val view = panel ?: return
        if (applying) return
        val scope = lifetime ?: return
        val targets = openTargets()
        val keys = targets.map { it.key }.toSet()
        dialogs.filterValues { it !in keys }.keys.toList().forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val dark = !JBColor.isBright()
        val token = ++generation
        worker?.cancel(true)
        if (!keepDraft || models.isEmpty()) view.loading()
        worker = ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                ContourCatalog.entries
                ContourCatalog.licenses
                val previewArtwork = WindowTabsPreview.loadArtwork()
                val loaded = targets.map { loadProject(it, dark) }
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
                Result.success(LoadedOpenProjects(loaded, assets, previewArtwork))
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
                    view.loaded(active.mapNotNull { models[it.target.key] }, loaded.previewArtwork)
                }, onFailure = { error ->
                    view.failed(error.message ?: "Cannot load open project icons.")
                })
            }, ModalityState.any())
        }
    }

    private fun loadProject(target: OpenProjectIconTarget, dark: Boolean): LoadedProjectIcon {
        val path = target.path ?: return LoadedProjectIcon(target, null, null, null,
            "A local project directory is required to save an icon.")
        return try {
            val snapshot = ProjectIconStore.snapshot(path)
            val svg = try {
                snapshot.currentSvg(dark)
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: org.xml.sax.SAXException) {
                null
            }
            val file = snapshot.currentFile(dark)
            val color = try {
                when {
                    svg != null -> svgIconColor(svg)
                    file?.endsWith(".png") == true -> sampleIconColor(snapshot.files.getValue(file), false)
                    file == null -> target.automaticColor
                    else -> null
                }
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // PNG тоже получает подготовленную копию: renderer больше не рисует delegate.
            val prepared = if (svg == null && file?.endsWith(".png") == true) {
                val image = RecentProjectIconHelper.createIcon(snapshot.files.getValue(file), false, 24)
                target.copy(preview = (target.preview as? CenteredProjectIcon)?.withArtwork(image) ?: image)
            } else target
            LoadedProjectIcon(prepared, snapshot, svg?.let { IconChoice(it, color) }, color)
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Exception) {
            LoadedProjectIcon(target, null, null, null, error.message ?: "Cannot read this project's icon.")
        }
    }

    private fun stillOpen(row: ProjectIconSettingsRow): Boolean =
        !disposed && !row.target.project.isDisposed && models[row.key] === row &&
            ProjectManager.getInstance().openProjects.any { it === row.target.project }

    private fun chooseIcon(row: ProjectIconSettingsRow) {
        val view = panel ?: return
        if (!stillOpen(row) || !row.ready || row.conflicted) return
        val initialColor = row.builder?.colorHex?.let(::parseIconColor) ?: row.color ?: COLOR_PRESETS.first().bright
        val expected = row.snapshot
        val dialog = IconSelectionDialog(view.component, project, row.target.name, initialColor,
            row.activeChoice, gallery + models.values.mapNotNull { it.activeChoice })
        dialogs[dialog] = row.key
        try {
            if (dialog.showAndGet() && stillOpen(row)) {
                dialog.selection()?.let(row::choose)
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
        if (!stillOpen(row) || !row.ready || row.conflicted) return
        val expected = row.snapshot
        if (target == ProjectColorTarget.ICON && !row.editableIconColor) return
        val dialog = ProjectColorDialog(view.component, project, row, target, view.appearance())
        dialogs[dialog] = row.key
        try {
            if (dialog.showAndGet() && stillOpen(row)) {
                if (row.snapshot?.fingerprint != expected?.fingerprint || row.snapshot?.directory != expected?.directory) {
                    row.markConflict()
                } else row.changeColor(target, dialog.selection())
                view.refreshRows()
            }
        } finally {
            dialogs.remove(dialog)
        }
    }

    private fun reloadRow(row: ProjectIconSettingsRow) {
        if (!stillOpen(row)) return
        row.discardDraft()
        panel?.refreshRows()
        reload(keepDraft = true)
    }

    override fun apply() {
        val view = panel ?: return
        if (!view.modified) return
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
                row.activeChoice?.asset ?: throw ConfigurationException("Choose a project icon."))
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
            if (writes.isNotEmpty()) ProgressManager.getInstance().runProcessWithProgressSynchronously(Runnable {
                try {
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
        lifetime?.let(Disposer::dispose)
        lifetime = null
        panel = null
        worker = null
        models.clear()
        iconDirectories = emptySet()
        gallery = emptyList()
    }

    companion object {
        private val LOG = Logger.getInstance(IconSettingsConfigurable::class.java)
    }
}

private data class LoadedOpenProjects(val rows: List<LoadedProjectIcon>, val gallery: List<IconChoice>,
                                      val previewArtwork: PreviewArtwork)

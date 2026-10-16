package dev.vlas.projecttabicons

import com.intellij.ide.ProjectColorChangeListener
import com.intellij.ide.ProjectWindowCustomizerService
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.WindowManagerListener
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.mac.WindowTabsComponent
import com.intellij.ui.IconDeferrer
import com.intellij.ui.DeferredIconImpl
import com.intellij.util.ui.EmptyIcon
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Container
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.LayoutManager
import java.awt.LayoutManager2
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.border.Border

@Service(Service.Level.APP)
class ProjectTabIconsService(private val coroutineScope: CoroutineScope) : Disposable {
    @Volatile
    private var disposed = false
    @Volatile
    private var failed = false
    private val queued = AtomicBoolean()
    private val invalidateIcons = AtomicBoolean()
    private var connection: MessageBusConnection? = null
    private val observations = IdentityHashMap<IdeFrameImpl, FrameObservation>()
    private val tabOwners = IdentityHashMap<TabInfo, WindowTabsComponent>()
    private val originalIcons = IdentityHashMap<TabInfo, Icon?>()
    private val appliedIcons = IdentityHashMap<TabInfo, Icon?>()
    private val originalTabTitles = IdentityHashMap<TabInfo, String>()
    private val appliedTabTitles = IdentityHashMap<TabInfo, String>()
    private val originalBranchTooltips = IdentityHashMap<TabInfo, HtmlChunk?>()
    private val appliedBranchTooltips = IdentityHashMap<TabInfo, HtmlChunk>()
    private val branchCaptions = IdentityHashMap<JComponent, NativeProjectTabBranchCaption>()
    private data class GitWatch(val provider: ProjectTabBranchProvider, val includeStatus: Boolean, val lifetime: Disposable)
    private val branchWatches = IdentityHashMap<Project, GitWatch>()
    private val iconWrappers = IdentityHashMap<Icon, CenteredProjectIcon>()
    private val originalLabelBorders = IdentityHashMap<JComponent, Border?>()
    private val appliedLabelBorders = IdentityHashMap<JComponent, ProjectTabBorder>()
    private val originalLabelLayouts = IdentityHashMap<JComponent, LayoutManager?>()
    private val appliedLabelLayouts = IdentityHashMap<JComponent, ProjectTabIconLayout>()
    private val appliedLabelFonts = IdentityHashMap<JComponent, ProjectTabFont>()
    private val appliedContainerLayouts = IdentityHashMap<JComponent, ProjectTabsContainerLayout>()
    private val projectNames = IdentityHashMap<Project, String>()
    private val iconReads = IdentityHashMap<Icon, IconColorRead?>()
    private val loadingColors = identitySet<Icon>()
    private var colorGeneration = 0L
    private var previousAppearance: TabAppearanceState? = null

    init {
        ProjectTabBranchProvider.EP_NAME.addChangeListener(coroutineScope, Runnable { requestRefresh() })
    }

    fun requestRefresh(invalidate: Boolean = false) {
        if (disposed || failed || !SystemInfoRt.isMac) return
        if (invalidate) invalidateIcons.set(true)
        if (!queued.compareAndSet(false, true)) return

        // Scope принадлежит сервису и отменяется платформой при отключении плагина.
        coroutineScope.launch(Dispatchers.Default) {
            // Git-виджет может читать VFS при выборе репозитория: нужен EDT с разрешённым
            // read lock, а не Dispatchers.UI, который запрещает доступ к модели.
            // Отложенный переход также даёт Swing завершить перестройку панели.
            withContext(Dispatchers.EDT) {
                queued.set(false)
                if (disposed || failed) return@withContext
                try {
                    val appearance = ApplicationManager.getApplication().getService(TabAppearanceSettings::class.java).state
                    if (!appearance.enabled) {
                        // Возвращаем штатные значки, layout, высоту и отступы крестика.
                        // Сервис остаётся доступен для повторного включения через Apply.
                        cleanup()
                        invalidateIcons.set(true)
                        return@withContext
                    }
                    connectIfNeeded()
                    refresh(appearance)
                } catch (error: ProcessCanceledException) {
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    stopAfterFailure(error)
                } catch (error: LinkageError) {
                    stopAfterFailure(error)
                }
            }
        }
    }

    private fun connectIfNeeded() {
        if (connection != null) return
        connection = ApplicationManager.getApplication().messageBus.connect(this).also { bus ->
            bus.subscribe(WindowManagerListener.TOPIC, object : WindowManagerListener {
                override fun onFramesChanged() = requestRefresh()
            })
            bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { requestRefresh(invalidate = true) })
            bus.subscribe(
                ProjectWindowCustomizerService.PROJECT_COLOR_CHANGE_TOPIC,
                object : ProjectColorChangeListener {
                    override fun projectColorChanged(projectPath: String) = requestRefresh(invalidate = true)
                },
            )
            bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { isIconConfiguration(it.path) }) requestRefresh(invalidate = true)
                }
            })
        }
    }

    private fun refresh(appearance: TabAppearanceState) {
        val frames = IdentityHashMap<IdeFrameImpl, Project>()
        WindowManager.getInstance().allProjectFrames.filterIsInstance<ProjectFrameHelper>().forEach { helper ->
            val project = helper.getProject()
            if (project != null && !project.isDisposed) {
                frames[helper.frame] = project
            }
        }
        frames.values.forEach { project ->
            val previousName = projectNames.put(project, project.name)
            if (previousName != null && previousName != project.name) invalidateIcons.set(true)
        }
        projectNames.keys.retainAll(frames.values.toSet())

        observations.keys.filter { it !in frames }.forEach { frame -> observations.remove(frame)?.close() }
        frames.keys.forEach { frame ->
            val root = frame.rootPane
            val previous = observations[frame]
            val observation = if (previous != null && previous.rootPane === root) {
                previous
            } else {
                previous?.close()
                FrameObservation(
                    frame, root,
                    changed = { requestRefresh() },
                    titleChanged = { requestRefresh() },
                ).also { observations[frame] = it }
            }
            observation.update()
        }

        val branches = projectExtraInfo(frames.values.toSet(), appearance)
        val recentProjects = RecentProjectsManagerBase.getInstanceEx()
        if (invalidateIcons.getAndSet(false)) {
            colorGeneration++
            iconReads.clear()
            loadingColors.clear()
            iconWrappers.clear()
            IconDeferrer.getInstance().clearCache()
            frames.values.mapNotNull(recentProjects::getProjectPath).toSet().forEach {
                RecentProjectIconHelper.refreshProjectIcon(it)
            }
            frames.forEach { (frame, project) ->
                ProjectWindowCustomizerService.getInstance().dropProjectIconCache(project)
                frame.repaint()
            }
        }

        val projectColors = ApplicationManager.getApplication().getService(ProjectOutlineColors::class.java)
        if (previousAppearance != appearance) iconWrappers.clear()
        previousAppearance = appearance

        val activeTabs = identitySet<TabInfo>()
        val activeDelegates = identitySet<Icon>()
        val activeLabels = identitySet<JComponent>()
        val activeContainers = identitySet<JComponent>()
        frames.keys.forEach { frame ->
            val container = frame.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) as? Component
            val tabs = findWindowTabs(container) ?: return@forEach
            val tabsContainer = tabs.parent as? JComponent
            if (tabsContainer != null && needsCustomContainerLayout(appearance)) {
                activeContainers.add(tabsContainer)
                decorateTabsContainer(tabsContainer, appearance.tabHeight)
            }
            tabs.tabs.forEach tab@{ info ->
                // Каждый frame содержит копию всей полосы. Значок относится к frame из TabInfo,
                // а не к проекту окна, внутри которого сейчас рисуется полоса.
                val targetFrame = info.`object` as? IdeFrameImpl ?: return@tab
                val project = frames[targetFrame] ?: return@tab
                val path = recentProjects.getProjectPath(project) ?: return@tab
                tabOwners[info] = tabs
                decorateTabTitle(info, project.name, appearance.showProjectPath)
                val branch = branches[project]
                decorateBranchTooltip(info, project.name, branch)
                // Поля провайдера по 2px: запрос 24/28/32px даёт изображение 20/24/28px.
                val delegate = recentProjects.getProjectIcon(path, true, appearance.iconSize.pixels + 4, project.name)
                activeDelegates.add(delegate)
                val label = tabs.getTabLabel(info)
                val read = if (appearance.showOutline || appearance.showIcons || appearance.showTabBackground) {
                    readIcon(delegate, label, appearance.iconSize.pixels)
                } else null
                val overrides = projectColors.overrides(path)
                // Ручной цвет буквенного аватара не перекрашивает сторонние SVG/PNG.
                val iconColor = if (read?.avatar != null) overrides.icon ?: read.color else read?.color
                val color = if (appearance.showOutline) overrides.outline ?: iconColor else null
                val tabColor = if (appearance.showTabBackground) overrides.tab ?: iconColor else null
                val icon = if (appearance.showIcons) iconWrappers.getOrPut(delegate) {
                    CenteredProjectIcon(delegate, appearance.iconSize.pixels,
                        artwork = { iconReads[delegate]?.artwork },
                        avatarColor = { projectColors.iconColor(path) },
                        initials = appearance.iconInitials, letterWeight = appearance.iconLetterWeight,
                        projectName = { project.name })
                } else null
                if (label != null) {
                    activeLabels.add(label)
                    decorateTabLabel(label, tabs, appearance, icon, color, tabColor)
                    decorateBranchCaption(label, project.name, branch, appearance.branchPosition)
                }
                activeTabs.add(info)
                if (!originalIcons.containsKey(info)) originalIcons[info] = info.icon
                // Штатная раскладка центрирует icon + title. Border закрепляет значок на выбранной стороне.
                if (info.icon != null) info.setIcon(null)
                appliedIcons[info] = null
            }
        }
        appliedContainerLayouts.keys.filter { it !in activeContainers }.forEach(::restoreTabsContainer)
        appliedTabTitles.keys.filter { it !in activeTabs }.forEach(::restoreTabTitle)
        appliedBranchTooltips.keys.filter { it !in activeTabs }.forEach(::restoreBranchTooltip)
        tabOwners.keys.retainAll(activeTabs)
        originalIcons.keys.retainAll(activeTabs)
        appliedIcons.keys.retainAll(activeTabs)
        iconWrappers.keys.retainAll(activeDelegates)
        iconReads.keys.retainAll(activeDelegates)
        branchCaptions.keys.filter { it !in activeLabels }.toList().forEach { branchCaptions.remove(it)?.restore() }
        appliedLabelBorders.keys.filter { it !in activeLabels }.forEach(::restoreTabLabelBorder)
        appliedLabelLayouts.keys.filter { it !in activeLabels }.forEach(::restoreTabLabelLayout)
        appliedLabelFonts.keys.filter { it !in activeLabels }.forEach(::restoreTabFont)
    }

    private fun projectExtraInfo(projects: Set<Project>, appearance: TabAppearanceState): Map<Project, ProjectTabExtraInfo?> {
        val mode = appearance.moreInfo()
        val provider = if (mode.isGit) ProjectTabBranchProvider.EP_NAME.extensionList.firstOrNull() else null
        val includeStatus = mode.isGit && appearance.showGitStatus
        branchWatches.keys.filter {
            it !in projects || branchWatches[it]?.provider !== provider || branchWatches[it]?.includeStatus != includeStatus
        }.toList().forEach { branchWatches.remove(it)?.lifetime?.let(Disposer::dispose) }
        if (provider == null) return emptyMap()
        return projects.associateWith { project ->
            branchWatches.getOrPut(project) {
                GitWatch(provider, includeStatus, provider.watch(project, this, includeStatus) { requestRefresh() })
            }
            val status = if (includeStatus) provider.currentStatus(project)?.let { ProjectTabExtraInfo.Status(it) } else null
            val branch = if (mode.isBranch) provider.currentBranch(project)?.takeIf { it.isNotBlank() } else null
            branch?.let {
                ProjectTabExtraInfo.Branch(it, compact = mode == TabMoreInfo.GIT_BRANCH_COMPACT, status = status)
            } ?: status
        }
    }

    private fun decorateBranchCaption(label: JComponent, projectName: String, extra: ProjectTabExtraInfo?, side: TabBranchPosition) {
        if (extra == null) {
            branchCaptions.remove(label)?.restore()
            return
        }
        branchCaptions[label]?.takeUnless { it.isInstalled() }?.let { branchCaptions.remove(label)?.restore() }
        branchCaptions.getOrPut(label) { NativeProjectTabBranchCaption(label) }.show(projectName, extra, side)
    }

    private fun decorateBranchTooltip(info: TabInfo, projectName: String, extra: ProjectTabExtraInfo?) {
        if (extra == null) { restoreBranchTooltip(info); return }
        val current = info.tooltipHtmlText
        val previous = appliedBranchTooltips[info]
        if (previous == null || current.toString() != previous.toString()) originalBranchTooltips[info] = current
        val base = originalBranchTooltips[info] ?: HtmlChunk.text(projectName)
        val next = HtmlChunk.fragment(base, HtmlChunk.br(), HtmlChunk.text(extra.tooltip))
        if (current.toString() != next.toString()) info.setTooltipText(next)
        appliedBranchTooltips[info] = next
    }

    private fun restoreBranchTooltip(info: TabInfo) {
        val applied = appliedBranchTooltips.remove(info) ?: return
        val original = originalBranchTooltips.remove(info)
        if (canUpdateTabInfo(info) && info.tooltipHtmlText.toString() == applied.toString()) info.setTooltipText(original)
    }

    private fun decorateTabTitle(info: TabInfo, projectName: String, showPath: Boolean) {
        if (showPath) {
            restoreTabTitle(info)
            return
        }
        val applied = appliedTabTitles[info]
        // IDEA меняет текст при смене файла или имени проекта. Сохраняем её новую
        // подпись, чтобы включение пути и отключение плагина вернули актуальный текст.
        if (applied == null || info.text != applied) originalTabTitles[info] = info.text
        if (info.text != projectName) info.setText(projectName)
        appliedTabTitles[info] = projectName
        // IDEA обновляет базовый tooltip; расширение ветки хранится и восстанавливается отдельно.
    }

    private fun restoreTabTitle(info: TabInfo) {
        val applied = appliedTabTitles.remove(info) ?: return
        val original = originalTabTitles.remove(info) ?: return
        // Не затираем текст, если после нас его изменило другое расширение или IDEA.
        if (canUpdateTabInfo(info) && info.text == applied) info.setText(original)
    }

    private fun canUpdateTabInfo(info: TabInfo): Boolean {
        val tabs = tabOwners[info] ?: return false
        // IDEA снимает TabLabel до dispose application service. Setter тогда
        // вызывает updateText/updateIcon у уже разобранного JBTabsImpl.
        return tabs.isDisplayable && tabs.getTabLabel(info) != null
    }

    private fun decorateTabLabel(label: JComponent, tabs: WindowTabsComponent,
                                 appearance: TabAppearanceState, icon: CenteredProjectIcon?, color: Color?, tabColor: Color?) {
        val fontChanged = if (appearance.tabFontSize == 0) restoreTabFont(label) else {
            appliedLabelFonts.getOrPut(label) { ProjectTabFont(label) }.apply(appearance.tabFontSize)
        }
        if (fontChanged) {
            label.revalidate()
            tabs.revalidateAndRepaint()
        }
        decorateTabLayout(label, tabs, icon, appearance)
        if (icon == null && !appearance.showOutline && !appearance.showTabBackground) {
            restoreTabLabelBorder(label)
            return
        }
        val applied = appliedLabelBorders[label]
        if (applied != null && label.border === applied) {
            if (applied.projectIcon !== icon) {
                applied.projectIcon = icon
                label.revalidate()
                tabs.revalidateAndRepaint()
            }
            if (applied.outlineColor != color || applied.tabColor != tabColor || applied.outlineThickness != appearance.outlineThickness ||
                applied.outlineStyle != appearance.outlineStyle || applied.tabFillInset != appearance.tabFillInset) {
                applied.outlineColor = color
                applied.tabColor = tabColor
                applied.outlineThickness = appearance.outlineThickness
                applied.outlineStyle = appearance.outlineStyle
                applied.tabFillInset = appearance.tabFillInset
                label.revalidate()
                tabs.revalidateAndRepaint()
            }
            return
        }
        // Тема может заменить border при updateUI: в этом случае её border становится новой базой.
        if (applied == null || label.border !== applied) originalLabelBorders[label] = label.border
        val border = ProjectTabBorder(originalLabelBorders[label], icon, color,
            appearance.outlineThickness, appearance.outlineStyle, tabColor, appearance.tabFillInset)
        label.border = border
        appliedLabelBorders[label] = border
        label.revalidate()
        tabs.revalidateAndRepaint()
    }

    private fun needsCustomContainerLayout(appearance: TabAppearanceState): Boolean =
        appearance.showIcons || appearance.showOutline || appearance.showTabBackground ||
            appearance.closeButtonPosition != TabCloseButtonPosition.LEFT || appearance.tabHeight != DEFAULT_TAB_HEIGHT ||
            appearance.tabFontSize != 0 || appearance.moreInfo().isGit

    private fun decorateTabLayout(label: JComponent, tabs: WindowTabsComponent,
                                  icon: CenteredProjectIcon?, appearance: TabAppearanceState) {
        // Пустой слот крестика сохраняется и при выключенных иконках/обводке:
        // смена стороны кнопки не должна перемещать область названия.
        val centerProjectName = appearance.moreInfo() == TabMoreInfo.NONE
        val applied = appliedLabelLayouts[label]
        if (applied != null && label.layout === applied) {
            if (applied.projectIcon !== icon || applied.position != appearance.iconPosition ||
                applied.closeButtonPosition != appearance.closeButtonPosition ||
                applied.centerProjectName != centerProjectName) {
                applied.projectIcon = icon
                applied.position = appearance.iconPosition
                applied.closeButtonPosition = appearance.closeButtonPosition
                applied.centerProjectName = centerProjectName
                label.revalidate()
                tabs.revalidateAndRepaint()
            }
            return
        }
        applied?.restoreCloseInsets()
        val original = label.layout as? LayoutManager2 ?: return
        originalLabelLayouts[label] = original
        label.layout = ProjectTabIconLayout(original, icon, appearance.iconPosition,
            appearance.closeButtonPosition, centerProjectName).also { appliedLabelLayouts[label] = it }
        label.revalidate()
        tabs.revalidateAndRepaint()
    }

    private fun decorateTabsContainer(container: JComponent, height: Int) {
        val applied = appliedContainerLayouts[container]
        if (applied != null && container.layout === applied) {
            if (applied.tabHeight != height) {
                applied.tabHeight = height
                container.revalidate()
                container.repaint()
            }
            return
        }
        val original = container.layout as? LayoutManager2 ?: return
        container.layout = ProjectTabsContainerLayout(original, height).also { appliedContainerLayouts[container] = it }
        container.revalidate()
    }

    private fun restoreTabsContainer(container: JComponent) {
        val applied = appliedContainerLayouts.remove(container) ?: return
        if (container.layout === applied) {
            container.layout = applied.original
            container.revalidate()
            container.repaint()
        }
    }

    private fun restoreTabFont(label: JComponent): Boolean = appliedLabelFonts.remove(label)?.restore() ?: false

    private fun restoreTabLabelLayout(label: JComponent) {
        val applied = appliedLabelLayouts.remove(label)
        applied?.restoreCloseInsets()
        val original = originalLabelLayouts.remove(label)
        if (applied != null && label.layout === applied) {
            label.layout = original
            label.revalidate()
            label.repaint()
        }
    }

    private fun readIcon(delegate: Icon, label: JComponent?, unscaledSize: Int): IconColorRead? {
        if (iconReads.containsKey(delegate)) return iconReads[delegate]
        if (!loadingColors.add(delegate)) return null
        var source = delegate
        if (delegate is DeferredIconImpl<*> && !delegate.isDone) {
            // В режиме «только линия» у иконки нет видимого paintIcon. Запускаем
            // загрузку штатным способом, не рисуя placeholder на экран.
            // Загруженные file-иконки здесь не рисуем, чтобы не менять их HiDPI-кеш.
            if (delegate.currentlyPaintedIcon() is EmptyIcon) {
                val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
                try {
                    delegate.paintIcon(label, graphics, 0, 0)
                } finally {
                    graphics.dispose()
                }
            } else {
                // У провайдера уже есть готовая база: она безопасна для чтения в IO.
                source = delegate.currentlyPaintedIcon()
            }
        }
        val generation = colorGeneration
        coroutineScope.launch(Dispatchers.IO) {
            var pending = false
            val result = try {
                projectIconColor(source, unscaledSize)
            } catch (_: IconColorNotReady) {
                pending = true
                null
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LOG.debug("Cannot determine the project icon color", error)
                null
            }
            if (pending) delay(200)
            withContext(Dispatchers.UI) {
                if (disposed || failed || generation != colorGeneration) return@withContext
                loadingColors.remove(delegate)
                val ready = result?.let { it.copy(color = it.avatar?.presentation?.color1 ?: it.color) }
                if (!pending) {
                    iconReads[delegate] = ready
                    // Новая SVG-версия меняет рисунок без изменения TabInfo.icon.
                    iconWrappers.remove(delegate)
                }
                requestRefresh()
            }
        }
        return null
    }

    private fun restoreTabLabelBorder(label: JComponent) {
        val applied = appliedLabelBorders.remove(label)
        val original = originalLabelBorders.remove(label)
        // Не заменяем border, если после нас его установила тема или другое расширение.
        if (applied != null && label.border === applied) {
            label.border = original
            label.revalidate()
            label.repaint()
        }
    }

    private fun stopAfterFailure(error: Throwable) {
        failed = true
        LOG.warn("Cannot decorate macOS project window tabs; original icons will be restored", error)
        cleanup()
    }

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) cleanup()
        else SwingUtilities.invokeLater { cleanup() }
    }

    private fun cleanup() {
        connection?.disconnect()
        connection = null
        observations.values.forEach(FrameObservation::close)
        observations.clear()
        appliedIcons.forEach { (info, applied) ->
            // Не затираем значок, если после нас его изменило другое расширение.
            if (canUpdateTabInfo(info) && info.icon === applied) info.setIcon(originalIcons[info])
        }
        branchWatches.values.forEach { Disposer.dispose(it.lifetime) }
        branchWatches.clear()
        branchCaptions.values.forEach { it.restore() }
        branchCaptions.clear()
        appliedBranchTooltips.keys.toList().forEach(::restoreBranchTooltip)
        originalBranchTooltips.clear()
        appliedTabTitles.keys.toList().forEach(::restoreTabTitle)
        originalTabTitles.clear()
        tabOwners.clear()
        appliedIcons.clear()
        originalIcons.clear()
        iconWrappers.clear()
        appliedLabelFonts.keys.toList().forEach { restoreTabFont(it) }
        appliedLabelBorders.keys.toList().forEach(::restoreTabLabelBorder)
        appliedLabelLayouts.keys.toList().forEach(::restoreTabLabelLayout)
        originalLabelBorders.clear()
        originalLabelLayouts.clear()
        appliedContainerLayouts.keys.toList().forEach(::restoreTabsContainer)
        projectNames.clear()
        colorGeneration++
        iconReads.clear()
        loadingColors.clear()
        previousAppearance = null
    }

    companion object {
        private val LOG = Logger.getInstance(ProjectTabIconsService::class.java)
    }
}

private fun findWindowTabs(component: Component?): WindowTabsComponent? {
    if (component is WindowTabsComponent) return component
    if (component !is Container) return null
    component.components.forEach { child -> findWindowTabs(child)?.let { return it } }
    return null
}

private fun isIconConfiguration(path: String): Boolean {
    if (!path.replace('\\', '/').contains("/.idea/")) return false
    return path.substringAfterLast('/') in setOf("icon.svg", "icon.png", "icon_dark.svg", "icon_dark.png", ".name")
}

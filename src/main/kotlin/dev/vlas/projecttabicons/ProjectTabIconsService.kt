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
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
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
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Container
import java.awt.Color
import java.awt.LayoutManager2
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

@Service(Service.Level.APP)
class ProjectTabIconsService(private val coroutineScope: CoroutineScope) : Disposable {
    @Volatile
    private var disposed = false
    private var retryJob: Job? = null
    private val recovery = ProjectTabRefreshRecovery(
        restore = { incompatible ->
            invalidateIcons.set(true)
            cleanup(keepRefreshListeners = !incompatible)
        },
        scheduleRetry = { delayMillis, resume ->
            retryJob?.cancel()
            retryJob = coroutineScope.launch {
                delay(delayMillis)
                withContext(Dispatchers.EDT) { if (!disposed) resume() }
            }
        },
        requestRefresh = { requestRefresh() },
        report = { error, incompatible ->
            LOG.warn(if (incompatible) "Project window tab API is incompatible; decoration is disabled"
                else "Cannot refresh project window tabs; original appearance restored, retrying", error)
        },
    )
    private val refreshQueue = CoalescingRefreshQueue(coroutineScope, Dispatchers.Default, Dispatchers.EDT)
    private val invalidateIcons = AtomicBoolean()
    private val iconFileEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private data class ProjectIconDelegate(val path: java.nio.file.Path, val size: Int, val name: String, val icon: Icon)
    private val projectDelegates = IdentityHashMap<Project, ProjectIconDelegate>()
    private var connection: MessageBusConnection? = null
    private val observations = IdentityHashMap<IdeFrameImpl, FrameObservation>()
    private val groupLayouts = IdentityHashMap<WindowTabsComponent, ProjectTabGroupLayout>()
    private val groupDrag = ProjectTabGroupDrag()
    private val groupVisualState by lazy { ApplicationManager.getApplication().getService(ProjectTabGroupVisualState::class.java) }
    private val groupOrdering = ProjectTabGroupOrdering(coroutineScope) { tabs, explicit ->
        if (explicit) groupLayouts[tabs]?.restoreAppearance() else groupLayouts[tabs]?.refreshOrder()
        requestRefresh()
    }
    private val tabOwners = IdentityHashMap<TabInfo, WindowTabsComponent>()
    private val originalIcons = IdentityHashMap<TabInfo, Icon?>()
    private val originalTabTitles = IdentityHashMap<TabInfo, String>()
    private val appliedTabTitles = IdentityHashMap<TabInfo, String>()
    private val originalBranchTooltips = IdentityHashMap<TabInfo, HtmlChunk?>()
    private val appliedBranchTooltips = IdentityHashMap<TabInfo, HtmlChunk>()
    private val branchCaptions = IdentityHashMap<JComponent, NativeProjectTabBranchCaption>()
    private data class GitWatch(val provider: ProjectTabBranchProvider, val includeStatus: Boolean, val lifetime: Disposable)
    private val branchWatches = IdentityHashMap<Project, GitWatch>()
    private val iconWrappers = IdentityHashMap<Icon, CenteredProjectIcon>()
    private val appliedLabelBorders = IdentityHashMap<JComponent, ProjectTabBorder>()
    private val appliedLabelLayouts = IdentityHashMap<JComponent, ProjectTabIconLayout>()
    private val appliedLabelFonts = IdentityHashMap<JComponent, ProjectTabFont>()
    private val appliedContainerLayouts = IdentityHashMap<JComponent, ProjectTabsContainerLayout>()
    private val projectNames = IdentityHashMap<Project, String>()
    // Только очищенные изображения, независимо от смены DeferredIcon и сброса его кеша.
    private val lastPreparedArtwork = IdentityHashMap<Project, MutableMap<Int, Icon>>()
    private val iconReadQueue = ProjectIconReadQueue(
        coroutineScope, Dispatchers.IO, Dispatchers.UI,
        postToUi = { SwingUtilities.invokeLater(it) },
        canPublish = { !disposed && !recovery.blocked },
    )
    private val iconReads = ProjectIconReadCache(iconReadQueue, ::projectIconColor,
        resolve = { it.copy(color = it.avatar?.presentation?.color1 ?: it.color) },
        changed = { delegate, ready -> if (ready) iconWrappers.remove(delegate); requestRefresh() },
        report = { LOG.debug("Cannot determine the project icon color", it) })
    private var previousAppearance: TabAppearanceState? = null
    private var previousGroups: ProjectGroupsState? = null
    private val groupIconWrappers = mutableMapOf<Pair<ProjectGroupIcon, Int>, CenteredProjectIcon>()

    private data class TabPresentation(val icon: CenteredProjectIcon?, val outline: Color?, val background: Color?)

    init {
        ProjectTabBranchProvider.EP_NAME.addChangeListener(coroutineScope, Runnable { requestRefresh() })
    }

    fun requestRefresh(invalidate: Boolean = false, resetTransientFailure: Boolean = false) {
        if (disposed || !SystemInfoRt.isMac) return
        if (resetTransientFailure) {
            if (!SwingUtilities.isEventDispatchThread()) {
                ApplicationManager.getApplication().invokeLater { requestRefresh(invalidate, resetTransientFailure = true) }
                return
            }
            retryJob?.cancel()
            retryJob = null
            recovery.resetTransientFailures()
        }
        if (invalidate) invalidateIcons.set(true)
        if (recovery.blocked) return
        refreshQueue.submit(prepare = {
            try {
                val groups = ApplicationManager.getApplication().getService(ProjectGroups::class.java).state
                Result.success(groups to GroupAppearanceIcons.prepare(groups))
            }
            catch (error: ProcessCanceledException) { throw error }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
            catch (error: LinkageError) { Result.failure(error) }
            // Git-виджет может читать VFS при выборе репозитория: нужен EDT с разрешённым
            // read lock, а не Dispatchers.UI, который запрещает доступ к модели.
            // Отложенный переход также даёт Swing завершить перестройку панели.
        }, apply = apply@{ prepared ->
            if (disposed) return@apply
            recovery.run {
                val appearance = ApplicationManager.getApplication().getService(TabAppearanceSettings::class.java).state
                if (!appearance.enabled) {
                    // Возвращаем штатные значки, layout, высоту и отступы крестика.
                    // Сервис остаётся доступен для повторного включения через Apply.
                    cleanup()
                    invalidateIcons.set(true)
                    return@run
                }
                connectIfNeeded()
                val (groups, groupIcons) = prepared.getOrThrow()
                // Подготовка SVG не блокирует EDT. После Apply готовим новую версию.
                if (groups != ApplicationManager.getApplication().getService(ProjectGroups::class.java).state) {
                    requestRefresh()
                    return@run
                }
                refresh(appearance, groups, groupIcons)
            }
        })
    }

    private fun connectIfNeeded() {
        if (connection != null) return
        connection = ApplicationManager.getApplication().messageBus.connect(this).also { bus ->
            bus.subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
                    if (ActionManager.getInstance().getId(action) == "MergeAllWindowsAction") {
                        groupOrdering.windowsMerged(); requestRefresh()
                    }
                }
            })
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
                    val paths = events.map { it.path }.filter(::isIconConfiguration)
                    if (paths.isNotEmpty()) { iconFileEvents.addAll(paths); requestRefresh() }
                }
            })
        }
    }

    private fun refresh(appearance: TabAppearanceState, groups: ProjectGroupsState,
                        groupIcons: Map<ProjectGroupIcon, PreparedGroupIcon>) {
        val frames = IdentityHashMap<IdeFrameImpl, Project>()
        WindowManager.getInstance().allProjectFrames.filterIsInstance<ProjectFrameHelper>().forEach { helper ->
            val project = helper.getProject()
            if (project != null && !project.isDisposed) {
                frames[helper.frame] = project
            }
        }
        val recentProjects = RecentProjectsManagerBase.getInstanceEx()
        val projectPaths = frames.values.mapNotNull { project -> recentProjects.getProjectPath(project)?.let { project to it } }.toMap()
        val fileEvents = generateSequence { iconFileEvents.poll() }.toList()
        val changedProjects = affectedIconProjects(projectPaths, fileEvents).toMutableSet()
        frames.values.forEach { project ->
            val previousName = projectNames.put(project, project.name)
            if (previousName != null && previousName != project.name) changedProjects.add(project)
        }
        projectNames.keys.retainAll(frames.values.toSet())
        lastPreparedArtwork.keys.retainAll(frames.values.toSet())
        projectDelegates.keys.retainAll(frames.values.toSet())

        observations.keys.filter { it !in frames }.forEach { frame -> observations.remove(frame)?.close() }
        groupVisualState.retain(groups.groups.map { it.id }.toSet())
        groupOrdering.update(frames)
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
        if (invalidateIcons.getAndSet(false)) {
            iconReads.clear()
            iconWrappers.clear()
            projectDelegates.clear()
            IconDeferrer.getInstance().clearCache()
            changedProjects.addAll(frames.values)
        }
        changedProjects.forEach { project ->
            projectDelegates.remove(project)?.icon?.let { delegate -> iconReads.invalidate(delegate); iconWrappers.remove(delegate) }
            projectPaths[project]?.let(RecentProjectIconHelper::refreshProjectIcon)
        }
        frames.forEach { (frame, project) ->
            if (project in changedProjects) {
                ProjectWindowCustomizerService.getInstance().dropProjectIconCache(project)
                frame.repaint()
            }
        }

        val projectColors = ApplicationManager.getApplication().getService(ProjectOutlineColors::class.java)
        if (previousAppearance != appearance) { iconWrappers.clear(); groupIconWrappers.clear() }
        if (previousGroups != groups) groupIconWrappers.clear()
        previousAppearance = appearance
        previousGroups = groups

        val groupColors = groups.groups.mapNotNull { group ->
            val automatic = groupIcons[group.icon]?.choice?.color ?: parseIconColor(group.icon.color)
                ?: return@mapNotNull null
            group.id to ProjectTabGroupColors(parseIconColor(group.outlineColor) ?: automatic,
                parseIconColor(group.tabColor) ?: automatic)
        }.toMap()
        val sharedIconSize = appearance.iconSize.pixels
        val sharedGroupIcons = if (appearance.showGroupIcon) groups.groups.mapNotNull { group ->
            val artwork = groupIcons[group.icon]?.artwork?.get(sharedIconSize) ?: return@mapNotNull null
            group.id to groupIconWrappers.getOrPut(group.icon to sharedIconSize) {
                CenteredProjectIcon(artwork, sharedIconSize, artwork = { artwork })
            }
        }.toMap() else emptyMap()

        val activeTabs = identitySet<TabInfo>()
        val activeDelegates = identitySet<Icon>()
        val activeLabels = identitySet<JComponent>()
        val activeContainers = identitySet<JComponent>()
        val activeGroupLayouts = identitySet<WindowTabsComponent>()
        val presentations = IdentityHashMap<Project, TabPresentation>()
        fun presentationFor(project: Project, label: JComponent?): TabPresentation? {
            presentations[project]?.let { return it }
            val path = recentProjects.getProjectPath(project) ?: return null
            val group = groups.groupFor(projectIconPathKey(path))
            if (group != null) {
                val prepared = groupIcons[group.icon] ?: return null
                val artwork = prepared.artwork.getValue(appearance.iconSize.pixels)
                val icon = if (appearance.showIcons) groupIconWrappers.getOrPut(group.icon to appearance.iconSize.pixels) {
                    CenteredProjectIcon(artwork, appearance.iconSize.pixels, artwork = { artwork })
                } else null
                val source = prepared.choice.color
                return TabPresentation(icon,
                    if (appearance.showOutline) parseIconColor(group.outlineColor) ?: source else null,
                    if (appearance.showTabBackground) parseIconColor(group.tabColor) ?: source else null,
                ).also { presentations[project] = it }
            }
            // Каждое окно содержит копию всей полосы; данные проекта вычисляем один раз за проход.
            // Поля провайдера по 2px: запрос 24/28/32px даёт изображение 20/24/28px.
            // IconDeferrer сбрасывается платформой даже при нерелевантных VFS-событиях.
            // Сохраняем свой delegate до изменения данных именно этого проекта/темы/размера.
            val size = appearance.iconSize.pixels
            val cached = projectDelegates[project]?.takeIf { it.path == path && it.size == size && it.name == project.name }
                ?: ProjectIconDelegate(path, size, project.name, recentProjects.getProjectIcon(path, true, size + 4, project.name))
                    .also { projectDelegates[project] = it }
            val delegate = cached.icon
            activeDelegates.add(delegate)
            val read = if (appearance.showOutline || appearance.showIcons || appearance.showTabBackground) {
                readIcon(delegate, label, appearance.iconSize.pixels)
            } else null
            val overrides = projectColors.overrides(path)
            // Ручной цвет буквенного аватара не перекрашивает сторонние SVG/PNG.
            val iconColor = if (read?.avatar != null) overrides.icon ?: read.color else read?.color
            val icon = if (appearance.showIcons) iconWrappers.getOrPut(delegate) {
                CenteredProjectIcon(delegate, appearance.iconSize.pixels,
                    artwork = { preparedArtwork(delegate, project, appearance.iconSize.pixels) },
                    avatarColor = { projectColors.iconColor(path) },
                    initials = appearance.iconInitials, letterWeight = appearance.iconLetterWeight,
                    projectName = { project.name })
            } else null
            return TabPresentation(icon,
                if (appearance.showOutline) overrides.outline ?: iconColor else null,
                if (appearance.showTabBackground) overrides.tab ?: iconColor else null,
            ).also { presentations[project] = it }
        }
        frames.keys.forEach { frame ->
            val container = frame.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) as? Component
            val tabs = findWindowTabs(container) ?: return@forEach
            val groupLayout = if (groups.groups.isNotEmpty() || appearance.groupSpacing > 0 || appearance.showGroupOutline ||
                appearance.showGroupSeparator || appearance.showGroupIcon) {
                activeGroupLayouts.add(tabs)
                groupLayouts.getOrPut(tabs) { ProjectTabGroupLayout(tabs, groupVisualState, groupDrag,
                    { groupOrdering.isOrdering }, { requestRefresh() }) }.also { layout ->
                    val memberships = tabs.tabs.mapNotNull { info ->
                        val project = frames[info.`object`] ?: return@mapNotNull null
                        val path = recentProjects.getProjectPath(project) ?: return@mapNotNull null
                        groups.groupFor(projectIconPathKey(path))?.let { info to it.id }
                    }.toMap()
                    layout.update(appearance, memberships, groupColors, sharedGroupIcons)
                }
            } else null
            val tabsContainer = tabs.parent as? JComponent
            if (tabsContainer != null && needsCustomContainerLayout(appearance)) {
                activeContainers.add(tabsContainer)
                decorateTabsContainer(tabsContainer, appearance.tabHeight, groupLayout)
            }
            tabs.tabs.forEach tab@{ info ->
                // Каждый frame содержит копию всей полосы. Значок относится к frame из TabInfo,
                // а не к проекту окна, внутри которого сейчас рисуется полоса.
                val targetFrame = info.`object` as? IdeFrameImpl ?: return@tab
                val project = frames[targetFrame] ?: return@tab
                val label = tabs.getTabLabel(info)
                val presentation = presentationFor(project, label) ?: return@tab
                tabOwners[info] = tabs
                decorateTabTitle(info, project.name, appearance.showProjectPath)
                val branch = branches[project]
                decorateBranchTooltip(info, project.name, branch)
                if (label != null) {
                    activeLabels.add(label)
                    decorateTabLabel(label, tabs, info, groupLayout, appearance,
                        presentation.icon, presentation.outline, presentation.background)
                    decorateBranchCaption(label, project.name, branch, appearance.branchPosition)
                }
                activeTabs.add(info)
                if (!originalIcons.containsKey(info)) originalIcons[info] = info.icon
                // Штатная раскладка центрирует icon + title. Border закрепляет значок на выбранной стороне.
                if (info.icon != null) info.setIcon(null)
            }
        }
        groupLayouts.keys.filter { it !in activeGroupLayouts }.forEach { groupLayouts.remove(it)?.close() }
        appliedContainerLayouts.keys.filter { it !in activeContainers }.forEach(::restoreTabsContainer)
        appliedTabTitles.keys.filter { it !in activeTabs }.forEach(::restoreTabTitle)
        appliedBranchTooltips.keys.filter { it !in activeTabs }.forEach(::restoreBranchTooltip)
        tabOwners.keys.retainAll(activeTabs)
        originalIcons.keys.retainAll(activeTabs)
        iconWrappers.keys.retainAll(activeDelegates)
        iconReads.retain(activeDelegates)
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

    private fun decorateTabLabel(label: JComponent, tabs: WindowTabsComponent, info: TabInfo,
                                 groupLayout: ProjectTabGroupLayout?,
                                 appearance: TabAppearanceState, icon: CenteredProjectIcon?, color: Color?, tabColor: Color?) {
        val fontChanged = if (appearance.tabFontSize == 0) restoreTabFont(label) else {
            appliedLabelFonts.getOrPut(label) { ProjectTabFont(label) }.apply(appearance.tabFontSize)
        }
        if (fontChanged) {
            label.revalidate()
            tabs.revalidateAndRepaint()
        }
        decorateTabLayout(label, tabs, icon, appearance)
        // Выделение активного таба работает и без иконок, обводки и цветной заливки.
        // Полное отключение плагина восстанавливает border через cleanup().
        val applied = appliedLabelBorders[label]
        if (applied != null && label.border === applied) {
            var changed = false
            if (applied.groupLayout !== groupLayout || applied.groupTab !== info) {
                applied.groupLayout = groupLayout
                applied.groupTab = info
                changed = true
            }
            if (applied.projectIcon !== icon) {
                applied.projectIcon = icon
                changed = true
            }
            if (applied.outlineColor != color || applied.tabColor != tabColor || applied.outlineThickness != appearance.outlineThickness ||
                applied.outlineStyle != appearance.outlineStyle || applied.tabFillInset != appearance.tabFillInset ||
                applied.tabFillIntensity != appearance.tabFillIntensity || applied.inactiveTabDimming != appearance.inactiveTabDimming ||
                applied.activeTabHighlight != appearance.activeTabHighlight) {
                applied.outlineColor = color
                applied.tabColor = tabColor
                applied.outlineThickness = appearance.outlineThickness
                applied.outlineStyle = appearance.outlineStyle
                applied.tabFillInset = appearance.tabFillInset
                applied.tabFillIntensity = appearance.tabFillIntensity
                applied.inactiveTabDimming = appearance.inactiveTabDimming
                applied.activeTabHighlight = appearance.activeTabHighlight
                changed = true
            }
            if (changed) {
                label.revalidate()
                tabs.revalidateAndRepaint()
            }
            return
        }
        // Тема может заменить border при updateUI: в этом случае её border становится новой базой.
        val border = ProjectTabBorder(label.border, icon, color,
            appearance.outlineThickness, appearance.outlineStyle, tabColor, appearance.tabFillInset, appearance.tabFillIntensity,
            groupLayout = groupLayout, groupTab = info, inactiveTabDimming = appearance.inactiveTabDimming,
            activeTabHighlight = appearance.activeTabHighlight)
        label.border = border
        appliedLabelBorders[label] = border
        label.revalidate()
        tabs.revalidateAndRepaint()
    }

    private fun needsCustomContainerLayout(appearance: TabAppearanceState): Boolean =
        appearance.showIcons || appearance.showOutline || appearance.showTabBackground ||
            appearance.closeButtonPosition != TabCloseButtonPosition.LEFT || appearance.tabHeight != DEFAULT_TAB_HEIGHT ||
            appearance.tabFontSize != 0 || appearance.moreInfo().isGit || appearance.showGroupOutline || appearance.showGroupIcon

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
        label.layout = ProjectTabIconLayout(original, icon, appearance.iconPosition,
            appearance.closeButtonPosition, centerProjectName).also { appliedLabelLayouts[label] = it }
        label.revalidate()
        tabs.revalidateAndRepaint()
    }

    private fun decorateTabsContainer(container: JComponent, height: Int, groupLayout: ProjectTabGroupLayout?) {
        val applied = appliedContainerLayouts[container]
        if (applied != null && container.layout === applied) {
            if (applied.tabHeight != height || applied.groupLayout !== groupLayout) {
                applied.tabHeight = height
                applied.groupLayout = groupLayout
                container.revalidate()
                container.repaint()
            }
            return
        }
        val original = container.layout as? LayoutManager2 ?: return
        container.layout = ProjectTabsContainerLayout(original, height, groupLayout).also { appliedContainerLayouts[container] = it }
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
        val applied = appliedLabelLayouts.remove(label) ?: return
        applied.restoreCloseInsets()
        if (label.layout === applied) {
            label.layout = applied.original
            label.revalidate()
            label.repaint()
        }
    }

    private fun preparedArtwork(delegate: Icon, project: Project, size: Int): Icon? {
        val current = iconReads.get(delegate, size)
        if (current?.avatar != null) {
            lastPreparedArtwork[project]?.remove(size)
            return null
        }
        current?.artwork?.let { image ->
            lastPreparedArtwork.getOrPut(project) { mutableMapOf() }[size] = image
            return image
        }
        return lastPreparedArtwork[project]?.get(size)
    }

    private fun readIcon(delegate: Icon, label: JComponent?, unscaledSize: Int): IconColorRead? =
        iconReads.request(delegate, unscaledSize) {
            if (delegate is DeferredIconImpl<*> && !delegate.isDone) loadProjectIconWithoutPainting(delegate, label)
        }

    private fun restoreTabLabelBorder(label: JComponent) {
        val applied = appliedLabelBorders.remove(label) ?: return
        // Не заменяем border, если после нас его установила тема или другое расширение.
        if (label.border === applied) {
            label.border = applied.original
            label.revalidate()
            label.repaint()
        }
    }

    override fun dispose() {
        disposed = true
        retryJob?.cancel()
        recovery.dispose()
        if (SwingUtilities.isEventDispatchThread()) { groupOrdering.dispose(); cleanup() }
        else SwingUtilities.invokeLater { groupOrdering.dispose(); cleanup() }
    }

    private fun cleanup(keepRefreshListeners: Boolean = false) {
        if (!keepRefreshListeners) {
            connection?.disconnect()
            connection = null
            observations.values.forEach(FrameObservation::close)
            observations.clear()
        }
        groupOrdering.reset()
        groupLayouts.values.forEach(ProjectTabGroupLayout::close)
        groupLayouts.clear()
        originalIcons.forEach { (info, original) ->
            // Не затираем значок, если после нас его изменило другое расширение.
            if (canUpdateTabInfo(info) && info.icon == null) info.setIcon(original)
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
        originalIcons.clear()
        iconWrappers.clear()
        appliedLabelFonts.keys.toList().forEach { restoreTabFont(it) }
        appliedLabelBorders.keys.toList().forEach(::restoreTabLabelBorder)
        appliedLabelLayouts.keys.toList().forEach(::restoreTabLabelLayout)
        appliedContainerLayouts.keys.toList().forEach(::restoreTabsContainer)
        projectNames.clear()
        iconReads.clear()
        projectDelegates.clear()
        iconFileEvents.clear()
        lastPreparedArtwork.clear()
        previousAppearance = null
        previousGroups = null
        groupIconWrappers.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(ProjectTabIconsService::class.java)
    }
}

internal fun findWindowTabs(component: Component?): WindowTabsComponent? {
    if (component is WindowTabsComponent) return component
    if (component !is Container) return null
    component.components.forEach { child -> findWindowTabs(child)?.let { return it } }
    return null
}

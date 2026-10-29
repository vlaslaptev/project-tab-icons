package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

private val OPENED_BY_GROUP_ACTION = Key.create<Boolean>("ProjectTabIcons.openedByGroupAction")
internal fun openedByGroupAction(project: Project): Boolean = project.getUserData(OPENED_BY_GROUP_ACTION) == true

class OpenGroupedProjectsAction : DumbAwareAction(
    "Open All Grouped Projects", "Open projects from every saved group, then arrange their tabs by group", null,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = SystemInfoRt.isMac || SystemInfoRt.isWindows
        event.presentation.isEnabled = event.presentation.isVisible && event.project?.isDisposed == false &&
            service<ProjectGroups>().resolvedState.memberships.isNotEmpty() && !service<GroupedProjectOpening>().isOpening
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { service<GroupedProjectOpening>().open(it) }
    }
}

/** One batch for the whole IDE; existing projects are reused and failed paths do not stop the rest. */
@Service(Service.Level.APP)
internal class GroupedProjectOpening(private val scope: CoroutineScope) {
    @Volatile var isOpening = false
        private set

    fun open(anchor: Project) {
        if (isOpening || anchor.isDisposed) return
        val groups = service<ProjectGroups>().resolvedState
        val paths = orderedProjectGroupTabs(groups.memberships.keys.toList(), groups) { it }
        if (paths.isEmpty()) return
        isOpening = true
        scope.launch {
            try {
                withBackgroundProgress(anchor, "Opening grouped projects", cancellable = true) {
                    val projects = linkedSetOf<Project>()
                    val failures = mutableListOf<String>()
                    var opened = 0
                    for (key in paths) {
                        if (anchor.isDisposed) throw CancellationException("The originating project was closed")
                        val existing = withContext(Dispatchers.EDT) { findOpenProject(key) }
                        if (existing != null) { projects.add(existing); continue }
                        try {
                            val path = Path.of(key)
                            val exists = withContext(Dispatchers.IO) {
                                path.isAbsolute && (Files.isDirectory(path) ||
                                    path.fileName.toString().endsWith(".ipr") && Files.isRegularFile(path))
                            }
                            if (!exists) { failures.add("Not found: $key"); continue }
                            // Recheck after I/O: another action may have opened the project meanwhile.
                            val alreadyOpen = withContext(Dispatchers.EDT) { findOpenProject(key) }
                            val project = alreadyOpen ?: ProjectUtil.openOrImportAsync(path, OpenProjectTask {
                                forceOpenInNewFrame = true
                                showWelcomeScreen = false
                                beforeInit = { it.putUserData(OPENED_BY_GROUP_ACTION, true) }
                            })
                            if (project == null || project.isDisposed) failures.add("Not opened: $key")
                            else { projects.add(project); if (alreadyOpen == null) opened++ }
                        } catch (error: CancellationException) { throw error }
                        catch (error: ProcessCanceledException) { throw error }
                        catch (error: Exception) {
                            LOG.warn("Cannot open a project from a saved group", error)
                            failures.add("Could not open: $key")
                        }
                    }
                    val ready = withTimeoutOrNull(30_000) {
                        while (true) {
                            val waiting = withContext(Dispatchers.EDT) {
                                val framed = WindowManager.getInstance().allProjectFrames.filterIsInstance<ProjectFrameHelper>()
                                    .filter { it.frame.isDisplayable }.mapNotNull { it.project }.toSet()
                                projects.any { !it.isDisposed && it !in framed }
                            }
                            if (!waiting) break
                            delay(100)
                        }
                        true
                    } == true
                    val arranged = ready && !anchor.isDisposed && service<ProjectTabIconsService>()
                        .joinAndArrangeGroupedProjects(anchor, projects.filterNot { it.isDisposed })
                    if (!arranged) failures.add("Some project windows could not be arranged. Run the command again when they are ready.")
                    LOG.info("Grouped project opening finished: requested=${paths.size}, opened=$opened, available=${projects.size}, arranged=$arranged, failures=${failures.size}")
                    if (failures.isNotEmpty()) withContext(Dispatchers.EDT) {
                        val details = failures.take(8).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
                        val remaining = if (failures.size > 8) "<br>And ${failures.size - 8} more." else ""
                        NotificationGroupManager.getInstance().getNotificationGroup("Project Tab Icons")
                            .createNotification("Open All Grouped Projects", details + remaining, NotificationType.WARNING)
                            .notify(anchor.takeUnless { it.isDisposed })
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: ProcessCanceledException) { throw error }
            catch (error: Exception) {
                LOG.warn("Cannot finish opening grouped projects", error)
                withContext(Dispatchers.EDT) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Project Tab Icons")
                        .createNotification("Open All Grouped Projects", "Could not finish arranging the project windows. " +
                            "Already opened projects were kept. You can run the command again.", NotificationType.WARNING)
                        .notify(anchor.takeUnless { it.isDisposed })
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) { isOpening = false }
            }
        }
    }

    private fun findOpenProject(key: String): Project? {
        val recent = RecentProjectsManagerBase.getInstanceEx()
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            !project.isDisposed && recent.getProjectPath(project)?.let(::projectIconPathKey) == key
        }
    }

    companion object { private val LOG = Logger.getInstance(GroupedProjectOpening::class.java) }
}

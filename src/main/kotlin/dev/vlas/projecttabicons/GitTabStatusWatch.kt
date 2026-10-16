package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Выбранный IDE Git-корень читается на EDT; локальный numstat вычисляется только в фоне. */
internal class GitTabStatusWatch(
    private val project: Project,
    parent: Disposable,
    private val changed: () -> Unit,
) {
    private data class Selection(val root: String, val revision: String?)
    @Volatile private var disposed = false
    @Volatile private var generation = 0L
    @Volatile private var selection: Selection? = null
    @Volatile private var scheduled: ScheduledFuture<*>? = null
    @Volatile private var running: Future<*>? = null
    @Volatile private var indicator: ProgressIndicatorBase? = null
    // Меняются только на EDT, чтобы новая версия никогда не читала устаревший счётчик другого корня.
    private var status: GitTabDiffStatus? = null

    init {
        Disposer.register(parent, Disposable { disposed = true; cancelWork() })
    }

    fun snapshot(): GitTabDiffStatus? {
        if (disposed || project.isDisposed) return null
        val repository = GitBranchUtil.getCurrentRepository(project)
        val next = repository?.let { Selection(it.root.path, it.currentRevision) }
        if (next != selection) {
            cancelWork()
            selection = next
            status = when {
                next == null -> null
                next.revision == null -> GitTabDiffStatus(GitTabDiffState.NO_COMMITS)
                else -> GitTabDiffStatus(GitTabDiffState.LOADING)
            }
            if (next?.revision != null) schedule(next)
        }
        return status
    }

    fun affects(events: List<VFileEvent>): Boolean {
        val root = selection?.root ?: return false
        return events.any { event ->
            val path = event.path
            path.startsWith("$root/") && !path.startsWith("$root/.git/") && path != "$root/.git"
        }
    }

    fun invalidate() {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) {
                val previous = selection
                snapshot()
                // snapshot уже запланировал новый корень; тот же корень пересчитываем после события.
                if (selection == previous) selection?.takeIf { it.revision != null }?.let(::schedule)
            }
        }, ModalityState.any())
    }

    private fun schedule(target: Selection) {
        cancelWork()
        val run = generation
        val progress = ProgressIndicatorBase()
        indicator = progress
        scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule(timer@{
            if (disposed || project.isDisposed || run != generation) return@timer
            running = AppExecutorUtil.getAppExecutorService().submit(worker@{
                if (disposed || project.isDisposed || run != generation) return@worker
                val timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({ progress.cancel() }, 15, TimeUnit.SECONDS)
                val result = try {
                    ProgressManager.getInstance().runProcess(Computable { readDiff(target) }, progress)
                } catch (_: ProcessCanceledException) {
                    GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
                } catch (error: Exception) {
                    LOG.debug("Cannot read project tab changed-line count", error)
                    GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
                } finally { timeout.cancel(false) }
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && !project.isDisposed && run == generation && selection == target) {
                        status = result
                        indicator = null
                        changed()
                    }
                }, ModalityState.any())
            })
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun readDiff(target: Selection): GitTabDiffStatus {
        ProgressManager.checkCanceled()
        val handler = GitLineHandler(project, Path.of(target.root), GitCommand.DIFF).apply {
            addParameters("--numstat", "--no-ext-diff", "--no-textconv", "--no-color", target.revision!!, "--")
            addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
            setSilent(true)
            withLowPriority()
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
        var added = 0L
        var removed = 0L
        for (line in result.output) {
            ProgressManager.checkCanceled()
            if (line.isEmpty()) continue
            val first = line.indexOf('\t')
            val second = if (first >= 0) line.indexOf('\t', first + 1) else -1
            if (first < 0 || second < 0) return GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
            val plus = line.substring(0, first)
            val minus = line.substring(first + 1, second)
            if (plus == "-" && minus == "-") continue // Git не считает строки бинарных файлов.
            val additions = plus.toLongOrNull()?.takeIf { it >= 0 } ?: return GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
            val deletions = minus.toLongOrNull()?.takeIf { it >= 0 } ?: return GitTabDiffStatus(GitTabDiffState.UNAVAILABLE)
            added = Math.addExact(added, additions)
            removed = Math.addExact(removed, deletions)
        }
        return GitTabDiffStatus(GitTabDiffState.READY, added, removed)
    }

    private fun cancelWork() {
        generation++
        indicator?.cancel()
        scheduled?.cancel(false)
        running?.cancel(true)
        scheduled = null
        running = null
        indicator = null
    }

    companion object { private val LOG = Logger.getInstance(GitTabStatusWatch::class.java) }
}

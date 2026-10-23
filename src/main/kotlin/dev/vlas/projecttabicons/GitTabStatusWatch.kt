package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcsUtil.VcsUtil
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Выбранный IDE Git-корень читается на EDT; локальный numstat вычисляется только в фоне. */
internal class GitTabStatusWatch(
    private val project: Project,
    parent: Disposable,
    private val changed: () -> Unit,
) {
    private data class Selection(val root: String, val revision: String?)
    @Volatile private var disposed = false
    @Volatile private var selection: Selection? = null
    @Volatile private var repository: GitRepository? = null
    private val diffTask = GitTabDiffTask(
        submit = { task -> AppExecutorUtil.getAppExecutorService().submit(Runnable(task)) },
        timeout = { cancel ->
            val timer = AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable(cancel), 15, TimeUnit.SECONDS)
            val stop: () -> Unit = { timer.cancel(false); Unit }
            stop
        },
        post = { task -> ApplicationManager.getApplication().invokeLater(Runnable(task), ModalityState.any()) },
        report = { LOG.debug("Cannot read project tab changed-line count", it) },
    )
    private val refresh = GitTabStatusRefresh(
        nowMillis = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        schedule = { delayMillis, callback ->
            val timer = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && !project.isDisposed) callback()
                }, ModalityState.any())
            }, delayMillis, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { timer.cancel(false); Unit }
            cancel
        },
        start = ::start,
        changed = changed,
    )

    init {
        Disposer.register(parent, Disposable {
            disposed = true
            cancelWork()
            ApplicationManager.getApplication().invokeLater({
                refresh.dispose()
                repository = null
                selection = null
            }, ModalityState.any())
        })
    }

    fun snapshot(): GitTabDiffStatus? {
        if (disposed || project.isDisposed) return null
        val repository = GitBranchUtil.getCurrentRepository(project)
        this.repository = repository
        val next = repository?.let { Selection(it.root.path, it.currentRevision) }
        if (next != selection) {
            cancelWork()
            selection = next
            refresh.reset(when {
                next == null -> null
                next.revision == null -> GitTabDiffStatus(GitTabDiffState.NO_COMMITS)
                else -> GitTabDiffStatus(GitTabDiffState.LOADING)
            })
            if (next?.revision != null) refresh.invalidate()
        }
        return refresh.status
    }

    fun affects(events: List<VFileEvent>): Boolean {
        val root = selection?.root ?: return false
        val repository = repository?.takeIf { it.root.path == root }
        return events.any { event ->
            val paths = when (event) {
                is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
                else -> listOf(event.path)
            }
            paths.any { path ->
                affectsGitTabStatusPath(root, path, event is VFileContentChangeEvent) { candidate ->
                    val file = VcsUtil.getFilePath(candidate, false)
                    val ignored = repository?.ignoredFilesHolder
                    val untracked = repository?.untrackedFilesHolder
                    (ignored != null && ignored.initialized && !ignored.isInUpdateMode() && ignored.containsFile(file)) ||
                        (untracked != null && untracked.isInitialized && !untracked.isInUpdateMode && untracked.containsUntrackedFile(file))
                }
            }
        }
    }

    fun invalidate() {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) {
                val previous = selection
                snapshot()
                // snapshot уже запланировал новый корень; тот же корень пересчитываем после события.
                if (selection == previous && selection?.revision != null) refresh.invalidate()
            }
        }, ModalityState.any())
    }

    private fun start(run: Long) {
        if (disposed || project.isDisposed) return
        val target = selection ?: return
        diffTask.start(read = { progress ->
            ProgressManager.getInstance().runProcess(Computable { readDiff(target) }, progress)
        }, publish = { result ->
            if (!disposed && !project.isDisposed && run == refresh.generation && selection == target) {
                refresh.complete(run, result)
            }
        })
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
        return parseGitNumstat(result.output) { ProgressManager.checkCanceled() }
    }

    private fun cancelWork() {
        diffTask.cancel()
    }

    companion object { private val LOG = Logger.getInstance(GitTabStatusWatch::class.java) }
}

internal fun parseGitNumstat(lines: List<String>, checkCanceled: () -> Unit = {}): GitTabDiffStatus {
    var added = 0L
    var removed = 0L
    try {
        for (line in lines) {
            checkCanceled()
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
    } catch (_: ArithmeticException) { return GitTabDiffStatus(GitTabDiffState.UNAVAILABLE) }
}

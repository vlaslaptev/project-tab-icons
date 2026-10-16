package dev.vlas.projecttabicons

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.ConcurrentHashMap

/** Ветка — из готовой модели на EDT. Дополнительный фоновый diff включён только в Git status. */
internal class GitProjectTabBranchProvider : ProjectTabBranchProvider {
    private val statuses = ConcurrentHashMap<Project, GitTabStatusWatch>()

    override fun currentBranch(project: Project): String? =
        GitBranchUtil.getCurrentRepository(project)?.currentBranch?.name

    override fun currentStatus(project: Project): GitTabDiffStatus? = statuses[project]?.snapshot()

    override fun watch(project: Project, parent: Disposable, includeStatus: Boolean, changed: () -> Unit): Disposable {
        val lifetime = Disposer.newDisposable("Project tab Git info")
        Disposer.register(parent, lifetime)
        val status = if (includeStatus) GitTabStatusWatch(project, lifetime, changed).also { watch ->
            statuses[project] = watch
            Disposer.register(lifetime, Disposable { statuses.remove(project, watch) })
        } else null
        fun invalidate() { status?.invalidate(); changed() }
        project.messageBus.connect(lifetime).apply {
            subscribe(GitRepository.GIT_REPO_CHANGE, object : GitRepositoryChangeListener {
                override fun repositoryChanged(repository: GitRepository) = invalidate()
            })
            subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, object : VcsRepositoryMappingListener {
                override fun mappingChanged() = invalidate()
            })
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = changed()
            })
            if (status != null) {
                subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
                    override fun changeListUpdateDone() = status.invalidate()
                })
            }
        }
        if (status != null) {
            // VFS — application-level topic; фильтруем события по текущему Git-корню.
            ApplicationManager.getApplication().messageBus.connect(lifetime)
                .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (status.affects(events)) status.invalidate()
                    }
                })
        }
        return lifetime
    }
}

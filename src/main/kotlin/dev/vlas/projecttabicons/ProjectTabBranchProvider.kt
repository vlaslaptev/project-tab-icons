package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Git остаётся необязательным: основной UI не загружает классы выключенного Git-плагина. */
internal interface ProjectTabBranchProvider {
    /** null — нет репозитория; branchName == null — репозиторий без выбранной ветки. */
    fun currentRepository(project: Project): ProjectTabRepositoryInfo?
    fun currentStatus(project: Project): GitTabDiffStatus?
    fun watch(project: Project, parent: Disposable, includeStatus: Boolean, changed: () -> Unit): Disposable

    companion object {
        val EP_NAME = ExtensionPointName.create<ProjectTabBranchProvider>("dev.vlas.projecttabicons.branchProvider")
    }
}

internal data class ProjectTabRepositoryInfo(val branchName: String?)

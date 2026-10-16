package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Git остаётся необязательным: основной UI не загружает классы выключенного Git-плагина. */
internal interface ProjectTabBranchProvider {
    fun currentBranch(project: Project): String?
    fun currentStatus(project: Project): GitTabDiffStatus?
    fun watch(project: Project, parent: Disposable, includeStatus: Boolean, changed: () -> Unit): Disposable

    companion object {
        val EP_NAME = ExtensionPointName.create<ProjectTabBranchProvider>("dev.vlas.projecttabicons.branchProvider")
    }
}

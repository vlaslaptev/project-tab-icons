package dev.vlas.projecttabicons

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.ui.tabs.impl.JBTabsImpl

/** Marker for a tab strip whose selected project is independent of its AWT owner. */
internal interface ManagedProjectTabs

/** The shared decorator never links to Win32 implementation classes. */
internal interface PlatformProjectTabsBackend : AutoCloseable {
    val isOrdering: Boolean
    fun update(frames: Map<IdeFrameImpl, Project>, groups: ProjectGroupsState)
    fun tabsFor(frame: IdeFrameImpl): JBTabsImpl?
    fun cancelInteractions()
    fun hide()
    fun arrange()
    suspend fun joinAndArrangeGroupedProjects(anchor: Project, projects: List<Project>): Boolean
}

internal interface PlatformProjectTabsFactory {
    fun create(
        changed: () -> Unit,
        orderRestored: (JBTabsImpl, Boolean) -> Unit,
        dragFinished: (JBTabsImpl, Boolean) -> Unit,
    ): PlatformProjectTabsBackend

    companion object {
        val EP_NAME = ExtensionPointName.create<PlatformProjectTabsFactory>("dev.vlas.projecttabicons.platformTabs")
    }
}

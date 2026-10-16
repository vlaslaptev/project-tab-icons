package dev.vlas.projecttabicons

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfoRt

class ProjectTabIconsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (SystemInfoRt.isMac && !project.isDisposed) {
            service<ProjectTabIconsService>().requestRefresh()
        }
    }
}

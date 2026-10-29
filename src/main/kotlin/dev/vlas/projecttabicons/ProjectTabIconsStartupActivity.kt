package dev.vlas.projecttabicons

import com.intellij.openapi.components.service
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfoRt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProjectTabIconsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if ((SystemInfoRt.isMac || SystemInfoRt.isWindows) && !project.isDisposed) {
            service<ProjectTabIconsService>().requestRefresh()
            if (SystemInfoRt.isMac) withContext(Dispatchers.EDT) {
                service<MacAutomaticProjectTabs>().projectOpened(project)
            }
        }
    }
}

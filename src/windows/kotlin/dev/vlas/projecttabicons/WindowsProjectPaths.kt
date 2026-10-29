package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Match IDEA's project-window identity: the .ipr file or the directory-based project root. */
internal fun windowsProjectPath(project: Project): Path? = RecentProjectsManagerBase.getInstanceEx().getProjectPath(project)

internal fun windowsProjectPathKey(project: Project): String? = windowsProjectPath(project)?.let(::projectIconPathKey)

package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.DeferredIconImpl
import com.intellij.util.ui.AvatarIcon

internal fun openProjectIconTargets(): List<OpenProjectIconTarget> {
        val recent = RecentProjectsManagerBase.getInstanceEx()
        return ProjectManager.getInstance().openProjects.filterNot { it.isDisposed || it.isDefault }.map { target ->
            val path = recent.getProjectPath(target)
            val delegate = path?.let { recent.getProjectIcon(it, true, 28, target.name) }
            val resolved = if (delegate is DeferredIconImpl<*>) delegate.currentlyPaintedIcon() else delegate
            OpenProjectIconTarget(target, path, delegate?.let { CenteredProjectIcon(it, 24, projectName = { target.name }) },
                (resolved as? AvatarIcon)?.presentation?.color1)
        }
    }

internal fun loadProjectIcon(target: OpenProjectIconTarget, dark: Boolean): LoadedProjectIcon {
        val path = target.path ?: return LoadedProjectIcon(target, null, null, null,
            "A local project directory is required to save an icon.")
        return try {
            val snapshot = ProjectIconStore.snapshot(path)
            val svg = try {
                snapshot.currentSvg(dark)
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: org.xml.sax.SAXException) {
                null
            }
            val file = snapshot.currentFile(dark)
            val color = try {
                when {
                    svg != null -> svgIconColor(svg)
                    file?.endsWith(".png") == true -> sampleIconColor(snapshot.files.getValue(file), false)
                    file == null -> target.automaticColor
                    else -> null
                }
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // PNG тоже получает подготовленную копию: renderer больше не рисует delegate.
            val prepared = if (svg == null && file?.endsWith(".png") == true) {
                val image = RecentProjectIconHelper.createIcon(snapshot.files.getValue(file), false, 24)
                target.copy(preview = (target.preview as? CenteredProjectIcon)?.withArtwork(image) ?: image)
            } else target
            LoadedProjectIcon(prepared, snapshot, svg?.let { IconChoice(it, color) }, color)
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: Exception) {
            LoadedProjectIcon(target, null, null, null, error.message ?: "Cannot read this project's icon.")
        }
    }


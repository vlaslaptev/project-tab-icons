package dev.vlas.projecttabicons

import com.intellij.configurationStore.ProjectStorePathManager
import com.intellij.ide.ProjectWindowCustomizerService
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.IconDeferrer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class IconSnapshot(val directory: Path, val files: Map<String, ByteArray>) {
    val fingerprint: String = PROJECT_ICON_FILES.joinToString("|") { name ->
        "$name:${files[name]?.let(::sha256) ?: "absent"}"
    }

    fun currentFile(dark: Boolean): String? {
        val lookup = if (dark) PROJECT_ICON_FILES.drop(2) + PROJECT_ICON_FILES.take(2) else PROJECT_ICON_FILES.take(2)
        return lookup.firstOrNull(files::containsKey)
    }

    fun currentSvg(dark: Boolean): SvgAsset? {
        val file = currentFile(dark) ?: return null
        if (!file.endsWith(".svg")) return null
        return SvgAsset.validate(files.getValue(file), "Current project · $file")
    }
}

internal data class ProjectIconSource(val path: Path, val title: String, val snapshot: IconSnapshot? = null)
internal data class SvgGallery(val assets: List<SvgAsset>, val skipped: Int)
internal data class ProjectIconWrite(val project: Project, val path: Path, val expected: IconSnapshot, val asset: SvgAsset)

/** Файловые операции выполняются в фоне; refreshUi вызывается на EDT. */
internal object ProjectIconStore {
    /** Проверяем весь набор до первой записи; при отказе возвращаем уже изменённые проекты. */
    fun installAll(changes: List<ProjectIconWrite>): Map<Path, IconSnapshot> {
        require(changes.map { it.expected.directory.toAbsolutePath().normalize() }.distinct().size == changes.size) {
            "Two projects share the same icon storage directory. Apply their icons separately."
        }
        changes.forEach { change ->
            require(!change.project.isDisposed) { "A selected project has been closed." }
            val current = snapshot(change.path)
            require(current.fingerprint == change.expected.fingerprint && current.directory == change.expected.directory) {
                "The icon for ${change.project.name} changed outside these settings. Reload its row before applying."
            }
        }
        val installed = linkedMapOf<Path, IconSnapshot>()
        try {
            changes.forEach { change ->
                require(!change.project.isDisposed) { "A selected project has been closed." }
                installed[change.path] = install(change.path, change.expected, change.asset)
            }
            return installed
        } catch (error: Exception) {
            changes.asReversed().forEach { change ->
                val written = installed[change.path] ?: return@forEach
                try {
                    val current = snapshot(change.path)
                    require(current.fingerprint == written.fingerprint && current.directory == written.directory) {
                        "A project icon changed after saving; automatic recovery did not overwrite the newer file."
                    }
                    PROJECT_ICON_FILES.forEach { name ->
                        val old = change.expected.files[name]
                        val target = current.directory.resolve(name)
                        if (old == null) Files.deleteIfExists(target) else writeAtomically(target, old)
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
    }

    fun directory(projectPath: Path): Path {
        if (!Files.isDirectory(projectPath) && projectPath.parent != null) {
            // IDEA поддерживает также .ipr-проекты с вложенным хранилищем.
            val stem = projectPath.fileName.toString().substringBeforeLast('.')
            val nested = projectPath.parent.resolve(".idea/.idea.$stem/.idea")
            if (Files.isDirectory(nested)) return nested
        }
        return requireNotNull(ProjectStorePathManager.getInstance().getStoreDescriptor(projectPath).dotIdea) {
            "This project does not have a local icon storage directory."
        }
    }

    fun snapshot(projectPath: Path): IconSnapshot {
        val directory = directory(projectPath)
        val files = PROJECT_ICON_FILES.mapNotNull { name ->
            val path = directory.resolve(name)
            if (Files.exists(path)) name to readIconBytes(path) else null
        }.toMap()
        return IconSnapshot(directory, files)
    }

    fun gallery(sources: List<ProjectIconSource>): SvgGallery {
        val byDigest = linkedMapOf<String, SvgAsset>()
        var skipped = 0
        for (source in sources) {
            try {
                val snapshot = source.snapshot
                val directory = snapshot?.directory ?: directory(source.path)
                for (name in listOf("icon.svg", "icon_dark.svg")) {
                    try {
                        val label = "${source.title} · $name"
                        val asset = if (snapshot != null) {
                            snapshot.files[name]?.let { SvgAsset.validate(it, label) }
                        } else {
                            // Даже если другой файл проекта не прочитан, его исправный SVG доступен в галерее.
                            directory.resolve(name).takeIf { Files.exists(it) }?.let { SvgAsset.read(it, label) }
                        } ?: continue
                        val previous = byDigest[asset.digest]
                        byDigest[asset.digest] = if (previous == null) asset else previous.copy(
                            label = "${previous.label}; ${asset.label}",
                        )
                    } catch (_: IllegalArgumentException) {
                        skipped++
                    } catch (_: java.io.IOException) {
                        skipped++
                    } catch (_: org.xml.sax.SAXException) {
                        skipped++
                    }
                }
            } catch (_: java.io.IOException) {
                skipped++
            } catch (_: IllegalArgumentException) {
                skipped++
            }
        }
        return SvgGallery(byDigest.values.toList(), skipped)
    }

    fun install(projectPath: Path, expected: IconSnapshot, asset: SvgAsset): IconSnapshot {
        val before = snapshot(projectPath)
        require(before.fingerprint == expected.fingerprint && before.directory == expected.directory) {
            "The project icon changed outside these settings. Reload the current icon before applying your selection."
        }
        Files.createDirectories(before.directory)
        try {
            writeAtomically(before.directory.resolve("icon.svg"), asset.bytes)
            // Старые PNG и dark-варианты не должны перекрывать явно выбранный SVG.
            PROJECT_ICON_FILES.filter { it != "icon.svg" }.forEach { name ->
                Files.deleteIfExists(before.directory.resolve(name))
            }
            return snapshot(projectPath)
        } catch (error: Exception) {
            // Возвращаем все прежние варианты при частичном отказе записи.
            PROJECT_ICON_FILES.forEach { name ->
                try {
                    val old = before.files[name]
                    val file = before.directory.resolve(name)
                    if (old == null) {
                        Files.deleteIfExists(file)
                    } else if (!Files.exists(file) || !readIconBytes(file).contentEquals(old)) {
                        writeAtomically(file, old)
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(target.parent, ".project-tab-icons-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun refreshUi(project: Project, projectPath: Path, snapshot: IconSnapshot) {
        LocalFileSystem.getInstance().refreshNioFiles(listOf(snapshot.directory), true, true, null)
        RecentProjectIconHelper.refreshProjectIcon(projectPath)
        IconDeferrer.getInstance().clearCache()
        if (project.isDisposed) return
        val customizer = ProjectWindowCustomizerService.getInstance()
        customizer.dropProjectIconCache(project)
        customizer.setIconMainColorAsProjectColor(project)
        service<ProjectTabIconsService>().requestRefresh(invalidate = true)
    }
}

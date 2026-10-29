package dev.vlas.projecttabicons

import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.Path

internal data class IconConfigurationEvents(val files: List<String>, val directories: List<String>) {
    fun affects(observed: Set<String>): Boolean = files.any { isObservedIconConfiguration(it, observed) } ||
        directories.any { changed -> observed.any { isInsideIconDirectory(it, changed) } ||
            observed.isEmpty() && changed.substringAfterLast('/') == ".idea" }
}

/** В after() event.path уже новый. Каталоги сопоставляются по пути, без обхода VFS/диска. */
internal fun iconConfigurationEvents(events: List<VFileEvent>): IconConfigurationEvents {
    val files = linkedSetOf<String>()
    val directories = linkedSetOf<String>()
    events.forEach { event ->
        val paths = when (event) {
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
            else -> listOf(event.path)
        }.map(::iconEventPath)
        if (event.file?.isDirectory == true || event is VFileCreateEvent && event.isDirectory) directories.addAll(paths)
        else files.addAll(paths.filter(::isIconConfiguration))
    }
    return IconConfigurationEvents(files.toList(), directories.toList())
}

private fun isInsideIconDirectory(path: String, parent: String): Boolean = path == parent || path.startsWith("${parent.trimEnd('/')}/")

internal fun projectIconDirectories(path: Path): Set<String> {
    val value = iconEventPath(path.toAbsolutePath().normalize().toString())
    if (!value.endsWith(".ipr", ignoreCase = true)) return setOf(iconEventPath("$value/.idea"))
    val parent = value.substringBeforeLast('/')
    val stem = value.substringAfterLast('/').substringBeforeLast('.')
    return setOf(iconEventPath("$parent/.idea"), iconEventPath("$parent/.idea/.idea.$stem/.idea"))
}

internal fun isIconConfiguration(path: String): Boolean {
    val normalized = iconEventPath(path)
    return normalized.contains("/.idea/", ignoreCase = com.intellij.openapi.util.SystemInfoRt.isWindows) &&
        iconConfigurationName(normalized) in PROJECT_ICON_CONFIGURATION_FILES
}

internal fun isObservedIconConfiguration(path: String, directories: Set<String>): Boolean {
    val normalized = iconEventPath(path)
    return iconConfigurationName(normalized) in PROJECT_ICON_CONFIGURATION_FILES &&
        (normalized.substringBeforeLast('/') in directories || directories.isEmpty() && isIconConfiguration(normalized))
}

/** Только сравнение путей: обработчик событий и EDT не обращаются к файловой системе. */
internal fun <T> affectedIconProjects(projectPaths: Map<T, Path>, events: Collection<String>,
                                      directoryEvents: Collection<String> = emptyList()): Set<T> {
    val directories = events.filter(::isIconConfiguration).map { iconEventPath(it).substringBeforeLast('/') }.toSet()
    return projectPaths.filterValues { path ->
        projectIconDirectories(path).any { directory -> directory in directories ||
            directoryEvents.any { isInsideIconDirectory(directory, iconEventPath(it)) } }
    }.keys
}

private val PROJECT_ICON_CONFIGURATION_FILES = setOf("icon.svg", "icon.png", "icon_dark.svg", "icon_dark.png", ".name")

private fun iconConfigurationName(path: String): String {
    val name = path.substringAfterLast('/')
    return if (cachedWindowsDirectoryIgnoresCase(path.substringBeforeLast('/'))) name.lowercase(java.util.Locale.ROOT) else name
}

private fun iconEventPath(path: String): String = canonicalProjectPathKey(
    path.replace('\\', '/').trimEnd('/'), com.intellij.openapi.util.SystemInfoRt.isWindows)

package dev.vlas.projecttabicons

import java.nio.file.Path

internal fun isIconConfiguration(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    return normalized.contains("/.idea/") && normalized.substringAfterLast('/') in PROJECT_ICON_CONFIGURATION_FILES
}

internal fun isObservedIconConfiguration(path: String, directories: Set<String>): Boolean {
    val normalized = path.replace('\\', '/')
    return normalized.substringAfterLast('/') in PROJECT_ICON_CONFIGURATION_FILES &&
        (normalized.substringBeforeLast('/') in directories || directories.isEmpty() && isIconConfiguration(normalized))
}

/** Только сравнение путей: обработчик событий и EDT не обращаются к файловой системе. */
internal fun <T> affectedIconProjects(projectPaths: Map<T, Path>, events: Collection<String>): Set<T> {
    val directories = events.filter(::isIconConfiguration).map { it.replace('\\', '/').substringBeforeLast('/') }.toSet()
    return projectPaths.filterValues { path ->
        val value = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        if (value.endsWith(".ipr", ignoreCase = true)) {
            val parent = value.substringBeforeLast('/')
            val stem = value.substringAfterLast('/').substringBeforeLast('.')
            "$parent/.idea" in directories || "$parent/.idea/.idea.$stem/.idea" in directories
        } else "$value/.idea" in directories
    }.keys
}

private val PROJECT_ICON_CONFIGURATION_FILES = setOf("icon.svg", "icon.png", "icon_dark.svg", "icon_dark.png", ".name")

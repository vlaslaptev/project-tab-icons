package dev.vlas.projecttabicons

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

internal fun projectIconPathKey(path: Path): String = canonicalProjectPathKey(
    path.toAbsolutePath().normalize().toString(), SystemInfoRt.isWindows)

/**
 * Регистр задаёт VFS конкретного каталога, а не ОС: NTFS и WSL допускают API рядом с api.
 * Только cached lookup, без refresh, toRealPath и дискового I/O на EDT. Если VFS ещё
 * не знает путь, сохраняем написание; загруженные настройки разрешаются повторно при чтении.
 * POSIX-ключи из Settings Sync на Windows не преобразуются.
 */
internal fun canonicalProjectPathKey(path: String, windows: Boolean,
                                     cachedPath: (String) -> String? = ::cachedWindowsProjectPath): String {
    if (!windows) return path
    val normalized = path.replace('\\', '/')
    if (!(normalized.startsWith("//") || normalized.length >= 3 &&
            normalized[0].isLetter() && normalized[1] == ':' && normalized[2] == '/')) return path
    return cachedPath(normalized) ?: normalized
}

private fun cachedWindowsProjectPath(path: String): String? {
    if (!SystemInfoRt.isWindows || ApplicationManager.getApplication() == null) return null
    val fileSystem = LocalFileSystem.getInstance()
    var prefix = path
    while (prefix.isNotEmpty()) {
        val file = fileSystem.findFileByPathIfCached(prefix)?.takeIf { it.isValid }
        if (file != null) {
            val suffix = path.substring(prefix.length)
            return if (suffix.startsWith('/')) file.path.trimEnd('/') + suffix else file.path + suffix
        }
        val slash = prefix.lastIndexOf('/')
        if (slash < 2) break
        prefix = prefix.substring(0, slash)
    }
    return null
}

/** Неизвестный каталог считаем чувствительным: недостаток кеша не должен склеить имена. */
internal fun cachedWindowsDirectoryIgnoresCase(path: String): Boolean =
    SystemInfoRt.isWindows && ApplicationManager.getApplication() != null &&
        LocalFileSystem.getInstance().findFileByPathIfCached(path)?.let { it.isValid && !it.isCaseSensitive } == true

internal fun <T> normalizedProjectPathKeys(values: Map<String, T>, windows: Boolean,
                                         cachedPath: (String) -> String? = ::cachedWindowsProjectPath): Map<String, T> =
    if (windows) values.mapKeys { canonicalProjectPathKey(it.key, true, cachedPath) } else values

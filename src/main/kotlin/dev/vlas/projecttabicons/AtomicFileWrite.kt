package dev.vlas.projecttabicons

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Узкая граница файловых операций позволяет воспроизводить отказы без chmod и смены ФС. */
internal open class IconFileOperations {
    open fun write(path: Path, bytes: ByteArray) { Files.write(path, bytes) }
    open fun move(source: Path, target: Path, vararg options: CopyOption) { Files.move(source, target, *options) }
    open fun delete(path: Path) { Files.deleteIfExists(path) }
}

/** Сначала готовим файл рядом с целью. Неатомарная замена имеет резервную копию для отката. */
internal fun writeFileAtomically(target: Path, bytes: ByteArray, files: IconFileOperations = IconFileOperations()) {
    val destination = target.toAbsolutePath()
    val temporary = Files.createTempFile(destination.parent, ".project-tab-icons-", ".tmp")
    try {
        files.write(temporary, bytes)
        try {
            files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            val backup = if (Files.exists(destination)) {
                Files.createTempFile(destination.parent, ".project-tab-icons-backup-", ".tmp").also {
                    try { Files.copy(destination, it, REPLACE_EXISTING) }
                    catch (error: Exception) { Files.deleteIfExists(it); throw error }
                }
            } else null
            var preserveBackup = false
            try {
                files.move(temporary, destination, REPLACE_EXISTING)
            } catch (error: Exception) {
                try {
                    if (backup == null) Files.deleteIfExists(destination)
                    else Files.copy(backup, destination, REPLACE_EXISTING)
                } catch (recovery: Exception) {
                    // Если сама ФС уже не принимает запись, оставляем единственную копию данных.
                    preserveBackup = true
                    error.addSuppressed(java.io.IOException("Previous file retained at $backup", recovery))
                }
                throw error
            } finally {
                if (backup != null && !preserveBackup) Files.deleteIfExists(backup)
            }
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

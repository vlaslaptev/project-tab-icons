package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.xmlb.annotations.OptionTag
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal const val MAX_SET_ICONS = 500
internal const val MAX_LIBRARY_BYTES = 16 * 1024 * 1024
internal const val MAX_LIBRARY_SETS = 50
internal const val MAX_ICON_SET_NAME_LENGTH = 64

internal data class ImportedIconSet(val id: String, val name: String, val icons: List<IconChoice>)
internal data class IconSetImport(val set: ImportedIconSet, val skipped: List<String>, val duplicates: Int)
internal data class StoredSetIcon(@field:OptionTag @JvmField val name: String = "", @field:OptionTag @JvmField val svg: String = "")
internal data class StoredIconSet(@field:OptionTag @JvmField val id: String = "", @field:OptionTag @JvmField val name: String = "",
                                 @field:OptionTag @JvmField val icons: List<StoredSetIcon> = emptyList())
internal data class IconSetLibraryState(@field:OptionTag @JvmField val sets: List<StoredIconSet> = emptyList())

/** Общая библиотека хранит копии SVG; исходную папку после импорта можно переместить. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsIconSets", storages = [Storage("projectTabIconSets.xml")])
internal class IconSetLibrary : SerializablePersistentStateComponent<IconSetLibraryState>(IconSetLibraryState()) {
    @Volatile private var decoded: Pair<IconSetLibraryState, List<ImportedIconSet>>? = null
    fun apply(value: IconSetLibraryState) = updateState { value }

    /** Вызывается в IO-потоке, как и чтение иконок проектов. Повреждённый SVG не ломает остальные наборы. */
    fun readSets(): List<ImportedIconSet> {
        val snapshot = state
        decoded?.takeIf { it.first === snapshot }?.let { return it.second }
        var bytes = 0
        val result = snapshot.sets.take(MAX_LIBRARY_SETS).mapNotNull { stored ->
            val icons = stored.icons.take(MAX_SET_ICONS).mapNotNull { icon ->
                if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
                if (icon.svg.length > MAX_ICON_BYTES * 4 / 3 + 8 || bytes >= MAX_LIBRARY_BYTES) return@mapNotNull null
                try {
                    val raw = Base64.getDecoder().decode(icon.svg)
                    bytes += raw.size
                    if (bytes > MAX_LIBRARY_BYTES) null else {
                        val asset = SvgAsset.validate(raw, "${icon.name} · ${stored.name}")
                        IconChoice(asset, svgIconColor(asset))
                    }
                } catch (error: ProcessCanceledException) { throw error }
                catch (_: Exception) { null }
            }
            if (icons.isEmpty() || stored.id.isBlank() || stored.name.isBlank()) null
            else ImportedIconSet(stored.id, stored.name, icons)
        }
        if (state === snapshot) decoded = snapshot to result
        return result
    }

    fun prepare(additions: List<ImportedIconSet>): IconSetLibraryState {
        val snapshot = state
        require(additions.all { it.icons.size <= MAX_SET_ICONS }) { "Too many icons in a category." }
        val existing = snapshot.sets.filterNot { stored -> additions.any { it.id == stored.id } }
        require(existing.size + additions.size <= MAX_LIBRARY_SETS) { "The icon library can contain up to $MAX_LIBRARY_SETS imported categories." }
        val encoded = additions.map { set -> StoredIconSet(set.id, set.name, set.icons.map {
            StoredSetIcon(it.asset.label.substringBeforeLast(" · "), Base64.getEncoder().encodeToString(it.asset.bytes))
        }) }
        require((existing + encoded).sumOf { set -> set.icons.sumOf { it.svg.length.toLong() } } <= MAX_LIBRARY_BYTES * 4L / 3) {
            "The imported icon library is full (16 MiB). Import a smaller folder."
        }
        val replacements = encoded.associateBy { it.id }
        // Замены остаются на прежних местах; добавления идут в конец библиотеки.
        return IconSetLibraryState(snapshot.sets.map { replacements[it.id] ?: it } +
            encoded.filter { addition -> snapshot.sets.none { it.id == addition.id } })
    }
}

/** Сканирование ограничено по глубине, числу файлов и суммарному размеру; ссылки не обходятся. */
internal fun importIconSet(folder: Path, names: Set<String>): IconSetImport {
    require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) { "Choose a local folder containing SVG icons." }
    val files = mutableListOf<Path>()
    Files.walk(folder, 12).use { paths ->
        val iterator = paths.iterator()
        var visited = 0
        while (iterator.hasNext()) {
            if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
            val path = iterator.next()
            require(++visited <= 10000) { "This folder contains too many files. Choose a smaller icon folder." }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".svg", true)) {
                files.add(path)
                require(files.size <= MAX_SET_ICONS) { "Import up to $MAX_SET_ICONS SVG files at a time. Choose a smaller folder." }
            }
        }
    }
    require(files.isNotEmpty()) { "No SVG files found. Choose a folder containing .svg files." }
    val base = folder.fileName?.toString().orEmpty().filterNot { it.isISOControl() }.trim().ifBlank { "My icons" }
    val name = uniqueStyleName(base, names, MAX_ICON_SET_NAME_LENGTH)
    val icons = linkedMapOf<String, IconChoice>()
    val skipped = mutableListOf<String>()
    var duplicates = 0
    var total = 0
    files.sortedBy { folder.relativize(it).toString().lowercase(Locale.ROOT) }.forEach { path ->
        if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
        val relative = folder.relativize(path).toString()
        try {
            val asset = SvgAsset.read(path, "${relative.substringBeforeLast('.')} · $name")
            if (asset.digest in icons) duplicates++ else {
                total += asset.bytes.size
                require(total <= MAX_LIBRARY_BYTES) { "The folder's SVG files exceed 16 MiB. Choose a smaller folder." }
                // Проверяем также отрисовку до добавления результата в категорию.
                icons[asset.digest] = IconChoice(asset, svgIconColor(asset))
            }
        } catch (error: ProcessCanceledException) { throw error }
        catch (error: Exception) {
            if (total > MAX_LIBRARY_BYTES) throw error
            skipped += "$relative: ${svgImportErrorMessage(error)}"
        }
    }
    require(icons.isNotEmpty()) { "No usable SVG icons found. ${skipped.firstOrNull()?.substringAfter(": ").orEmpty()}" }
    return IconSetImport(ImportedIconSet(UUID.randomUUID().toString(), name, icons.values.toList()), skipped, duplicates)
}

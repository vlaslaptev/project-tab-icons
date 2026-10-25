package dev.vlas.projecttabicons

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.xmlb.annotations.OptionTag
import java.awt.Color
import java.util.Base64
import javax.swing.Icon

/** Собственная копия SVG группы: проекты ссылаются на неё, не меняя свои .idea/icon.*. */
internal data class ProjectGroupIcon(
    @field:OptionTag @JvmField val svg: String = "",
    @field:OptionTag @JvmField val name: String = "Folder",
    @field:OptionTag @JvmField val color: String = "#60A5FA",
) {
    fun normalized(): ProjectGroupIcon = if (svg.length > MAX_ICON_BYTES * 4 / 3 + 8) ProjectGroupIcon() else copy(
        name = name.filterNot { it.isISOControl() }.takeCodePoints(128).ifBlank { "Group icon" },
        color = parseIconColor(color)?.hex().orEmpty(),
    )
}
internal data class PreparedGroupIcon(val choice: IconChoice, val artwork: Map<Int, Icon>)

/** SVG проверяются в фоне; растр видимого размера запрашивается через ProjectIconRasterCache. */
internal object GroupAppearanceIcons {
    private val cache = object : LinkedHashMap<ProjectGroupIcon, PreparedGroupIcon>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProjectGroupIcon, PreparedGroupIcon>?) = size > 128
    }
    private fun cached(icon: ProjectGroupIcon): PreparedGroupIcon? = synchronized(cache) { cache[icon] }
    fun choice(icon: ProjectGroupIcon): IconChoice? = cached(icon)?.choice
    fun touch(groups: ProjectGroupsState) {
        synchronized(cache) {
            cache[ProjectGroupIcon()]
            groups.groups.forEach { cache[it.icon] }
        }
    }

    fun store(choice: IconChoice): ProjectGroupIcon {
        require(choice.asset.bytes.size <= MAX_ICON_BYTES) { "The group icon is too large." }
        val stored = ProjectGroupIcon(Base64.getEncoder().encodeToString(choice.asset.bytes), choice.asset.label,
            choice.color?.hex().orEmpty()).normalized()
        synchronized(cache) { cache[stored] = PreparedGroupIcon(choice, emptyMap()) }
        return stored
    }

    fun prepare(groups: ProjectGroupsState): Map<ProjectGroupIcon, PreparedGroupIcon> =
        (listOf(ProjectGroupIcon()) + groups.groups.map { it.icon }).distinct().associateWith(::prepareIcon)

    private fun prepareIcon(stored: ProjectGroupIcon): PreparedGroupIcon {
        cached(stored)?.takeIf { it.artwork.isNotEmpty() }?.let { return it }
        if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
        val choice = cached(stored)?.choice ?: try {
            if (stored.svg.isBlank()) defaultChoice() else {
                val asset = SvgAsset.validate(Base64.getDecoder().decode(stored.svg), stored.name)
                IconChoice(asset, svgIconColor(asset))
            }
        } catch (error: ProcessCanceledException) { throw error }
        catch (_: Exception) { defaultChoice() }
        return PreparedGroupIcon(choice, listOf(20, 24, 28).associateWith {
            projectSvgIcon(choice.asset, it)
        }).also { synchronized(cache) { cache[stored] = it } }
    }
    private fun defaultChoice(): IconChoice {
        val color = Color(0x60A5FA)
        val folder = ContourCatalog.entries.first { it.id == "folder" }
        return IconChoice(SvgAsset(folder.svg(color), folder.title, BuilderSelection(folder.id, color.hex(), false)), color)
    }
}

internal fun ProjectTabGroup.colors(choice: IconChoice): ProjectColorOverrides = ProjectColorOverrides(
    icon = choice.color, outline = parseIconColor(outlineColor), tab = parseIconColor(tabColor),
)
internal fun ProjectGroupsState.groupFor(key: String): ProjectTabGroup? = memberships[key]?.let { id -> groups.firstOrNull { it.id == id } }

package dev.vlas.projecttabicons

import com.intellij.ide.ProjectFileIcon
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.DeferredIconImpl
import com.intellij.ui.LayeredIcon
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.EmptyIcon
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.swing.Icon

internal data class IconColorRead(val color: Color? = null, val avatar: AvatarIcon? = null,
                                  val artwork: Icon? = null)

/** Ограниченное чтение только активного варианта; цвет avatar читается отдельно на EDT. */
internal fun projectIconColor(delegate: Icon, unscaledSize: Int, path: Path, dark: Boolean): IconColorRead {
    val resolved = if (delegate is DeferredIconImpl<*>) {
        // Загрузку запускает loadProjectIconWithoutPainting; retrieveIcon не выполняет async evaluator.
        if (!delegate.isDone) throw IconColorNotReady()
        delegate.currentlyPaintedIcon()
    } else delegate
    // FullProjectAvatarPresentation читает ProjectWindowCustomizerService: его цвет нужен на EDT.
    if (resolved is AvatarIcon) return IconColorRead(avatar = resolved)
    when (resolved) {
        is ProjectFileIcon -> resolved
        is LayeredIcon -> if (resolved.iconCount == 2 && resolved.getIcon(0) is EmptyIcon) {
            resolved.getIcon(1) as? ProjectFileIcon
        } else null
        else -> null
    } ?: return IconColorRead()
    val selected = selectedProjectIconFile(ProjectIconStore.directory(path), dark) ?: return IconColorRead()
    val data = readIconBytes(selected)
    val svg = selected.fileName.toString().endsWith(".svg")
    if (svg) {
        val asset = SvgAsset.validate(data, "Project icon")
        // Даже SVG без старого фона рисуем только из проверенной копии байтов.
        // Исходный ProjectFileIcon не используется в paint и больше не обходит очистку.
        val artwork = projectSvgIcon(asset, unscaledSize)
        val color = (asset.textIcon?.colorHex ?: asset.builder?.colorHex)?.let(::parseIconColor) ?: sampleIconColor(data, true)
        return IconColorRead(color = color, artwork = artwork)
    }
    return IconColorRead(color = sampleIconColor(data, false),
        artwork = projectPngIcon(data, unscaledSize))
}

/** IO-поток; используется также для цвета в таблице и предпросмотра выбранного SVG. */
internal fun svgIconColor(asset: SvgAsset): Color? =
    (asset.textIcon?.colorHex ?: asset.builder?.colorHex)?.let(::parseIconColor) ?: sampleIconColor(asset.bytes, true)

internal fun sampleIconColor(data: ByteArray, svg: Boolean): Color? {
    // Отдельный экземпляр: offscreen-выбор цвета не сбрасывает HiDPI-кеш иконки вкладки.
    val sample = RecentProjectIconHelper.createIcon(data, svg, 64)
    val image = BufferedImage(sample.iconWidth, sample.iconHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        sample.paintIcon(null, graphics, 0, 0)
    } catch (error: ProcessCanceledException) {
        throw error
    } finally {
        graphics.dispose()
    }
    return dominantIconColor(image)
}

/** Подложку с alpha=0.5 игнорируем; среди непрозрачных цветов предпочитаем цветные пиксели. */
private fun dominantIconColor(image: BufferedImage): Color? {
    val colored = mutableMapOf<Int, ColorBucket>()
    val neutral = mutableMapOf<Int, ColorBucket>()
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val argb = image.getRGB(x, y)
        val alpha = argb ushr 24
        if (alpha < 160) continue
        val color = Color(argb, true)
        val saturation = Color.RGBtoHSB(color.red, color.green, color.blue, null)[1]
        val buckets = if (saturation >= 0.15f) colored else neutral
        val key = ((color.red shr 3) shl 10) or ((color.green shr 3) shl 5) or (color.blue shr 3)
        val bucket = buckets.getOrPut(key) { ColorBucket() }
        bucket.weight += alpha.toLong()
        bucket.red += color.red.toLong() * alpha
        bucket.green += color.green.toLong() * alpha
        bucket.blue += color.blue.toLong() * alpha
    }
    val buckets = if (colored.isNotEmpty()) colored else neutral
    val dominant = buckets.values.maxByOrNull { it.weight } ?: return null
    return Color((dominant.red / dominant.weight).toInt(), (dominant.green / dominant.weight).toInt(),
        (dominant.blue / dominant.weight).toInt())
}

private class ColorBucket {
    var weight = 0L
    var red = 0L
    var green = 0L
    var blue = 0L
}

internal fun selectedProjectIconFile(directory: Path, dark: Boolean): Path? {
    val names = if (dark) PROJECT_ICON_FILES.drop(2) + PROJECT_ICON_FILES.take(2) else PROJECT_ICON_FILES.take(2)
    return names.asSequence().map(directory::resolve).firstOrNull { Files.exists(it) }
}

internal class IconColorNotReady : Exception(null, null, false, false)

package dev.vlas.projecttabicons

import java.awt.Color
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal enum class ContourLicense(val resourcePath: String) {
    PHOSPHOR("/META-INF/Phosphor-LICENSE.txt"),
    DEVICON("/META-INF/Devicon-LICENSE.txt"),
    CC0("/META-INF/CC0-1.0.txt"),
    OWN("/META-INF/LICENSE.txt"),
}

internal data class Contour(
    val id: String,
    val title: String,
    private val template: String,
    private val license: ContourLicense,
    val category: String,
    private val keywords: String,
) {
    fun matches(query: String): Boolean {
        val searchable = "$title $id $category $keywords".lowercase(Locale.ROOT)
        return query.lowercase(Locale.ROOT).split(Regex("\\s+")).all { it in searchable }
    }

    fun svg(color: Color): ByteArray {
        val hex = color.hex()
        // Экспортируемый SVG содержит лицензию: его можно хранить отдельно от плагина.
        val attribution = "<!--\n${ContourCatalog.licenses.getValue(license).replace("--", "")}\n-->\n"
        val result = template.replace("currentColor", hex).replaceFirst(
            "<svg",
            "<svg data-project-tab-icons-contour=\"$id\" data-project-tab-icons-color=\"$hex\" " +
                "data-project-tab-icons-background=\"none\"",
        )
        return (attribution + result).toByteArray(StandardCharsets.UTF_8)
    }
}

internal object ContourCatalog {
    val entries: List<Contour> by lazy {
        resource("/icons/catalog.tsv").lineSequence().filter(String::isNotBlank).map { line ->
            val fields = line.split('\t')
            require(fields.size == 5) { "Expected icon ID, title, license, category and keywords" }
            val (id, title, license, category, keywords) = fields
            require(id.matches(Regex("[a-z0-9-]+")))
            Contour(id, title, resource("/icons/contours/$id.svg"), ContourLicense.valueOf(license), category, keywords)
        }.toList().also { entries ->
            require(entries.isNotEmpty() && entries.size <= 289 && entries.map { it.id }.toSet().size == entries.size)
        }
    }

    val licenses: Map<ContourLicense, String> by lazy {
        ContourLicense.entries.associateWith { resource(it.resourcePath) }
    }

    private fun resource(path: String): String = requireNotNull(javaClass.getResourceAsStream(path)) {
        "Missing bundled icon resource: $path"
    }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

internal data class ColorPreset(val name: String, val bright: Color, val dark: Color)

internal val COLOR_PRESETS = listOf(
    ColorPreset("Blue", Color(0x60A5FA), Color(0x1E40AF)),
    ColorPreset("Orange", Color(0xFB923C), Color(0x9A3412)),
    ColorPreset("Green", Color(0x4ADE80), Color(0x166534)),
    ColorPreset("Cyan", Color(0x22D3EE), Color(0x155E75)),
    ColorPreset("Purple", Color(0xC084FC), Color(0x6B21A8)),
    ColorPreset("Pink", Color(0xF472B6), Color(0x9D174D)),
    ColorPreset("Yellow", Color(0xFACC15), Color(0x854D0E)),
)

internal fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", rgb and 0xFFFFFF)

private val DARK_ICON_BACKGROUND = Color(0x12161E)
private val LIGHT_ICON_BACKGROUND = Color(0xF1F5F9)

/** Контрастный цвет отметки выбора в палитре; не добавляет подложку к иконке. */
internal fun iconBackground(color: Color): Color {
    val foreground = color.luminance()
    fun contrast(background: Color): Double {
        val light = background.luminance()
        return (max(foreground, light) + 0.05) / (min(foreground, light) + 0.05)
    }
    return if (contrast(DARK_ICON_BACKGROUND) >= contrast(LIGHT_ICON_BACKGROUND)) {
        DARK_ICON_BACKGROUND
    } else LIGHT_ICON_BACKGROUND
}

private fun Color.luminance(): Double {
    fun linear(value: Int): Double {
        val channel = value / 255.0
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}

internal fun parseIconColor(text: String): Color? =
    text.trim().takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let(Color::decode)

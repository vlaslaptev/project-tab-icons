package dev.vlas.projecttabicons

import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.util.Base64
import java.util.Locale
import kotlin.math.min

internal const val MAX_TEXT_ICON_LENGTH = 6

internal enum class TextIconStyle(val title: String, val awtStyle: Int) {
    REGULAR("Regular", Font.PLAIN), BOLD("Bold", Font.BOLD),
    ITALIC("Italic", Font.ITALIC), BOLD_ITALIC("Bold italic", Font.BOLD or Font.ITALIC);
    override fun toString(): String = title
}

internal enum class TextIconFont(val title: String, val family: String) {
    SANS("Sans serif", Font.SANS_SERIF), SERIF("Serif", Font.SERIF), MONO("Monospace", Font.MONOSPACED);
    override fun toString(): String = title
}

internal fun textIconError(text: String): String? = when {
    text.isBlank() -> "Enter 1–$MAX_TEXT_ICON_LENGTH characters, for example DB, QA or API."
    text.codePointCount(0, text.length) > MAX_TEXT_ICON_LENGTH -> "Use at most $MAX_TEXT_ICON_LENGTH characters."
    text.any { it.isISOControl() } || text.codePoints().anyMatch { it in 0xD800..0xDFFF } ->
        "Use printable characters without line breaks."
    else -> null
}

/** Контуры глифов сохраняются в SVG: размер, шрифт и платформа не меняют готовую иконку. */
internal data class TextIconSpec(val text: String, val style: TextIconStyle, val font: TextIconFont, val colorHex: String) {
    fun asset(): SvgAsset {
        require(textIconError(text) == null) { textIconError(text).orEmpty() }
        val color = requireNotNull(parseIconColor(colorHex))
        val layout = TextLayout(text, Font(font.family, style.awtStyle, 64), FontRenderContext(null, true, true))
        val shape = layout.getOutline(null)
        val bounds = shape.bounds2D
        require(!bounds.isEmpty) { "The text has no visible characters." }
        val scale = min(20.0 / bounds.width, 20.0 / bounds.height)
        val transform = AffineTransform().apply {
            translate(12.0 - bounds.centerX * scale, 12.0 - bounds.centerY * scale)
            scale(scale, scale)
        }
        val path = shape.getPathIterator(transform)
        val coordinates = DoubleArray(6)
        val data = StringBuilder()
        while (!path.isDone) {
            val (command, count) = when (path.currentSegment(coordinates)) {
                PathIterator.SEG_MOVETO -> "M" to 2
                PathIterator.SEG_LINETO -> "L" to 2
                PathIterator.SEG_QUADTO -> "Q" to 4
                PathIterator.SEG_CUBICTO -> "C" to 6
                else -> "Z" to 0
            }
            data.append(command)
            repeat(count) { index ->
                if (index > 0) data.append(' ')
                data.append(String.format(Locale.ROOT, "%.4f", coordinates[index]))
            }
            path.next()
        }
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" data-project-tab-icons-text="$encoded" data-project-tab-icons-text-style="${style.name}" data-project-tab-icons-text-font="${font.name}" data-project-tab-icons-color="${color.hex()}"><path fill="${color.hex()}" d="$data"/></svg>"""
        return SvgAsset(svg.toByteArray(Charsets.UTF_8), "$text · ${font.title}, ${style.title}", textIcon = copy(colorHex = color.hex()))
    }
}

internal fun readTextIconSpec(root: org.w3c.dom.Element, color: Color?): TextIconSpec? = try {
    val encoded = root.getAttribute("data-project-tab-icons-text")
    if (encoded.length !in 1..128 || color == null) null else {
        val text = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        if (textIconError(text) != null) null else TextIconSpec(text,
            TextIconStyle.valueOf(root.getAttribute("data-project-tab-icons-text-style")),
            TextIconFont.valueOf(root.getAttribute("data-project-tab-icons-text-font")), color.hex())
    }
} catch (_: IllegalArgumentException) { null }

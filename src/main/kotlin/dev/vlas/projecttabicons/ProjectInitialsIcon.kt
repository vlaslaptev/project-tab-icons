package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.text.BreakIterator
import java.util.Locale
import javax.swing.Icon
import kotlin.math.min

/** Один renderer для буквенных аватаров IDEA и постоянного примера в Preview. */
internal class ProjectInitialsIcon(
    private val shortName: String,
    private val color: Color,
    private val unscaledSize: Int,
    private val initials: TabIconInitials,
    private val weight: TabIconLetterWeight,
    private val projectName: String = shortName,
) : Icon {
    private val painter = ProjectInitialsPainter()

    override fun getIconWidth(): Int = JBUI.scale(unscaledSize)
    override fun getIconHeight(): Int = JBUI.scale(unscaledSize)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (graphics !is Graphics2D) return
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            painter.paint(g, shortName, color, unscaledSize, initials, weight, projectName)
        } finally { g.dispose() }
    }
}

/** Один последний layout на иконку; цвет меняется без повторного расчёта глифов. */
internal class ProjectInitialsPainter {
    private data class LayoutKey(val shortName: String, val projectName: String, val initials: TabIconInitials,
                                 val font: Font, val context: FontRenderContext, val size: Float)
    private data class PositionedLayout(val layout: TextLayout, val x: Float, val y: Float)
    private var cachedKey: LayoutKey? = null
    private var cachedLayout: PositionedLayout? = null

    fun paint(g: Graphics2D, shortName: String, color: Color, unscaledSize: Int,
              initials: TabIconInitials, weight: TabIconLetterWeight, projectName: String = shortName) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // Прежние размеры букв сохранены: 16/20/24 px для слотов Small/Medium/Large.
        val textSize = JBUI.scale((unscaledSize - 4).coerceAtLeast(1)).toFloat()
        val font = UIUtil.getLabelFont().deriveFont(weight.fontStyle, textSize)
        val key = LayoutKey(shortName, projectName, initials, font, g.fontRenderContext, JBUI.scale(unscaledSize).toFloat())
        if (key != cachedKey) {
            cachedLayout = createLayout(key)
            cachedKey = key
        }
        val positioned = cachedLayout ?: return
        g.color = color
        positioned.layout.draw(g, positioned.x, positioned.y)
    }

    private fun createLayout(key: LayoutKey): PositionedLayout? {
        val text = projectIconLetters(key.shortName, key.projectName, key.initials)
        if (text.isEmpty()) return null
        val reference = TextLayout(text, key.font, key.context).bounds
        if (reference.isEmpty) return null
        val available = key.font.size2D * 0.84f
        val ratio = min(available / reference.width, available / reference.height).toFloat()
        val layout = TextLayout(text, key.font.deriveFont(key.font.size2D * ratio), key.context)
        val bounds = layout.bounds
        return PositionedLayout(layout, ((key.size - bounds.width) / 2 - bounds.x).toFloat(),
            ((key.size - bounds.height) / 2 - bounds.y).toFloat())
    }
}

/** Два регистра используют одинаковые инициалы и одинаковый fallback для простого имени. */
internal fun projectIconLetters(shortName: String, projectName: String, initials: TabIconInitials): String {
    val ideLetters = initialCharacters(shortName, initials.count)
    val name = projectName.trim()
    val useNameStart = initials != TabIconInitials.ONE_LETTER && ideLetters.size < 2 &&
        SINGLE_WORD.matches(name) && name.codePoints().skip(1).noneMatch { Character.isUpperCase(it) }
    val letters = if (useNameStart) initialCharacters(name, initials.count) else ideLetters
    return when (initials) {
        TabIconInitials.ONE_LETTER -> letters.joinToString("")
        TabIconInitials.TWO_INITIALS -> letters.joinToString("").uppercase(Locale.ROOT)
        TabIconInitials.NAME_PREFIX -> letters.mapIndexed { index, letter ->
            if (index == 0) letter.uppercase(Locale.ROOT) else letter.lowercase(Locale.ROOT)
        }.joinToString("")
    }
}

private val SINGLE_WORD = Regex("[\\p{L}\\p{N}\\p{M}]+")

private fun initialCharacters(value: String, limit: Int): List<String> {
    // Не разрезаем суррогатные пары и составные буквы.
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    val result = ArrayList<String>(limit)
    var start = characters.first()
    var end = characters.next()
    while (end != BreakIterator.DONE && result.size < limit) {
        result.add(value.substring(start, end))
        start = end
        end = characters.next()
    }
    return result
}

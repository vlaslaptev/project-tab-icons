package dev.vlas.projecttabicons

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

/** Декорация, а не вторая IDE: нет обработчиков, фокуса, редактора или реальных файлов. */
internal object PreviewWindowChrome {
    const val TOOLBAR_HEIGHT = 40

    fun paint(graphics: Graphics2D, width: Int, height: Int, tabBottom: Int, palette: PreviewThemePalette) {
        val g = graphics.create() as Graphics2D
        try {
            val scale = JBUIScale.scale(1f).toDouble()
            g.scale(scale, scale)
            val w = width / scale
            val h = height / scale
            val bottom = tabBottom / scale
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = palette.stripBackground
            g.fillRect(0, 0, w.toInt(), h.toInt())
            toolbar(g, w, palette.chrome)
            editor(g, w, h, bottom + 6, palette.chrome)
        } finally { g.dispose() }
    }

    private fun toolbar(g: Graphics2D, width: Double, colors: PreviewChromePalette) {
        g.color = colors.toolbarBackground
        g.fillRect(0, 0, width.toInt(), TOOLBAR_HEIGHT)
        // Всегда видимый macOS-светофор; кнопки намеренно не реагируют на наведение/клики.
        listOf(0xFF5F57, 0xFEBC2E, 0x28C840).forEachIndexed { index, rgb ->
            val circle = Ellipse2D.Double(17.0 + index * 20, 14.0, 12.0, 12.0)
            g.color = Color(rgb)
            g.fill(circle)
            g.color = Color(0, 0, 0, 28)
            g.draw(circle)
        }
        val text = g.create() as Graphics2D
        try {
            // Длинные подписи никогда не заходят на правые действия при узком Settings.
            text.clipRect(91, 0, (width - 208).toInt().coerceAtLeast(0), TOOLBAR_HEIGHT)
            text.color = colors.toolbarForeground
            text.font = JBFont.medium().deriveFont(13f)
            text.drawString("Backend", 92, 25)
            chevron(text, 157.0, 19.0)
            if (width >= 470) {
                text.color = colors.muted
                branch(text, 188.0, 15.0)
                text.font = JBFont.regular().deriveFont(12f)
                text.drawString("main", 207, 25)
                chevron(text, 242.0, 19.0)
            }
        } finally { text.dispose() }
        val actions = g.create() as Graphics2D
        try {
            actions.clipRect((width - 202).toInt().coerceAtLeast(270), 0, 202, TOOLBAR_HEIGHT)
            if (width >= 620) {
                actions.font = JBFont.regular().deriveFont(12f)
                actions.color = colors.toolbarForeground
                actions.drawString("Backend", (width - 197).toFloat(), 25f)
                chevron(actions, width - 136, 19.0)
            }
            actions.color = Color(0x59A869)
            actions.draw(Path2D.Double().apply {
                moveTo(width - 105, 14.0); lineTo(width - 105, 26.0)
                lineTo(width - 95, 20.0); closePath()
            })
            actions.color = colors.toolbarForeground
            actions.draw(Ellipse2D.Double(width - 73, 14.0, 10.0, 10.0))
            actions.draw(Line2D.Double(width - 64, 23.0, width - 60, 27.0))
            repeat(3) { i -> actions.fill(Ellipse2D.Double(width - 33, 13.0 + i * 6, 2.5, 2.5)) }
        } finally { actions.dispose() }
    }

    private fun editor(graphics: Graphics2D, width: Double, height: Double, top: Double,
                       colors: PreviewChromePalette) {
        val g = graphics.create() as Graphics2D
        try {
            val card = RoundRectangle2D.Double(8.0, top, (width - 16).coerceAtLeast(0.0),
                (height - top - 8).coerceAtLeast(0.0), 10.0, 10.0)
            g.clip(card)
            g.color = colors.editorBackground
            g.fill(card)
            g.color = colors.fileTabsBackground
            g.fillRect(8, top.toInt(), (width - 16).toInt().coerceAtLeast(0), 30)
            // Узкая полоса tool windows и вкладки файлов дают контекст под проектными табами.
            g.fillRect(8, top.toInt(), 32, (height - top).toInt().coerceAtLeast(0))
            g.color = colors.muted
            g.draw(Path2D.Double().apply {
                moveTo(18.0, top + 10); lineTo(23.0, top + 10); lineTo(25.0, top + 12)
                lineTo(31.0, top + 12); lineTo(31.0, top + 22); lineTo(18.0, top + 22); closePath()
            })
            g.color = colors.selectedFileBackground
            g.fillRect(40, top.toInt(), 141, 30)
            g.color = colors.border
            g.draw(Line2D.Double(40.0, top + 30, width - 8, top + 30))
            g.draw(Line2D.Double(40.0, top, 40.0, height - 8))
            g.color = colors.accent
            g.fillRect(48, (top + 28).toInt(), 125, 2)
            g.font = JBFont.regular().deriveFont(12f)
            file(g, 53.0, top + 9, colors.accent)
            g.color = colors.editorForeground
            g.drawString("README.md", 72f, (top + 20).toFloat())
            g.color = colors.muted
            close(g, 164.0, top + 15)
            file(g, 193.0, top + 9, colors.muted)
            g.drawString("build.gradle.kts", 212f, (top + 20).toFloat())
            val content = g.create() as Graphics2D
            try {
                content.clipRect(41, (top + 31).toInt(), (width - 49).toInt().coerceAtLeast(0),
                    (height - top - 39).toInt().coerceAtLeast(0))
                content.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                val lines = listOf("# Backend workspace", "Three projects. One window.")
                lines.forEachIndexed { index, line ->
                    val baseline = (top + 50 + index * 18).toFloat()
                    content.color = colors.muted
                    content.drawString("${index + 1}", 58f, baseline)
                    content.color = colors.editorForeground
                    content.drawString(line, 87f, baseline)
                }
            } finally { content.dispose() }
        } finally { g.dispose() }
    }

    private fun chevron(g: Graphics2D, x: Double, y: Double) = g.draw(Path2D.Double().apply {
        moveTo(x, y); lineTo(x + 3, y + 3); lineTo(x + 6, y)
    })

    private fun branch(g: Graphics2D, x: Double, y: Double) {
        g.draw(Ellipse2D.Double(x, y, 4.0, 4.0))
        g.draw(Ellipse2D.Double(x + 7, y + 2, 4.0, 4.0))
        g.draw(Line2D.Double(x + 2, y + 4, x + 2, y + 12))
        g.draw(Path2D.Double().apply {
            moveTo(x + 2, y + 9); curveTo(x + 9, y + 9, x + 9, y + 9, x + 9, y + 6)
        })
    }

    private fun close(g: Graphics2D, x: Double, y: Double) {
        g.draw(Line2D.Double(x - 3, y - 3, x + 3, y + 3))
        g.draw(Line2D.Double(x + 3, y - 3, x - 3, y + 3))
    }

    private fun file(g: Graphics2D, x: Double, y: Double, color: Color) {
        g.color = color
        g.draw(Path2D.Double().apply {
            moveTo(x, y); lineTo(x + 6, y); lineTo(x + 10, y + 4)
            lineTo(x + 10, y + 12); lineTo(x, y + 12); closePath()
            moveTo(x + 6, y); lineTo(x + 6, y + 4); lineTo(x + 10, y + 4)
        })
    }
}

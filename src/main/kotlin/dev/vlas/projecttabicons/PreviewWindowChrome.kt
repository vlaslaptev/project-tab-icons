package dev.vlas.projecttabicons

import com.intellij.ui.scale.JBUIScale
import com.intellij.openapi.util.SystemInfoRt
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

    fun paint(graphics: Graphics2D, width: Int, height: Int, tabBottom: Int, palette: PreviewThemePalette,
              activeTitle: String, activeBranch: String, tabCount: Int, showEditor: Boolean = true) {
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
            val windows = SystemInfoRt.isWindows
            if (windows) windowsCaption(g, w, bottom, palette)
            val toolbarGraphics = g.create() as Graphics2D
            try {
                if (windows) toolbarGraphics.translate(0.0, bottom)
                toolbar(toolbarGraphics, w, palette.chrome, activeTitle, activeBranch)
            } finally { toolbarGraphics.dispose() }
            val contentTop = bottom + if (windows) TOOLBAR_HEIGHT else 0
            if (showEditor) editor(g, w, h, contentTop + 6, palette.chrome, activeTitle, tabCount)
        } finally { g.dispose() }
    }

    private fun windowsCaption(g: Graphics2D, width: Double, height: Double, palette: PreviewThemePalette) {
        val buttonWidth = WindowsCaptionMetrics.PREVIEW_BUTTON_WIDTH.toDouble()
        val centerY = height / 2
        g.color = palette.foreground
        val minimizeX = width - buttonWidth * 2.5
        g.draw(Line2D.Double(minimizeX - 5, centerY, minimizeX + 5, centerY))
        val maximizeX = width - buttonWidth * 1.5
        g.draw(java.awt.geom.Rectangle2D.Double(maximizeX - 5, centerY - 5, 10.0, 10.0))
        val closeX = width - buttonWidth / 2
        g.draw(Line2D.Double(closeX - 5, centerY - 5, closeX + 5, centerY + 5))
        g.draw(Line2D.Double(closeX + 5, centerY - 5, closeX - 5, centerY + 5))
    }

    private fun toolbar(g: Graphics2D, windowWidth: Double, colors: PreviewChromePalette, activeTitle: String, activeBranch: String) {
        val width = windowWidth
        g.color = colors.toolbarBackground
        g.fillRect(0, 0, windowWidth.toInt(), TOOLBAR_HEIGHT)
        if (SystemInfoRt.isWindows) {
            file(g, 20.0, 14.0, colors.accent)
            g.color = colors.toolbarForeground
            repeat(3) { index -> g.draw(Line2D.Double(48.0, 15.0 + index * 5, 62.0, 15.0 + index * 5)) }
        } else {
            // macOS-превью сохраняет прежнюю геометрию и цвета.
            listOf(0xFF5F57, 0xFEBC2E, 0x28C840).forEachIndexed { index, rgb ->
                val circle = Ellipse2D.Double(17.0 + index * 20, 14.0, 12.0, 12.0)
                g.color = Color(rgb)
                g.fill(circle)
                g.color = Color(0, 0, 0, 28)
                g.draw(circle)
            }
        }
        val text = g.create() as Graphics2D
        try {
            // Длинные подписи никогда не заходят на правые действия при узком Settings.
            text.clipRect(91, 0, (width - 208).toInt().coerceAtLeast(0), TOOLBAR_HEIGHT)
            text.color = colors.toolbarForeground
            text.font = JBFont.medium().deriveFont(13f)
            val title = fitLabel(text, activeTitle, (width - 316).toInt())
            text.drawString(title, 92, 25)
            val titleEnd = 92.0 + text.fontMetrics.stringWidth(title)
            chevron(text, titleEnd + 10, 19.0)
            val branchX = titleEnd + 40
            val branchWidth = (width - 208 - branchX - 35).toInt()
            if (width >= 470 && branchWidth >= 60) {
                text.color = colors.muted
                branch(text, branchX, 15.0)
                text.font = JBFont.regular().deriveFont(12f)
                val label = fitLabel(text, activeBranch, branchWidth)
                text.drawString(label, (branchX + 19).toFloat(), 25f)
                chevron(text, branchX + 19 + text.fontMetrics.stringWidth(label) + 10, 19.0)
            }
        } finally { text.dispose() }
        val actions = g.create() as Graphics2D
        try {
            actions.clipRect((width - 202).toInt().coerceAtLeast(270), 0, 202, TOOLBAR_HEIGHT)
            if (width >= 620) {
                actions.font = JBFont.regular().deriveFont(12f)
                actions.color = colors.toolbarForeground
                actions.drawString(fitLabel(actions, activeTitle, 52), (width - 197).toFloat(), 25f)
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
                       colors: PreviewChromePalette, activeTitle: String, tabCount: Int) {
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
                val caption = "$tabCount projects. One window."
                val lines = listOf("# $activeTitle workspace", caption)
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

    private fun fitLabel(g: Graphics2D, value: String, width: Int): String {
        if (g.fontMetrics.stringWidth(value) <= width) return value
        var prefix = value
        while (prefix.isNotEmpty() && g.fontMetrics.stringWidth("$prefix…") > width) prefix = prefix.dropLast(1)
        return if (prefix.isEmpty()) "" else "$prefix…"
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

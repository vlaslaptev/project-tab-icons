package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.border.AbstractBorder

/** Место под точку постоянно: изменение значения не сдвигает соседние поля. */
internal class SettingsPendingChange(private val component: JComponent) {
    private var pending = false
    private val description = component.accessibleContext.accessibleDescription
    private val tooltip = component.toolTipText

    init {
        // Checkbox/radio и лёгкие действия строк по умолчанию не рисуют border.
        if (component is AbstractButton) component.isBorderPainted = true
        component.border = BorderFactory.createCompoundBorder(component.border, object : AbstractBorder() {
            override fun getBorderInsets(c: Component): Insets = getBorderInsets(c, Insets(0, 0, 0, 0))

            override fun getBorderInsets(c: Component, insets: Insets): Insets = insets.apply {
                top = 0; left = 0; bottom = 0; right = JBUI.scale(12)
            }

            override fun paintBorder(c: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
                if (!pending) return
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                    val diameter = JBUI.scale(5)
                    val trailing = x + width - JBUI.scale(8)
                    val dotX = if (c is AbstractButton) minOf(trailing, x + c.insets.left +
                        (c.icon?.iconWidth ?: JBUI.scale(20)) + c.iconTextGap +
                        c.getFontMetrics(c.font).stringWidth(c.text.orEmpty()) + JBUI.scale(4)) else trailing
                    g.fillOval(dotX, y + (height - diameter) / 2, diameter, diameter)
                } finally { g.dispose() }
            }
        })
    }

    fun update(value: Boolean) {
        if (pending == value) return
        pending = value
        component.accessibleContext.accessibleDescription = if (value) {
            listOfNotNull(description, "Unsaved change. Apply to save.").joinToString(" ")
        } else description
        component.toolTipText = if (value) {
            listOfNotNull(tooltip, "Unsaved change. Apply to save.").joinToString(" ")
        } else tooltip
        component.repaint()
    }
}

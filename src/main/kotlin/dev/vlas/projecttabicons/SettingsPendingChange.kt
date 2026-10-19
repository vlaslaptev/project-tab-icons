package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.geom.Ellipse2D
import javax.swing.JComponent
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.BorderFactory
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.plaf.basic.BasicRadioButtonUI

/** Место под точку постоянно: изменение значения не сдвигает соседние поля. */
internal class SettingsPendingChange(private val component: JComponent) {
    private var pending = false
    private val description = component.accessibleContext.accessibleDescription
    private val tooltip = component.toolTipText
    private var contentBorder = component.border
    private val markerBorder = object : AbstractBorder() {
        override fun getBorderInsets(c: Component): Insets = getBorderInsets(c, Insets(0, 0, 0, 0))

        override fun getBorderInsets(c: Component, insets: Insets): Insets = insets.apply {
            top = 0; left = 0; bottom = 0; right = 0
            if (c.componentOrientation.isLeftToRight) right = JBUI.scale(MARKER_SPACE)
            else left = JBUI.scale(MARKER_SPACE)
        }

        override fun paintBorder(c: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
            if (!pending || width <= 0 || height <= 0) return
            val g = graphics.create() as Graphics2D
            try {
                val caption = captionBounds(width, height) ?: return
                val diameter = JBUI.scale(MARKER_SIZE).toDouble()
                if (width < diameter || height < diameter) return
                val dotX = x + if (c.componentOrientation.isLeftToRight)
                    caption.maxX + JBUI.scale(MARKER_GAP)
                else caption.minX - JBUI.scale(MARKER_GAP) - diameter
                // В тесной ячейке не прижимаем точку к тексту или поверх иконки.
                if (dotX < x || dotX + diameter > x + width) return
                val baseline = component.getBaseline(width, height)
                val centerY = if (baseline >= 0) baseline + component.font
                    .createGlyphVector(g.fontRenderContext, "x").visualBounds.centerY else caption.centerY
                val dotY = (y + centerY - diameter / 2).coerceIn(y.toDouble(), y + height - diameter)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (component.isEnabled) JBColor.namedColor("Component.focusColor", JBColor.BLUE)
                    else UIUtil.getLabelDisabledForeground()
                g.fill(Ellipse2D.Double(dotX, dotY, diameter, diameter))
            } finally { g.dispose() }
        }
    }

    init {
        // Checkbox/radio и лёгкие действия строк по умолчанию не рисуют border.
        if (component is AbstractButton) component.isBorderPainted = true
        component.putClientProperty(MARKER_KEY, this)
        refreshBorder()
    }

    /** Та же геометрия текста, что использует Swing, включая штатную иконку checkbox/radio и многоточие. */
    private fun captionBounds(width: Int, height: Int): Rectangle? {
        val insets = component.insets
        val view = Rectangle(insets.left, insets.top,
            (width - insets.left - insets.right).coerceAtLeast(0),
            (height - insets.top - insets.bottom).coerceAtLeast(0))
        val icon = Rectangle()
        val text = Rectangle()
        val metrics = component.getFontMetrics(component.font)
        when (val c = component) {
            is JLabel -> SwingUtilities.layoutCompoundLabel(c, metrics, c.text, c.icon,
                c.verticalAlignment, c.horizontalAlignment, c.verticalTextPosition, c.horizontalTextPosition,
                view, icon, text, c.iconTextGap)
            is AbstractButton -> {
                val artwork = c.icon ?: (c.ui as? BasicRadioButtonUI)?.defaultIcon ?: when (c) {
                    is JCheckBox -> UIManager.getIcon("CheckBox.icon")
                    is JRadioButton -> UIManager.getIcon("RadioButton.icon")
                    else -> null
                }
                SwingUtilities.layoutCompoundLabel(c, metrics, c.text, artwork,
                    c.verticalAlignment, c.horizontalAlignment, c.verticalTextPosition, c.horizontalTextPosition,
                    view, icon, text, c.iconTextGap)
            }
            else -> return null
        }
        return text.takeIf { it.width > 0 && it.height > 0 } ?: icon.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun refreshBorder() {
        component.border = BorderFactory.createCompoundBorder(markerBorder, contentBorder)
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

    companion object {
        private val MARKER_KEY = Any()
        private const val MARKER_SIZE = 4
        private const val MARKER_GAP = 6
        private const val MARKER_SPACE = MARKER_GAP + MARKER_SIZE + 4
        val reservedWidth: Int get() = JBUI.scale(MARKER_SPACE)

        /** Компактная раскладка меняет только исходные отступы, сохраняя индикатор. */
        fun setContentBorder(component: JComponent, border: Border) {
            val marker = component.getClientProperty(MARKER_KEY) as? SettingsPendingChange
            if (marker != null) {
                if (marker.contentBorder === border) return
                marker.contentBorder = border
                marker.refreshBorder()
            } else if (component.border !== border) component.border = border
        }
    }
}

package dev.vlas.projecttabicons

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants

/** Два независимых набора контролов: смена варианта не перезаписывает его значения. */
internal class GroupHighlightControls(style: GroupHighlightStyle, changed: () -> Unit) {
    private val joined = style == GroupHighlightStyle.JOINED
    private val defaults = GroupHighlightOptions()
    private class Slider(title: String, name: String, min: Int, max: Int, initial: Int, val unit: String = "%") {
        val control = JSlider(min, max, initial).apply {
            minorTickSpacing = 1; snapToTicks = true; isOpaque = false
            accessibleContext.accessibleName = name
        }
        val label = JBLabel(title).apply { labelFor = control }
        val value = JBLabel("", SwingConstants.RIGHT)
        fun field(width: Int, height: Int): AppearanceField = AppearanceField(label, JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            value.preferredSize = Dimension(value.getFontMetrics(value.font).stringWidth("100 $unit"), height)
            add(control, BorderLayout.CENTER); add(value, BorderLayout.EAST)
            preferredSize = Dimension(width, height)
        })
        fun refresh(enabled: Boolean) {
            listOf(control, label, value).forEach { it.isEnabled = enabled }
            value.text = "${control.value}${if (unit == "%") "" else " "}$unit"
        }
    }
    private val inset = Slider("Card inset:", "Active card inset in pixels", 0, 6, defaults.cardInset, "px")
    private val dividers = JBCheckBox("Show internal dividers", defaults.showDividers)
    private val dividerIntensity = Slider("Divider intensity:", "Internal divider intensity", 0, 100, defaults.dividerIntensity)
    private val dividerHeight = Slider("Divider height:", "Internal divider height", 20, 100, defaults.dividerHeight)
    private val hideAdjacent = JBCheckBox("Hide dividers beside active tab", defaults.hideDividersNearActive)
    private val sliders = if (joined) listOf(dividerIntensity, dividerHeight) else listOf(inset)
    private val checks = if (joined) listOf(dividers, hideAdjacent) else emptyList()

    init {
        inset.control.toolTipText = "Space around the active card: 0–6 px. 0 px sits flush with the group surface."
        dividers.toolTipText = "Draw thin lines between joined tabs. Does not affect pipes between groups."
        dividerHeight.control.toolTipText = "Line height relative to the capsule: 20–100%."
        dividerIntensity.control.toolTipText = "Internal line intensity: 0–100%."
        hideAdjacent.toolTipText = "Hide both internal lines touching the active tab. Other dividers remain visible."
        sliders.forEach { it.control.addChangeListener { changed() } }
        checks.forEach { it.isOpaque = false; it.addActionListener { changed() } }
    }

    fun labelWidth(): Int = sliders.maxOf { it.label.preferredSize.width }

    fun form(controlWidth: Int, rowHeight: Int, labelWidth: () -> Int, availableWidth: () -> Int): JComponent {
        val fields = if (joined) listOf(
            AppearanceField(null, dividers), dividerIntensity.field(controlWidth, rowHeight),
            dividerHeight.field(controlWidth, rowHeight), AppearanceField(null, hideAdjacent),
        ) else listOf(inset.field(controlWidth, rowHeight))
        return SettingsAppearanceForm(fields.first().control, fields.drop(1), labelWidth, controlWidth,
            rowHeight, availableWidth, fields.first().label, singleColumn = true)
    }

    fun value(): GroupHighlightOptions = GroupHighlightOptions(
        cardInset = inset.control.value,
        showDividers = dividers.isSelected, dividerIntensity = dividerIntensity.control.value,
        dividerHeight = dividerHeight.control.value, hideDividersNearActive = hideAdjacent.isSelected,
    )

    fun setValue(value: GroupHighlightOptions) {
        inset.control.value = value.cardInset
        dividers.isSelected = value.showDividers; dividerIntensity.control.value = value.dividerIntensity
        dividerHeight.control.value = value.dividerHeight; hideAdjacent.isSelected = value.hideDividersNearActive
    }

    fun refresh(enabled: Boolean) {
        sliders.forEach { it.refresh(enabled) }
        checks.forEach { it.isEnabled = enabled }
        if (joined) {
            dividerIntensity.refresh(enabled && dividers.isSelected)
            dividerHeight.refresh(enabled && dividers.isSelected)
            hideAdjacent.isEnabled = enabled && dividers.isSelected
        }
    }

    fun installMarkers(watch: (JComponent, (TabAppearanceState) -> Any?) -> Unit) {
        fun options(value: TabAppearanceState) = if (joined) value.groupJoined else value.groupBackground
        if (joined) {
            watch(dividers) { options(it).showDividers }
            watch(dividerIntensity.label) { options(it).dividerIntensity }
            watch(dividerHeight.label) { options(it).dividerHeight }
            watch(hideAdjacent) { options(it).hideDividersNearActive }
        } else {
            watch(inset.label) { options(it).cardInset }
        }
    }
}

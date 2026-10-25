package dev.vlas.projecttabicons

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import javax.swing.JSlider

/** Два независимых набора контролов: смена варианта не перезаписывает его значения. */
internal class GroupHighlightControls(style: GroupHighlightStyle, changed: () -> Unit) {
    private val joined = style == GroupHighlightStyle.JOINED
    private val defaults = GroupHighlightOptions()
    private class Slider(title: String, private val name: String, min: Int, max: Int, initial: Int) {
        val control = JSlider(min, max, initial).apply {
            minorTickSpacing = 1; snapToTicks = true; isOpaque = false
            accessibleContext.accessibleName = name
        }
        val label = JBLabel(title).apply { labelFor = control }
        fun field(width: Int, height: Int): AppearanceField =
            AppearanceField(label, percentField(control, name, width, height))
        fun refresh(enabled: Boolean) {
            listOf(control, label).forEach { it.isEnabled = enabled }
        }
    }
    private val inset = pixelSpinner(defaults.cardInset, 0, 6).apply {
        accessibleContext.accessibleName = "Active card inset in pixels"
    }
    private val insetLabel = JBLabel("Card inset:").apply { labelFor = inset }
    private val dividers = JBCheckBox("Show internal dividers", defaults.showDividers)
    private val dividerIntensity = Slider("Divider intensity:", "Internal divider intensity", 0, 100, defaults.dividerIntensity)
    private val dividerHeight = Slider("Divider height:", "Internal divider height", 20, 100, defaults.dividerHeight)
    private val hideAdjacent = JBCheckBox("Hide dividers beside active tab", defaults.hideDividersNearActive)
    private val sliders = if (joined) listOf(dividerIntensity, dividerHeight) else emptyList()
    private val checks = if (joined) listOf(dividers, hideAdjacent) else emptyList()

    init {
        inset.toolTipText = "Space around the active card: 0–6 px. 0 px sits flush with the group surface."
        dividers.toolTipText = "Draw thin lines between joined tabs. Does not affect pipes between groups."
        dividerHeight.control.toolTipText = "Line height relative to the capsule: 20–100%."
        dividerIntensity.control.toolTipText = "Internal line intensity: 0–100%."
        hideAdjacent.toolTipText = "Hide both internal lines touching the active tab. Other dividers remain visible."
        sliders.forEach { it.control.addChangeListener { changed() } }
        if (!joined) inset.addChangeListener { changed() }
        checks.forEach { it.isOpaque = false; it.addActionListener { changed() } }
    }

    fun labelWidth(): Int = if (joined) sliders.maxOf { it.label.preferredSize.width } else insetLabel.preferredSize.width

    fun form(controlWidth: Int, rowHeight: Int, labelWidth: () -> Int, availableWidth: () -> Int): JComponent {
        val fields = if (joined) listOf(
            AppearanceField(null, dividers), dividerIntensity.field(controlWidth, rowHeight),
            dividerHeight.field(controlWidth, rowHeight), AppearanceField(null, hideAdjacent),
        ) else listOf(AppearanceField(insetLabel, compactField(pixelField(inset)), stretch = false))
        return SettingsAppearanceForm(fields.first().control, fields.drop(1), labelWidth, controlWidth,
            rowHeight, availableWidth, fields.first().label, singleColumn = true)
    }

    fun value(): GroupHighlightOptions = GroupHighlightOptions(
        cardInset = inset.value as Int,
        showDividers = dividers.isSelected, dividerIntensity = dividerIntensity.control.value,
        dividerHeight = dividerHeight.control.value, hideDividersNearActive = hideAdjacent.isSelected,
    )

    fun setValue(value: GroupHighlightOptions) {
        inset.value = value.cardInset
        dividers.isSelected = value.showDividers; dividerIntensity.control.value = value.dividerIntensity
        dividerHeight.control.value = value.dividerHeight; hideAdjacent.isSelected = value.hideDividersNearActive
    }

    fun refresh(enabled: Boolean) {
        sliders.forEach { it.refresh(enabled) }
        listOf(inset, insetLabel).forEach { it.isEnabled = enabled }
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
            watch(insetLabel) { options(it).cardInset }
        }
    }
}

package dev.vlas.projecttabicons

import com.intellij.ui.dsl.builder.IntelliJSpacingConfiguration
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.SpacingConfiguration
import com.intellij.ui.dsl.builder.components.SegmentedButtonComponent
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.text.NumberFormatter

/** Короткий набор вариантов виден целиком и выбирается одним нажатием. */
internal fun <T : Any> appearanceChoice(
    choices: List<T>,
    title: (T) -> String = { it.toString() },
): SegmentedButtonComponent<T> = SegmentedButtonComponent<T> {
    SegmentedButton.createPresentation(title(it))
}.apply {
    val defaults = IntelliJSpacingConfiguration()
    spacing = object : SpacingConfiguration by defaults {
        override val segmentedButtonHorizontalGap: Int = 10
        override val segmentedButtonVerticalGap: Int = 4
    }
    items = choices
    selectedItem = choices.first()
}

/** Малый диапазон пикселей: точное значение вместо длинного ползунка. */
internal fun pixelSpinner(value: Int, minimum: Int, maximum: Int): JSpinner =
    JSpinner(SpinnerNumberModel(value, minimum, maximum, 1)).apply {
        editor = JSpinner.NumberEditor(this, "0").apply {
            (textField.formatter as NumberFormatter).apply {
                allowsInvalid = false
                commitsOnValidEdit = true
            }
        }
        preferredSize = Dimension(JBUI.scale(60), preferredSize.height)
    }

internal fun pixelField(spinner: JSpinner): JPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
    isOpaque = false
    add(spinner, BorderLayout.CENTER)
    add(JBLabel("px").apply {
        spinner.addPropertyChangeListener("enabled") { isEnabled = spinner.isEnabled }
    }, BorderLayout.EAST)
}

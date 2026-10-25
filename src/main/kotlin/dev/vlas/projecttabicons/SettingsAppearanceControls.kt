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
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
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
    integerSpinner(value, minimum, maximum)

private fun integerSpinner(value: Int, minimum: Int, maximum: Int): JSpinner =
    JSpinner(SpinnerNumberModel(value, minimum, maximum, 1)).apply {
        editor = JSpinner.NumberEditor(this, "0").apply {
            textField.horizontalAlignment = SwingConstants.RIGHT
            (textField.formatter as NumberFormatter).apply {
                // Разрешаем промежуточное «3» при вводе 35 в диапазоне 33–66.
                // В модель попадают только допустимые значения; уход из поля
                // возвращает прежнее значение, если ввод не закончен.
                allowsInvalid = true
                commitsOnValidEdit = true
            }
        }
        preferredSize = Dimension(JBUI.scale(60), preferredSize.height)
    }

internal fun pixelField(spinner: JSpinner): JPanel = numberField(spinner, "px")

/** Короткое поле в роли первой строки формы не растягивается до ширины колонки. */
internal fun compactField(field: JPanel): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(field, BorderLayout.WEST)
}

private fun numberField(spinner: JSpinner, unit: String): JPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
    isOpaque = false
    add(spinner, BorderLayout.CENTER)
    add(JBLabel(unit).apply {
        preferredSize = Dimension(getFontMetrics(font).stringWidth("px"), preferredSize.height)
        isEnabled = spinner.isEnabled
        spinner.addPropertyChangeListener("enabled") { isEnabled = spinner.isEnabled }
    }, BorderLayout.EAST)
}

/** Проценты: плавный выбор и точное число управляют одним значением. */
internal fun percentField(slider: JSlider, name: String, width: Int, height: Int): JPanel {
    val exact = integerSpinner(slider.value, slider.minimum, slider.maximum).apply {
        accessibleContext.accessibleName = "$name, exact value"
        toolTipText = "Enter ${slider.minimum}–${slider.maximum}% or use the slider."
        isEnabled = slider.isEnabled
    }
    var syncing = false
    slider.addChangeListener {
        if (!syncing) {
            syncing = true
            try { exact.value = slider.value } finally { syncing = false }
        }
    }
    exact.addChangeListener {
        if (!syncing) {
            syncing = true
            try { slider.value = exact.value as Int } finally { syncing = false }
        }
    }
    slider.addPropertyChangeListener("enabled") { exact.isEnabled = slider.isEnabled }
    return JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        isOpaque = false
        add(slider, BorderLayout.CENTER)
        add(numberField(exact, "%"), BorderLayout.EAST)
        preferredSize = Dimension(width, height)
    }
}

package dev.vlas.projecttabicons

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton

/** Отключаем Swing HTML до присваивания пользовательского текста, включая загрузку ресурсов HTML. */
internal fun userPresetRadioButton(name: String) = JBRadioButton().apply {
    putClientProperty("html.disable", true)
    text = name
}

internal fun styleBundleCheckBox(name: String) = JBCheckBox().apply {
    putClientProperty("html.disable", true)
    text = name
    isSelected = true
}

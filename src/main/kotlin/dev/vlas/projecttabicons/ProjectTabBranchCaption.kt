package dev.vlas.projecttabicons

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Component
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.SwingConstants

/** Общая подпись для настоящего таба и Preview с веткой или цветным счётчиком строк. */
internal open class ProjectTabBranchCaption : JBLabel() {
    var fontSource: JComponent? = null
    var branchForeground: Color? = null
    private var projectName = ""
    var addedForeground: Color? = null
    var removedForeground: Color? = null
    private var extraInfo: ProjectTabExtraInfo? = null
    val hasExtraInfo: Boolean get() = extraInfo != null
    private var position = TabBranchPosition.RIGHT

    init { horizontalAlignment = SwingConstants.CENTER }

    fun showTitle(title: String, extra: ProjectTabExtraInfo?, side: TabBranchPosition): Boolean {
        if (projectName == title && extraInfo == extra && position == side) return false
        projectName = title
        extraInfo = extra
        position = side
        // В accessibility и tooltip остаётся полная ветка даже в Compact.
        text = when {
            extra == null -> title
            side == TabBranchPosition.LEFT -> "${extra.accessibleText} $title"
            else -> "$title ${extra.accessibleText}"
        }
        revalidate()
        repaint()
        return true
    }

    private fun textFont() = fontSource?.font ?: font
    private fun statusFont() = textFont().let { it.deriveFont((it.size2D - JBUI.scale(3)).coerceAtLeast(1f)) }

    override fun getPreferredSize(): Dimension {
        val extra = extraInfo ?: return super.getPreferredSize()
        val metrics = getFontMetrics(textFont())
        val statusMetrics = getFontMetrics(statusFont())
        val extraWidth = when (extra) {
            is ProjectTabExtraInfo.Branch -> metrics.stringWidth("[${extra.displayName}]") +
                (extra.status?.let { JBUI.scale(8) + statusMetrics.stringWidth(it.text) } ?: 0)
            is ProjectTabExtraInfo.Status -> statusMetrics.stringWidth(extra.text)
        }
        return Dimension(metrics.stringWidth(projectName) + JBUI.scale(8) +
            extraWidth + insets.left + insets.right,
            metrics.height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun paintComponent(graphics: Graphics) {
        val extra = extraInfo ?: return super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val titleFont = textFont()
            val diffFont = statusFont()
            g.font = titleFont
            // Измеряем тем же Graphics, которым рисуем: компонентные метрики могут
            // отличаться по сглаживанию и съедать отступ после длинной ветки в Preview.
            val metrics = g.getFontMetrics(titleFont)
            val statusMetrics = g.getFontMetrics(diffFont)
            val available = (width - insets.left - insets.right).coerceAtLeast(0)
            val gap = JBUI.scale(8).coerceAtMost(available)
            val budget = (available - gap).coerceAtLeast(0)
            val projectWidth = metrics.stringWidth(projectName)
            val branch = extra as? ProjectTabExtraInfo.Branch
            val status = when (extra) {
                is ProjectTabExtraInfo.Branch -> extra.status
                is ProjectTabExtraInfo.Status -> extra
            }
            val statusWidth = status?.let { statusMetrics.stringWidth(it.text) } ?: 0
            val branchWidth = branch?.let { metrics.stringWidth("[${it.displayName}]") } ?: 0
            val infoGap = if (branch != null && status != null) gap else 0
            val extraWidth = branchWidth + infoGap + statusWidth
            // Сначала сохраняем целый счётчик; ветка сокращается в оставшемся месте.
            val minimumInfoBudget = if (branch != null && status != null) budget * 3 / 5 else budget * 2 / 5
            val extraBudget = minOf(extraWidth, maxOf(budget - projectWidth, minimumInfoBudget)).coerceAtMost(budget)
            val statusText = status?.let {
                if (statusWidth <= extraBudget) it.text else fitTabCaption("…", extraBudget, statusMetrics, keepEnd = false)
            } ?: ""
            val branchBudget = (extraBudget - statusMetrics.stringWidth(statusText) -
                if (statusText.isNotEmpty()) infoGap else 0).coerceAtLeast(0)
            val branchText = branch?.let {
                val brackets = metrics.stringWidth("[]")
                if (branchWidth <= branchBudget) "[${it.displayName}]"
                else if (branchBudget > brackets) {
                    val fitted = fitTabCaption(it.displayName, branchBudget - brackets, metrics, keepEnd = true)
                    if (fitted.isNotEmpty()) "[$fitted]" else ""
                } else fitTabCaption(it.displayName, branchBudget, metrics, keepEnd = true)
            } ?: ""
            val actualInfoGap = if (branchText.isNotEmpty() && statusText.isNotEmpty()) infoGap else 0
            val renderedInfoWidth = metrics.stringWidth(branchText) + actualInfoGap + statusMetrics.stringWidth(statusText)
            val name = fitTabCaption(projectName, (budget - renderedInfoWidth).coerceAtLeast(0), metrics, keepEnd = false)
            val actualGap = if (name.isEmpty() || renderedInfoWidth == 0) 0 else gap
            val total = metrics.stringWidth(name) + actualGap + renderedInfoWidth
            var x = insets.left + ((available - total) / 2).coerceAtLeast(0)
            val y = insets.top + (height - insets.top - insets.bottom - metrics.height) / 2 + metrics.ascent
            fun draw(value: String, color: Color, useStatusFont: Boolean = false) {
                g.font = if (useStatusFont) diffFont else titleFont
                g.color = color
                g.drawString(value, x, y)
                x += (if (useStatusFont) statusMetrics else metrics).stringWidth(value)
            }
            fun drawName() = draw(name, fontSource?.foreground ?: foreground)
            fun drawExtra() {
                draw(branchText, branchForeground ?: UIUtil.getContextHelpForeground())
                x += actualInfoGap
                if (status != null && status.value.state == GitTabDiffState.READY && statusText == status.text) {
                    draw("+${status.value.added}", addedForeground ?: JBColor.namedColor("VersionControl.FileStatus.ADDED", JBColor(0x2E7D32, 0x6AAB73)), useStatusFont = true)
                    draw(" ", branchForeground ?: UIUtil.getContextHelpForeground(), useStatusFont = true)
                    draw("-${status.value.removed}", removedForeground ?: JBColor.namedColor("VersionControl.FileStatus.DELETED", JBColor(0xC62828, 0xDB5C5C)), useStatusFont = true)
                } else draw(statusText, branchForeground ?: UIUtil.getContextHelpForeground(), useStatusFont = true)
            }
            if (position == TabBranchPosition.LEFT) { drawExtra(); x += actualGap; drawName() }
            else { drawName(); x += actualGap; drawExtra() }
        } finally { g.dispose() }
    }
}

/** Не заменяем TabLabel, его крестик и обработчики. Меняется только неинтерактивная подпись. */
internal class NativeProjectTabBranchCaption(private val label: JComponent) {
    private val originalLayout = (label.layout as? ProjectTabIconLayout)?.original as? BorderLayout
    private val original: Component? = originalLayout?.getLayoutComponent(label, BorderLayout.CENTER)
    private val originalVisible = original?.isVisible ?: true
    private val originalPreferred = original?.takeIf { it.isPreferredSizeSet }?.preferredSize
    private val collapsedPreferred = Dimension(0, 0)
    private val caption = object : ProjectTabBranchCaption() {
        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            super.setBounds(x, y, width, height)
            // TabLabel использует сохранённый placeholder для focus/fadeout.
            // Оставляем его в том же дереве и с актуальными координатами.
            original?.setBounds(x, y, width, height)
            (original as? Container)?.doLayout()
        }
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12)
        fontSource = (label as? TabLabel)?.labelComponent ?: original as? JComponent
    }

    fun isInstalled(): Boolean {
        val activeLayout = (label.layout as? ProjectTabIconLayout)?.original ?: label.layout
        return originalLayout != null && activeLayout === originalLayout && originalLayout.getLayoutComponent(label, BorderLayout.CENTER) === caption
    }

    fun show(projectName: String, extra: ProjectTabExtraInfo, side: TabBranchPosition) {
        val changed = caption.showTitle(projectName, extra, side)
        val install = !isInstalled() && original != null
        if (install) {
            // Не отсоединяем placeholder: иначе внутренний paint TabLabel теряет
            // связь с деревом. Нулевая preferred width отключает fadeout старого имени.
            original.isVisible = false
            original.preferredSize = collapsedPreferred
            label.add(caption, BorderLayout.CENTER)
        }
        if (changed || install) { label.revalidate(); label.repaint() }
    }

    fun restore() {
        val installed = isInstalled()
        if (caption.parent === label) label.remove(caption)
        if (original != null) {
            if (original.preferredSize == collapsedPreferred) original.preferredSize = originalPreferred
            original.isVisible = originalVisible
            if (installed && original.parent === label) label.add(original, BorderLayout.CENTER)
        }
        label.revalidate()
        label.repaint()
    }
}

/** Разрезаем только по Unicode code point; знак сокращения стоит со стороны отброшенного текста. */
private fun fitTabCaption(text: String, width: Int, metrics: FontMetrics, keepEnd: Boolean): String {
    if (metrics.stringWidth(text) <= width) return text
    if (width <= 0) return ""
    val count = text.codePointCount(0, text.length)
    val ellipsis = "…"
    if (metrics.stringWidth(ellipsis) > width) {
        val one = if (keepEnd) text.substring(text.offsetByCodePoints(text.length, -1))
            else text.substring(0, text.offsetByCodePoints(0, 1))
        return one.takeIf { metrics.stringWidth(it) <= width } ?: ""
    }
    fun candidate(n: Int): String = if (keepEnd) ellipsis + text.substring(text.offsetByCodePoints(text.length, -n))
        else text.substring(0, text.offsetByCodePoints(0, n)) + ellipsis
    var low = 0
    var high = count
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (metrics.stringWidth(candidate(mid)) <= width) low = mid else high = mid - 1
    }
    return candidate(low)
}

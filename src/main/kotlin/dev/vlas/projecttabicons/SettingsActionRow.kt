package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/** Действия прижаты вправо; при нехватке ширины занимают отдельную строку. */
internal open class SettingsActionRow(
    private val content: JComponent,
    private val actions: JComponent,
    private val availableWidth: () -> Int,
    private val stretchContent: Boolean = true,
    private val minimumContentWidth: () -> Int = { content.preferredSize.width },
) : JPanel(null) {
    private val gap = JBUI.scale(12)
    private val actionsWidth get() = if (actions.isVisible) actions.preferredSize.width else 0
    private fun usableWidth(): Int = (availableWidth() - insets.left - insets.right).coerceAtLeast(0)
    private fun stacked(): Boolean = actions.isVisible && usableWidth() < minimumContentWidth() + gap + actionsWidth

    init { isOpaque = false; add(content); add(actions) }

    fun contentWidth(): Int = (usableWidth() - if (actions.isVisible && !stacked()) actionsWidth + gap else 0)
        .coerceAtLeast(0)

    override fun getPreferredSize(): Dimension {
        val contentSize = content.preferredSize
        val actionsHeight = if (actions.isVisible) actions.preferredSize.height else 0
        val height = if (stacked()) contentSize.height + gap + actionsHeight else maxOf(contentSize.height, actionsHeight)
        return Dimension(contentSize.width + actionsWidth + (if (actions.isVisible) gap else 0) + insets.left + insets.right,
            height + insets.top + insets.bottom)
    }

    override fun doLayout() {
        val contentSize = content.preferredSize
        val contentWidth = if (stretchContent) contentWidth() else minOf(contentWidth(), contentSize.width)
        content.setBounds(insets.left, insets.top, contentWidth, contentSize.height)
        if (actions.isVisible) {
            val actionWidth = actionsWidth.coerceAtMost(usableWidth())
            actions.setBounds(insets.left + usableWidth() - actionWidth,
                insets.top + if (stacked()) contentSize.height + gap else 0,
                actionWidth, actions.preferredSize.height)
        }
    }
}

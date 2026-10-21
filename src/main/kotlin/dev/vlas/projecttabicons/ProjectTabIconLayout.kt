package dev.vlas.projecttabicons

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager2
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.border.Border
import javax.swing.border.EmptyBorder

/** Пять постоянных позиций: крестик | значок | название | значок | крестик. */
internal class ProjectTabIconLayout(
    val original: LayoutManager2,
    var projectIcon: CenteredProjectIcon?,
    var position: TabIconPosition,
    var closeButtonPosition: TabCloseButtonPosition,
    var centerProjectName: Boolean,
) : LayoutManager2 by original {
    var iconBounds: Rectangle? = null
        private set
    private var actionComponent: JComponent? = null
    private var originalActionBorder: Border? = null
    private var appliedActionBorder: Border? = null
    private var mirroredActionBorder: Border? = null

    override fun layoutContainer(parent: Container) {
        // IDEA задаёт TabLabel высоту 30 px. Полоса получает высоту из внешнего layout.
        val tabs = parent.parent as? com.intellij.ui.mac.WindowTabsComponent
        if (tabs != null && parent.height > 0 && tabs.height > parent.height) {
            parent.setSize(parent.width, tabs.height)
        }
        val borderLayout = original as? BorderLayout
        val close = borderLayout?.getLayoutComponent(parent, BorderLayout.WEST)
        updateCloseInsets(close)
        original.layoutContainer(parent)
        iconBounds = null
        val center = borderLayout?.getLayoutComponent(parent, BorderLayout.CENTER) ?: return
        val insets = parent.insets
        val groupMargins = ((parent as? JComponent)?.border as? ProjectTabBorder)?.groupMargins() ?: ProjectTabGroupMargins.NONE
        val left = insets.left.coerceAtLeast(0)
        val right = (parent.width - insets.right.coerceAtLeast(0)).coerceAtLeast(left)
        val top = insets.top.coerceAtLeast(0)
        val contentHeight = (parent.height - top - insets.bottom.coerceAtLeast(0)).coerceAtLeast(0)
        // Оба крайних слота существуют при любом положении единственного крестика.
        val closeWidth = (close?.preferredSize?.width ?: 0).coerceIn(0, (right - left) / 2)
        var contentLeft = left + closeWidth
        var contentRight = right - closeWidth

        // Wrapper остаётся WEST в исходном layout: IDEA может перестроить actionPanel.
        // Перемещаем существующий компонент вместе с его hit area, без второй кнопки.
        close?.setBounds(
            if (closeButtonPosition == TabCloseButtonPosition.LEFT) left else right - closeWidth,
            top, closeWidth, contentHeight,
        )

        visibleProjectIcon(parent)?.let { icon ->
            val gap = JBUI.scale(4)
            val slotWidth = iconSlotWidth(icon)
            // Оба внутренних слота резервируются парой. На слишком узкой вкладке
            // скрываем значок, оставляя место для крестика и названия.
            if (contentRight - contentLeft >= slotWidth * 2) {
                val iconX = if (position == TabIconPosition.LEFT) {
                    contentLeft + gap
                } else {
                    contentRight - gap - icon.iconWidth
                }
                iconBounds = Rectangle(iconX, top + (contentHeight - icon.iconHeight) / 2,
                    icon.iconWidth, icon.iconHeight)
                contentLeft += slotWidth
                contentRight -= slotWidth
            }
        }
        if (centerProjectName || (center is ProjectTabBranchCaption && center.hasExtraInfo)) {
            // Центр короткого имени — середина всей вкладки. Контролы остаются
            // в прежних слотах; длинное имя ограничивается только их областями.
            val icon = iconBounds
            val gap = JBUI.scale(4)
            // У родного TabLabel правый inset больше левого. В свободном краю
            // оставляем одинаковое поле, учитывая защиту полной обводки.
            val edgePadding = maxOf(gap, minOf((insets.left - groupMargins.left).coerceAtLeast(0),
                (insets.right - groupMargins.right).coerceAtLeast(0)))
            val titleLeft = when {
                icon != null && position == TabIconPosition.LEFT -> icon.x + icon.width + gap
                closeButtonPosition == TabCloseButtonPosition.LEFT -> left + closeWidth
                else -> groupMargins.left + edgePadding
            }
            val titleRight = when {
                icon != null && position == TabIconPosition.RIGHT -> icon.x - gap
                closeButtonPosition == TabCloseButtonPosition.RIGHT -> right - closeWidth
                else -> parent.width - groupMargins.right - edgePadding
            }
            val availableWidth = (titleRight - titleLeft).coerceAtLeast(0)
            if (center is ProjectTabBranchCaption && center.hasExtraInfo) {
                // Git использует всю свободную ширину между контролами. Без доп.
                // информации обычная подпись ниже центрируется по всей вкладке.
                center.setBounds(titleLeft, top, availableWidth, contentHeight)
                return
            }
            val titleWidth = minOf(center.preferredSize.width.coerceAtLeast(0), availableWidth)
            // Wrapper родной подписи имеет разные внутренние поля. Центрируем
            // его содержимое, иначе сам текст остаётся сдвинутым внутри вкладки.
            val titleInsets = (center as? Container)?.insets
            val paddingOffset = (titleInsets?.left ?: 0) - (titleInsets?.right ?: 0)
            val titleX = ((parent.width + groupMargins.left - groupMargins.right - titleWidth - paddingOffset) / 2)
                .coerceIn(titleLeft, maxOf(titleLeft, titleRight - titleWidth))
            center.setBounds(titleX, top, titleWidth, contentHeight)
            return
        }
        // Подпись с путём использует прежнюю центральную область и может занять
        // пустые позиции справа, не сдвигая начало текста и контролы.
        // Правый крестик сохраняет место и тогда, когда IDEA скрывает его до hover.
        val titleWidth = (contentRight - contentLeft).coerceAtLeast(0)
        val trailingEdge = when {
            iconBounds != null && position == TabIconPosition.RIGHT -> contentRight
            closeButtonPosition == TabCloseButtonPosition.RIGHT -> right - closeWidth
            else -> right
        }
        val extraTitleWidth = (center.preferredSize.width - titleWidth)
            .coerceIn(0, (trailingEdge - contentRight).coerceAtLeast(0))
        center.setBounds(contentLeft, top, titleWidth + extraTitleWidth, contentHeight)
    }

    // Родной крестик имеет отступ 6 px от левого края. Справа зеркалим этот отступ,
    // а не сам рисунок и не кнопку. Исходный border восстанавливается при отключении.
    private fun updateCloseInsets(close: Component?) {
        val target = if (close is Container && close.componentCount == 1) {
            close.getComponent(0) as? JComponent
        } else close as? JComponent
        if (target !== actionComponent) {
            restoreCloseInsets()
            actionComponent = target
        }
        if (target == null) return
        if (target.border !== appliedActionBorder || originalActionBorder == null) {
            originalActionBorder = target.border
            val source = originalActionBorder
            mirroredActionBorder = if (source is EmptyBorder) {
                val i = source.getBorderInsets(target)
                EmptyBorder(i.top, i.right, i.bottom, i.left)
            } else source
        }
        val next = if (closeButtonPosition == TabCloseButtonPosition.RIGHT) mirroredActionBorder else originalActionBorder
        if (target.border !== next) target.border = next
        appliedActionBorder = next
    }

    fun restoreCloseInsets() {
        actionComponent?.let { if (it.border === appliedActionBorder) it.border = originalActionBorder }
        actionComponent = null
        originalActionBorder = null
        appliedActionBorder = null
        mirroredActionBorder = null
    }

    private fun visibleProjectIcon(parent: Container): CenteredProjectIcon? {
        val border = (parent as? JComponent)?.border as? ProjectTabBorder
        return if (border != null) border.visibleProjectIcon() else projectIcon
    }

    private fun iconSlotWidth(icon: CenteredProjectIcon?): Int = icon?.let { it.iconWidth + JBUI.scale(4) * 2 } ?: 0

    override fun preferredLayoutSize(parent: Container): Dimension =
        withSlotWidths(parent, original.preferredLayoutSize(parent), minimum = false)

    override fun minimumLayoutSize(parent: Container): Dimension =
        withSlotWidths(parent, original.minimumLayoutSize(parent), minimum = true)

    private fun withSlotWidths(parent: Container, size: Dimension, minimum: Boolean): Dimension {
        val close = (original as? BorderLayout)?.getLayoutComponent(parent, BorderLayout.WEST)
        val closeWidth = (close?.preferredSize?.width ?: 0).coerceAtLeast(0)
        // Исходный BorderLayout уже учитывает один видимый крестик. Заменяем его
        // вклад в ширину парой равных слотов; значок тоже получает слот с обеих сторон.
        val originalCloseWidth = if (close != null && close.isVisible) {
            (if (minimum) close.minimumSize.width else closeWidth).coerceAtLeast(0)
        } else 0
        return Dimension(size.width - originalCloseWidth + closeWidth * 2 + iconSlotWidth(visibleProjectIcon(parent)) * 2, size.height)
    }
}

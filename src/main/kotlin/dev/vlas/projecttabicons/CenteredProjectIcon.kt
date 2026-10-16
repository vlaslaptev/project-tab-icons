package dev.vlas.projecttabicons

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ide.ProjectFileIcon
import com.intellij.ui.DeferredIconImpl
import com.intellij.ui.LayeredIcon
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Центрирует буквенные аватары и убирает внешний отступ провайдера у custom SVG/PNG. */
internal class CenteredProjectIcon(
    private val delegate: Icon,
    private val unscaledSize: Int,
    private val artwork: () -> Icon? = { null },
    private val avatarColor: () -> Color? = { null },
    private val initials: TabIconInitials = DEFAULT_ICON_INITIALS,
    private val letterWeight: TabIconLetterWeight = TabIconLetterWeight.BOLD,
    private val projectName: () -> String = { "" },
) : Icon {
    private var renderingFailed = false

    override fun getIconWidth(): Int = JBUI.scale(unscaledSize)

    override fun getIconHeight(): Int = JBUI.scale(unscaledSize)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (renderingFailed || graphics !is Graphics2D) {
            delegate.paintIcon(component, graphics, x, y)
            return
        }

        val resolved = if (delegate is DeferredIconImpl<*>) {
            if (!delegate.isDone) {
                // Запускает асинхронную загрузку IDEA. retrieveIcon() может выполнять I/O.
                delegate.paintIcon(component, graphics, x, y)
                return
            }
            delegate.currentlyPaintedIcon()
        } else {
            delegate
        }

        val g = graphics.create() as Graphics2D
        try {
            if (iconWidth <= 0 || iconHeight <= 0) return

            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            artwork()?.let {
                g.clipRect(0, 0, iconWidth, iconHeight)
                it.paintIcon(component, g, 0, 0)
                return
            }
            // Только известная обёртка штатного провайдера: EmptyIcon + ProjectFileIcon.
            // Внутренние отступы самого SVG и его цвета сохраняются; контур не обрезается.
            val custom = (resolved as? LayeredIcon)?.takeIf {
                it.iconCount == 2 && it.getIcon(0) is EmptyIcon
            }?.getIcon(1) as? ProjectFileIcon
            if (custom != null && custom.iconWidth == iconWidth && custom.iconHeight == iconHeight) {
                // SVG/PNG уже подготовлен провайдером в конечном размере и плотности
                // дисплея. Не растягиваем готовое растровое изображение через Graphics2D.
                g.clipRect(0, 0, iconWidth, iconHeight)
                custom.paintIcon(component, g, 0, 0)
                return
            }
            if (resolved !is AvatarIcon) {
                delegate.paintIcon(component, graphics, x, y)
                return
            }

            val presentation = resolved.presentation
            paintProjectInitials(g, presentation.shortName, avatarColor() ?: presentation.color1,
                unscaledSize, initials, letterWeight, projectName().ifEmpty { presentation.shortName })
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: RuntimeException) {
            renderingFailed = true
            LOG.warn("Cannot render the project tab icon; using the IDE icon", error)
            delegate.paintIcon(component, graphics, x, y)
        } finally {
            g.dispose()
        }
    }

    fun withAvatarAppearance(color: Color?, initials: TabIconInitials,
                             weight: TabIconLetterWeight): CenteredProjectIcon =
        CenteredProjectIcon(delegate, unscaledSize, artwork, { color }, initials, weight, projectName)

    companion object {
        private val LOG = Logger.getInstance(CenteredProjectIcon::class.java)
    }
}

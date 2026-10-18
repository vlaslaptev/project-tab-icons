package dev.vlas.projecttabicons

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.DeferredIconImpl
import com.intellij.util.ui.AvatarIcon
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Рисует только подготовленное изображение или буквы; исходник IDEA на экран не попадает. */
internal class CenteredProjectIcon(
    private val delegate: Icon,
    private val unscaledSize: Int,
    private val artwork: () -> Icon? = { null },
    private val avatarColor: () -> Color? = { null },
    private val initials: TabIconInitials = DEFAULT_ICON_INITIALS,
    private val letterWeight: TabIconLetterWeight = TabIconLetterWeight.BOLD,
    private val projectName: () -> String = { "" },
) : Icon {
    private var renderingFailureLogged = false
    private var lastArtwork: Icon? = null
    private val initialsPainter = ProjectInitialsPainter()

    override fun getIconWidth(): Int = JBUI.scale(unscaledSize)

    override fun getIconHeight(): Int = JBUI.scale(unscaledSize)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        if (graphics !is Graphics2D) return
        val g = graphics.create() as Graphics2D
        try {
            if (iconWidth <= 0 || iconHeight <= 0) return
            if (delegate is DeferredIconImpl<*>) loadProjectIconWithoutPainting(delegate, component, x, y)

            g.translate(x, y)
            g.clipRect(0, 0, iconWidth, iconHeight)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            artwork()?.let {
                lastArtwork = it
                it.paintIcon(component, g, 0, 0)
                return
            }
            val resolved = if (delegate is DeferredIconImpl<*>) delegate.currentlyPaintedIcon() else delegate
            if (resolved is AvatarIcon) {
                lastArtwork = null
                val presentation = resolved.presentation
                initialsPainter.paint(g, presentation.shortName, avatarColor() ?: presentation.color1,
                    unscaledSize, initials, letterWeight, projectName().ifEmpty { presentation.shortName })
                return
            }
            // При ожидании, неизвестной обёртке или сбое не возвращаем сырой SVG/аватар.
            // Последнее очищенное изображение сохраняется; без него слот остаётся пустым.
            lastArtwork?.paintIcon(component, g, 0, 0)
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: RuntimeException) {
            if (!renderingFailureLogged) {
                renderingFailureLogged = true
                LOG.warn("Cannot render the prepared project tab icon; leaving its slot empty", error)
            }
        } finally {
            g.dispose()
        }
    }

    fun withAvatarAppearance(color: Color?, initials: TabIconInitials,
                             weight: TabIconLetterWeight): CenteredProjectIcon =
        CenteredProjectIcon(delegate, unscaledSize, artwork, { color }, initials, weight, projectName)

    fun withArtwork(image: Icon): CenteredProjectIcon =
        CenteredProjectIcon(delegate, unscaledSize, { image }, avatarColor, initials, letterWeight, projectName)

    companion object {
        private val LOG = Logger.getInstance(CenteredProjectIcon::class.java)
    }
}

/** SDK 262: запрос загрузки и repaint без вызова paintIcon у исходного изображения. */
internal fun loadProjectIconWithoutPainting(icon: DeferredIconImpl<*>, component: Component?, x: Int = 0, y: Int = 0) {
    if (icon.isDone) return
    if (component != null) icon.notifyPaint(component, x, y)
    icon.triggerEvaluation()
}

package dev.vlas.projecttabicons

import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.util.IdentityHashMap
import javax.swing.JComponent

/** Меняет только подпись оконной вкладки и восстанавливает исходные шрифты при отключении. */
internal class ProjectTabFont(private val label: JComponent) {
    private data class FontState(val original: Font, val applied: Font)
    private val fonts = IdentityHashMap<JComponent, FontState>()

    fun apply(size: Int): Boolean {
        val targets = linkedSetOf(label)
        fun collect(view: Component) {
            if (view is JComponent) targets.add(view)
            if (view is Container) view.components.forEach(::collect)
        }
        (label as? TabLabel)?.labelComponent?.let(::collect)
        var changed = false
        fonts.keys.filter { it !in targets }.toList().forEach { changed = restore(it) || changed }
        targets.forEach { target ->
            val current = target.font ?: return@forEach
            val previous = fonts[target]
            // updateUI или другое расширение могли заменить шрифт: это новая база.
            val original = if (previous != null && current === previous.applied) previous.original else current
            val next = original.deriveFont(JBUI.scale(size).toFloat())
            if (current != next) {
                target.font = next
                changed = true
            }
            fonts[target] = FontState(original, target.font)
        }
        return changed
    }

    private fun restore(target: JComponent): Boolean {
        val saved = fonts.remove(target) ?: return false
        if (target.font !== saved.applied) return false
        target.font = saved.original
        target.revalidate()
        target.repaint()
        return true
    }

    fun restore(): Boolean {
        var changed = false
        fonts.keys.toList().forEach { changed = restore(it) || changed }
        return changed
    }
}

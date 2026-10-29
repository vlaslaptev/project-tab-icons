package dev.vlas.projecttabicons

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.jetbrains.JBR
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil

/** JBR keeps native Snap/minimize/maximize/close and hit testing in one title/tab row. */
internal class WindowsMergedCaption(private val frame: JFrame, private val tabs: WindowsProjectTabs) : JPanel(BorderLayout()) {
    private val titleBar = JBR.getWindowDecorations()?.createCustomTitleBar()
    private val strip = JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }
    private val dragArea = JPanel().apply {
        preferredSize = Dimension(JBUI.scale(WindowsCaptionMetrics.DRAG_AREA_WIDTH), 0)
        // No mouse listeners: JBR treats this empty area as a native window caption.
    }
    // JBR can query hit testing on the outer HWND event before AWT retargets it
    // to TabLabel. Include that event, not only events sourced by tab children.
    // Keep IDEA's drag helper and its events intact.
    private var tabGesture = false
    val isTabGestureInProgress: Boolean get() = tabGesture
    private val tabHitTest = AWTEventListener { event ->
        if (event is MouseEvent && event.id != MouseEvent.MOUSE_EXITED && event.id != MouseEvent.MOUSE_WHEEL &&
            (event.component === frame || SwingUtilities.getWindowAncestor(event.component) === frame)) {
            val overTabs = tabs.contains(SwingUtilities.convertPoint(event.component, event.point, tabs))
            if (event.id == MouseEvent.MOUSE_PRESSED) tabGesture = overTabs
            if (overTabs || tabGesture) titleBar?.forceHitTest(true)
            if (event.id == MouseEvent.MOUSE_RELEASED) tabGesture = false
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (titleBar != null) Toolkit.getDefaultToolkit().addAWTEventListener(
            tabHitTest, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
    }

    override fun removeNotify() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(tabHitTest)
        tabGesture = false
        super.removeNotify()
    }

    init {
        add(strip, BorderLayout.CENTER)
        if (titleBar != null) {
            add(dragArea, BorderLayout.EAST)
            titleBar.height = tabs.preferredSize.height.toFloat()
            JBR.getWindowDecorations()!!.setCustomTitleBar(frame, titleBar)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateCaption()
                override fun componentShown(e: ComponentEvent) = updateCaption()
            })
        }
        updateCaption()
    }

    private fun updateCaption() {
        val bar = titleBar ?: return
        bar.height = maxOf(1, height.takeIf { it > 0 } ?: preferredSize.height).toFloat()
        val inset = ceil(bar.rightInset.toDouble()).toInt()
        if (border?.getBorderInsets(this)?.right != inset) {
            border = BorderFactory.createEmptyBorder(0, 0, 0, inset)
            revalidate()
        }
    }

    override fun paintComponent(g: Graphics) {
        val color = tabs.tabPainter.getBackgroundColor()
        background = color
        strip.background = color
        dragArea.background = color
        titleBar?.let { bar ->
            val dark = ColorUtil.isDark(color)
            if (bar.properties["controls.dark"] != dark) bar.putProperty("controls.dark", dark)
        }
        super.paintComponent(g)
    }
}

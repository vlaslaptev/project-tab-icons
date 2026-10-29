package dev.vlas.projecttabicons

import com.intellij.openapi.wm.impl.FrameBoundsConverter
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.ui.ScreenUtil
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** Capture before reparenting: an embedded frame no longer has its original restore geometry. */
internal fun projectWindowNormalBounds(frame: IdeFrameImpl): Rectangle {
    if (frame.extendedState == Frame.NORMAL) return Rectangle(frame.bounds)
    val screen = frame.graphicsConfiguration
    if (frame.isDisplayable && screen != null) {
        val placement = WINDOWPLACEMENT()
        if (User32.INSTANCE.GetWindowPlacement(HWND(Native.getComponentPointer(frame)), placement).booleanValue()) {
            val bounds = placement.rcNormalPosition.toRectangle()
            if (!bounds.isEmpty) {
                FrameBoundsConverter.scaleDown(bounds, screen)
                // Normal positions of top-level project windows use workspace coordinates.
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
                bounds.translate(insets.left, insets.top)
                return bounds
            }
        }
    }
    val bounds = frame.normalBounds?.takeUnless { it.isEmpty }?.let { Rectangle(it) } ?: return Rectangle(frame.bounds)
    val oldScreen = frame.screenBounds
    val newScreen = screen?.bounds
    if (oldScreen != null && !oldScreen.isEmpty && newScreen != null && !newScreen.isEmpty && oldScreen != newScreen) {
        ScreenUtil.moveAndScale(bounds, oldScreen, newScreen)
    }
    return bounds
}

/** Keep restored and detached windows reachable after display/DPI changes. Coordinates are AWT logical pixels. */
internal fun fitProjectWindowBounds(requested: Rectangle): Rectangle {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
    val screen = screens.maxByOrNull {
        val intersection = it.bounds.intersection(requested)
        maxOf(0, intersection.width).toLong() * maxOf(0, intersection.height)
    } ?: return Rectangle(requested)
    val area = Rectangle(screen.bounds)
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
    area.x += insets.left; area.y += insets.top
    area.width -= insets.left + insets.right; area.height -= insets.top + insets.bottom
    return Rectangle(requested).apply {
        width = width.coerceIn(1, maxOf(1, area.width))
        height = height.coerceIn(1, maxOf(1, area.height))
        x = x.coerceIn(area.x, area.x + area.width - width)
        y = y.coerceIn(area.y, area.y + area.height - height)
    }
}

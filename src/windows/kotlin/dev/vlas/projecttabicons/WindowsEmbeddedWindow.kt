package dev.vlas.projecttabicons

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.jetbrains.WindowDecorations.CustomTitleBar
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.util.IdentityHashMap
import javax.swing.JFrame
import javax.swing.SwingUtilities

internal class EmbeddedProjectWindow(val frame: IdeFrameImpl, val project: Project, private val shell: JFrame) {
    private val user32 = User32.INSTANCE
    private val hwnd = HWND(Native.getComponentPointer(frame))
    private val originalParent = user32.GetParent(hwnd)
    // Visibility/minimize/maximize are state transitions, not restorable decoration
    // bits. Reapplying WS_MAXIMIZE alone leaves a normal-sized HWND marked zoomed.
    private val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and
        (WinUser.WS_VISIBLE or WinUser.WS_MINIMIZE or WinUser.WS_MAXIMIZE).inv()
    private val exStyle = user32.GetWindowLong(hwnd, -20)
    private val normalBounds = projectWindowNormalBounds(frame)
    private val state = frame.extendedState
    private val visible = frame.isVisible
    private var embedded = false
    private var layoutRequired = true
    private var layoutTransform: AffineTransform? = null
    private var layoutConfiguration: GraphicsConfiguration? = null
    private val titleBars = IdentityHashMap<CustomTitleBar, Any?>()
    private val headerListeners = IdentityHashMap<Component, MouseAdapter>()
    private val systemMenus = IdentityHashMap<Component, WindowsSystemMenuBridge>()
    var lastFocus: Component? = frame.mostRecentFocusOwner
        private set

    fun rememberFocus() { frame.focusOwner?.let { lastFocus = it } }

    fun embed(parent: HWND) {
        frame.extendedState = Frame.NORMAL
        show(false)
        embedded = true
        layoutRequired = true
        // Стили окон — 32 бита даже в ARM64/x64. HWND всегда остаётся указателем.
        setStyle(WinUser.GWL_STYLE, (style or WinUser.WS_CHILD) and WinUser.WS_POPUP.inv() and WinUser.WS_OVERLAPPEDWINDOW.inv())
        setStyle(-20, exStyle and 0x00040000.inv()) // WS_EX_APPWINDOW
        Native.setLastError(0)
        user32.SetParent(hwnd, parent)
        val error = Native.getLastError()
        val actualParent = user32.GetParent(hwnd)
        check(handle(actualParent) == handle(parent)) {
            "SetParent failed: $error; expected=${handle(parent)}, actual=${handle(actualParent)}"
        }
        LOG.info("Windows merge: embedded HWND=${handle(hwnd)} parent=${handle(actualParent)}")
        check(user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0020 or 0x0010 or 0x0004 or 0x0001)) {
            "Could not update embedded window styles: ${Native.getLastError()}"
        }
        refreshHeader()
    }

    /** Keep the IDE toolbar and all its menus; only the outer window has caption controls. */
    fun refreshHeader() {
        if (!embedded) return
        fun children(component: Component): Sequence<Component> = sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(children(it)) }
        }
        // SDK 262 installs the frame title directly in IdeRootPane.layeredPane.
        // Never walk the editor/tool-window content tree on the reconciliation timer.
        val headers = frame.rootPane.layeredPane.components.filter { it.javaClass.name.contains("customFrameDecorations.header") }
        val currentComponents = identitySet<Component>()
        val currentBars = identitySet<CustomTitleBar>()
        headers.forEach { header ->
            val getter = header.javaClass.methods.firstOrNull { it.name == "getCustomTitleBar" && it.parameterCount == 0 }
                ?: return@forEach
            val bar = getter.invoke(header) as? CustomTitleBar ?: return@forEach
            currentBars.add(bar)
            systemMenus.getOrPut(header) { WindowsSystemMenuBridge(header, shell) }.update()
            if (!titleBars.containsKey(bar)) titleBars[bar] = bar.properties["controls.visible"]
            if (bar.properties["controls.visible"] != false) {
                bar.putProperty("controls.visible", false)
                header.invalidate()
                frame.rootPane.revalidate()
                header.repaint()
            }
            // A caption gesture must not drag/maximize a child HWND inside the canvas.
            // These listeners do not consume events or replace any IDE menu listeners.
            children(header).forEach { component ->
                currentComponents.add(component)
                if (headerListeners.containsKey(component)) return@forEach
                val listener = object : MouseAdapter() {
                    private fun hit() = bar.forceHitTest(true)
                    override fun mousePressed(e: MouseEvent) = hit()
                    override fun mouseReleased(e: MouseEvent) = hit()
                    override fun mouseClicked(e: MouseEvent) = hit()
                    override fun mouseEntered(e: MouseEvent) = hit()
                    override fun mouseMoved(e: MouseEvent) = hit()
                    override fun mouseDragged(e: MouseEvent) = hit()
                }
                component.addMouseListener(listener)
                component.addMouseMotionListener(listener)
                headerListeners[component] = listener
            }
        }
        headerListeners.keys.filter { it !in currentComponents }.forEach(::removeHeaderListener)
        systemMenus.keys.filter { it !in headers }.forEach { systemMenus.remove(it)?.close() }
        titleBars.keys.filter { it !in currentBars }.forEach { bar -> bar.putProperty("controls.visible", titleBars.remove(bar)) }
    }

    private fun removeHeaderListener(component: Component) {
        headerListeners.remove(component)?.let {
            component.removeMouseListener(it)
            component.removeMouseMotionListener(it)
        }
    }

    private fun restoreHeader() {
        systemMenus.values.forEach { it.close() }
        systemMenus.clear()
        headerListeners.keys.toList().forEach(::removeHeaderListener)
        titleBars.forEach { (bar, value) -> bar.putProperty("controls.visible", value) }
        titleBars.clear()
        frame.rootPane.revalidate()
        frame.rootPane.repaint()
    }

    private fun setStyle(index: Int, value: Int) {
        Native.setLastError(0)
        val previous = user32.SetWindowLong(hwnd, index, value)
        check(previous != 0 || Native.getLastError() == 0) { "Could not update window style: ${Native.getLastError()}" }
    }

    // SW_SHOWNOACTIVATE restores remembered bounds; tab selection must retain the
    // child rectangle just fitted to the canvas, including after a DPI change.
    fun show(visible: Boolean) { user32.ShowWindow(hwnd, if (visible) WinUser.SW_SHOWNA else WinUser.SW_HIDE) }

    /** The borderless canvas bounds are in screen/device coordinates. */
    fun resize(canvasBounds: Rectangle) {
        check(SwingUtilities.isEventDispatchThread())
        if (!embedded || !frame.isDisplayable || !user32.IsWindow(hwnd)) return
        val nativeBounds = RECT()
        check(user32.GetWindowRect(hwnd, nativeBounds)) {
            "Could not read embedded window bounds: ${Native.getLastError()}"
        }
        val configuration = frame.graphicsConfiguration
        val transform = configuration?.defaultTransform
        val displayChanged = layoutConfiguration !== configuration || layoutTransform != transform
        val repositioned = nativeBounds.toRectangle() != canvasBounds
        if (repositioned || displayChanged || layoutRequired) {
            // The native parent uses device pixels. Do not pass them to AWT setBounds,
            // which still treats this reparented JFrame as a top-level window.
            // DPI changes may move the HWND without changing its size. Repair both
            // coordinates, and refresh native insets even if the size stayed the same.
            layoutRequired = true
            check(user32.SetWindowPos(hwnd, null, 0, 0, canvasBounds.width, canvasBounds.height,
                WinUser.SWP_NOACTIVATE or WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED)) {
                "Could not resize embedded window: ${Native.getLastError()}"
            }
        }
        val previousInsets = frame.insets
        if (previousInsets.left != 0 || previousInsets.top != 0 || previousInsets.right != 0 || previousInsets.bottom != 0) {
            val client = RECT()
            check(user32.GetClientRect(hwnd, client)) {
                "Could not read embedded client bounds: ${Native.getLastError()}"
            }
            // A restored IDE frame can retain its decorated peer insets after
            // SetParent even though its native client already fills the HWND.
            // FRAMECHANGED need not deliver WM_SIZE when the rectangle is equal.
            // Let JBR refresh its insets and drawing surface through its normal
            // size handler; changing only rootPane bounds leaves painting offset.
            if (client.right == canvasBounds.width && client.bottom == canvasBounds.height) {
                val size = (client.right.toLong() and 0xffffL) or ((client.bottom.toLong() and 0xffffL) shl 16)
                user32.SendMessage(hwnd, WinUser.WM_SIZE, WPARAM(0), LPARAM(size)) // SIZE_RESTORED
                layoutRequired = true
            }
        }
        val insets = frame.insets
        val root = frame.rootPane
        val expectedBounds = Rectangle(insets.left, insets.top,
            maxOf(0, frame.width - insets.left - insets.right),
            maxOf(0, frame.height - insets.top - insets.bottom))
        if (layoutRequired || !frame.isValid || !root.isValid || root.bounds != expectedBounds) {
            // JBR suppresses COMPONENT_RESIZED for all windows during the shell's
            // native sizing loop. WM_SIZE updates frame dimensions, but the child
            // JFrame receives no WM_SIZING to validate its own Swing hierarchy.
            frame.invalidate()
            frame.validate()
            frame.repaint()
            layoutConfiguration = configuration
            layoutTransform = transform
            layoutRequired = false
        }
    }

    fun restore(targetBounds: Rectangle? = null, targetState: Int? = null) {
        restoreHeader()
        if (!embedded || !frame.isDisplayable || !user32.IsWindow(hwnd)) return
        show(false)
        // Reset the JBR peer as well as the HWND before restoring normal geometry.
        frame.extendedState = Frame.NORMAL
        Native.setLastError(0)
        user32.SetParent(hwnd, originalParent)
        val error = Native.getLastError()
        setStyle(WinUser.GWL_STYLE, style)
        setStyle(-20, exStyle)
        check(handle(user32.GetParent(hwnd)) == handle(originalParent)) { "Could not detach project window: $error" }
        check(user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0020 or 0x0010 or 0x0004 or 0x0001 or 0x0002)) {
            "Could not restore window frame: ${Native.getLastError()}"
        }
        frame.bounds = fitProjectWindowBounds(targetBounds ?: normalBounds)
        val restoredState = targetState ?: state
        // A hidden WFramePeer only records extendedState. Show the normal HWND
        // first so JBR performs the real native transition (also ICONIFIED|MAXIMIZED).
        if (visible) user32.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE)
        frame.extendedState = restoredState
        frame.invalidate()
        frame.validate()
        frame.repaint()
        embedded = false
    }

    companion object {
        private val LOG = Logger.getInstance(EmbeddedProjectWindow::class.java)
        private fun handle(window: HWND?): Long = Pointer.nativeValue(window?.pointer)
    }
}

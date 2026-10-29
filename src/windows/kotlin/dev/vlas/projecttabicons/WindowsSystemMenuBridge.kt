package dev.vlas.projecttabicons

import java.awt.Component
import java.awt.Frame
import java.lang.reflect.Field
import javax.swing.Action
import javax.swing.JFrame

/** Retain IDEA's menu items, icons and close flow; route only window sizing to the shell. */
internal class WindowsSystemMenuBridge(private val header: Component, private val shell: JFrame) : AutoCloseable {
    private data class Binding(val action: Action, val callback: Field, val original: Any?, val name: String)
    private val bindings = mutableListOf<Binding>()

    init {
        val type = generateSequence(header.javaClass as Class<*>?) { it.superclass }
            .firstOrNull { it.name == "com.intellij.openapi.wm.impl.customFrameDecorations.header.FrameHeader" }
        if (type != null) {
            try {
                for (name in listOf("iconifyAction", "restoreAction", "maximizeAction")) {
                    val field = type.getDeclaredField(name).apply { isAccessible = true }
                    val action = field.get(header) as Action
                    val callback = action.javaClass.getDeclaredField("action").apply { isAccessible = true }
                    bindings += Binding(action, callback, callback.get(action), name)
                    val operation: () -> Unit = {
                        shell.extendedState = when (name) {
                            "iconifyAction" -> shell.extendedState or Frame.ICONIFIED
                            "maximizeAction" -> shell.extendedState or Frame.MAXIMIZED_BOTH
                            else -> if (shell.extendedState and Frame.ICONIFIED != 0)
                                shell.extendedState and Frame.ICONIFIED.inv()
                            else shell.extendedState and Frame.MAXIMIZED_BOTH.inv()
                        }
                    }
                    callback.set(action, operation)
                }
            } catch (error: Exception) {
                close()
                throw error
            }
        }
    }

    fun update() {
        val maximized = shell.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        bindings.forEach { binding ->
            binding.action.isEnabled = when (binding.name) {
                "maximizeAction" -> shell.isResizable && !maximized
                "restoreAction" -> maximized || shell.extendedState and Frame.ICONIFIED != 0
                else -> true
            }
        }
    }

    override fun close() {
        bindings.forEach { it.callback.set(it.action, it.original) }
        bindings.clear()
        header.javaClass.getMethod("windowStateChanged").invoke(header)
    }
}

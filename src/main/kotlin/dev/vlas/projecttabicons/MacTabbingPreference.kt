package dev.vlas.projecttabicons

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.mac.foundation.Foundation

internal enum class MacTabbingMode { UNKNOWN, MANUAL, ALWAYS, FULL_SCREEN }

/** Read AppKit's effective preference, including the OS default; never write defaults. */
@Service(Service.Level.APP)
internal class MacTabbingPreference : Disposable {
    @Volatile var mode = MacTabbingMode.UNKNOWN
        private set
    @Volatile private var disposed = false
    private var pending = false
    private var lastRead = 0L

    // Callers are on EDT. Native work must not block it, including inside Settings.
    fun refresh() {
        if (disposed || pending || System.nanoTime() - lastRead < 1_000_000_000L) return
        pending = true
        Foundation.executeOnMainThread(true, false) {
            val value = try { readMode() } catch (error: Exception) {
                LOG.warn("Cannot read macOS window tabbing preference", error)
                MacTabbingMode.UNKNOWN
            }
            ApplicationManager.getApplication().invokeLater({
                if (!disposed) mode = value
                lastRead = System.nanoTime()
                pending = false
            }, ModalityState.any())
        }
    }

    override fun dispose() { disposed = true }

    companion object {
        private val LOG = Logger.getInstance(MacTabbingPreference::class.java)

        /** AppKit thread only. Ask again for each opening, as required by NSWindow. */
        fun readMode(): MacTabbingMode = when (Foundation.invoke("NSWindow", "userTabbingPreference").toInt()) {
            0 -> MacTabbingMode.MANUAL
            1 -> MacTabbingMode.ALWAYS
            2 -> MacTabbingMode.FULL_SCREEN
            else -> MacTabbingMode.UNKNOWN
        }
    }
}

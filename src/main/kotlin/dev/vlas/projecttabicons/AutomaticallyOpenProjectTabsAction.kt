package dev.vlas.projecttabicons

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.SystemInfoRt

class AutomaticallyOpenProjectTabsAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isVisible = SystemInfoRt.isMac || SystemInfoRt.isWindows
        if (SystemInfoRt.isMac) {
            val preference = service<MacTabbingPreference>()
            preference.refresh()
            event.presentation.isEnabled = preference.mode != MacTabbingMode.UNKNOWN && preference.mode != MacTabbingMode.ALWAYS
            event.presentation.description = when (preference.mode) {
                MacTabbingMode.ALWAYS -> "Enabled by macOS. Other applications are unaffected by this plugin."
                MacTabbingMode.UNKNOWN -> "Reading macOS window tabbing preference…"
                else -> "Automatically add new projects to tabs in this IDE only"
            }
        }
    }

    override fun isSelected(event: AnActionEvent): Boolean {
        val state = service<ProjectWindowOpeningSettings>().state
        return if (SystemInfoRt.isMac) state.automaticallyOpenInTabsOnMac || service<MacTabbingPreference>().mode == MacTabbingMode.ALWAYS
        else state.automaticallyOpenInTabs
    }

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        val settings = service<ProjectWindowOpeningSettings>()
        SharedIconSettingsLock.withLock {
            if (SystemInfoRt.isMac) {
                if (service<MacTabbingPreference>().mode in setOf(MacTabbingMode.UNKNOWN, MacTabbingMode.ALWAYS)) return@withLock
                settings.apply(settings.state.copy(automaticallyOpenInTabsOnMac = state))
            } else if (SystemInfoRt.isWindows) settings.apply(settings.state.copy(automaticallyOpenInTabs = state))
        }
    }
}

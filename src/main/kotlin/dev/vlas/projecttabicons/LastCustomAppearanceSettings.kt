package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag

internal data class LastCustomAppearanceState(
    @field:OptionTag @JvmField val appearance: TabAppearanceState? = null,
)

/** Память Custom сохраняется только вместе с Apply; просмотр пресетов и Cancel её не меняют. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsLastCustomAppearance", storages = [Storage("projectTabIconsCustom.xml")])
internal class LastCustomAppearanceSettings :
    SerializablePersistentStateComponent<LastCustomAppearanceState>(LastCustomAppearanceState()) {

    fun remember(value: TabAppearanceState) {
        SharedIconSettingsLock.withLock { updateState { LastCustomAppearanceState(value.asCustomAppearance()) } }
    }

    override fun loadState(state: LastCustomAppearanceState) {
        SharedIconSettingsLock.withLock { super.loadState(LastCustomAppearanceState(state.appearance?.asCustomAppearance())) }
    }
}

// Общий переключатель не является частью запоминаемого оформления.
internal fun TabAppearanceState.asCustomAppearance(): TabAppearanceState =
    normalized().copy(preset = TabAppearancePreset.CUSTOM, userPresetId = null, enabled = true)

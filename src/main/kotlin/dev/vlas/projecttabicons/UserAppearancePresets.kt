package dev.vlas.projecttabicons

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag

internal const val MAX_USER_APPEARANCE_PRESETS = 10
internal const val MAX_USER_PRESET_NAME_LENGTH = 32

internal fun normalizeUserPresetName(name: String): String = name.trim()

internal fun String.takeCodePoints(max: Int): String = substring(0, offsetByCodePoints(0, minOf(codePointCount(0, length), max.coerceAtLeast(0))))

internal fun duplicateUserPresetName(name: String, existing: List<UserAppearancePreset>): String {
    var number = 1
    while (true) {
        val suffix = if (number == 1) " copy" else " copy $number"
        val candidate = normalizeUserPresetName(name).takeCodePoints(MAX_USER_PRESET_NAME_LENGTH - suffix.length) + suffix
        if (userPresetNameError(candidate, existing) == null) return candidate
        number++
    }
}

internal data class UserAppearancePreset(
    @field:OptionTag @JvmField val id: String = "",
    @field:OptionTag @JvmField val name: String = "",
    @field:OptionTag @JvmField val appearance: TabAppearanceState = TabAppearanceState.defaults(),
)

internal data class UserAppearancePresetsState(
    @field:OptionTag @JvmField val presets: List<UserAppearancePreset> = emptyList(),
) {
    fun normalized(): UserAppearancePresetsState = copy(presets = buildList {
        for (preset in presets) {
            val normalized = preset.copy(id = preset.id.trim(), name = normalizeUserPresetName(preset.name),
                appearance = preset.appearance.asCustomAppearance())
            if (normalized.id.isNotEmpty() && normalized.id.length <= 64 && none { it.id == normalized.id } &&
                userPresetNameError(normalized.name, this) == null) add(normalized)
            if (size == MAX_USER_APPEARANCE_PRESETS) break
        }
    })
}

internal fun userPresetNameError(name: String, existing: List<UserAppearancePreset>, exceptId: String? = null): String? {
    val trimmed = normalizeUserPresetName(name)
    return when {
        trimmed.isEmpty() -> "Enter a preset name."
        trimmed.codePointCount(0, trimmed.length) > MAX_USER_PRESET_NAME_LENGTH ->
            "Use at most $MAX_USER_PRESET_NAME_LENGTH characters."
        trimmed.any { it.isISOControl() } -> "Use a name without control characters."
        TabAppearancePreset.entries.any { it.title.equals(trimmed, ignoreCase = true) } ||
            existing.any { it.id != exceptId && normalizeUserPresetName(it.name).equals(trimmed, ignoreCase = true) } ->
            "A preset with this name already exists."
        else -> null
    }
}

/** Именованные оформления, без переключателя включения и назначений конкретным проектам. */
@Service(Service.Level.APP)
@State(name = "ProjectTabIconsUserPresets", storages = [Storage("projectTabIconsPresets.xml")])
internal class UserAppearancePresets :
    SerializablePersistentStateComponent<UserAppearancePresetsState>(UserAppearancePresetsState()) {

    fun apply(presets: List<UserAppearancePreset>) {
        SharedIconSettingsLock.withLock { updateState { UserAppearancePresetsState(presets).normalized() } }
    }

    override fun loadState(state: UserAppearancePresetsState) {
        SharedIconSettingsLock.withLock { super.loadState(state.normalized()) }
    }
}

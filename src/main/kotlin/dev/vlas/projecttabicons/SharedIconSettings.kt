package dev.vlas.projecttabicons

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException

/** Общая граница compare-and-save, в том числе для внешней загрузки XML. Без I/O и ожидания EDT. */
internal object SharedIconSettingsLock {
    fun <T> withLock(action: () -> T): T = synchronized(this, action)
}

internal data class SharedIconSettingsSnapshot(
    val appearance: TabAppearanceState,
    val customAppearance: TabAppearanceState?,
    val userPresets: List<UserAppearancePreset>,
    val projectGroups: ProjectGroupsState,
)

/** Исходное хранилище и исходный UI различаются: например, ещё не сохранённый Custom имеет fallback. */
internal class SharedIconSettingsChanges(
    private val stored: SharedIconSettingsSnapshot,
    private val initialDraft: SharedIconSettingsSnapshot,
    private val draft: SharedIconSettingsSnapshot,
) {
    private val appearanceChanged = draft.appearance != initialDraft.appearance
    private val customChanged = draft.customAppearance != initialDraft.customAppearance
    private val presetsChanged = draft.userPresets != initialDraft.userPresets
    private val groupsChanged = draft.projectGroups != initialDraft.projectGroups

    fun validate(current: SharedIconSettingsSnapshot) {
        val conflicts = buildList<String> {
            fun <T> check(name: String, changed: Boolean, before: T, now: T, wanted: T) {
                if (changed && now != before && now != wanted) add(name)
            }
            check("Tab appearance", appearanceChanged, stored.appearance, current.appearance, draft.appearance)
            check("Custom appearance", customChanged, stored.customAppearance, current.customAppearance, draft.customAppearance)
            check("User presets", presetsChanged, stored.userPresets, current.userPresets, draft.userPresets)
            check("Project groups", groupsChanged, stored.projectGroups, current.projectGroups, draft.projectGroups)
        }
        if (conflicts.isNotEmpty()) throw ConfigurationException(
            "Changed outside this settings page: ${conflicts.joinToString()}. " +
                "Your pending changes have been kept. Use Revert changes on this page to load the latest settings, " +
                "then repeat your edits.",
            "Project Tab Icons settings conflict",
        )
    }

    fun saveTo(store: SharedIconSettingsStore) = SharedIconSettingsLock.withLock {
        val current = store.snapshot()
        // Все конфликты проверяем до первой записи. Совпадающее изменение уже сохранено другим окном.
        validate(current)
        if (appearanceChanged && draft.appearance != current.appearance) store.appearance.apply(draft.appearance)
        if (customChanged && draft.customAppearance != current.customAppearance) {
            store.custom.remember(requireNotNull(draft.customAppearance))
        }
        if (presetsChanged && draft.userPresets != current.userPresets) store.presets.apply(draft.userPresets)
        if (groupsChanged && draft.projectGroups != current.projectGroups) store.groups.apply(draft.projectGroups)
    }
}

internal class SharedIconSettingsStore {
    // Сервисы создаются до захвата общей блокировки: их loadState тоже использует её.
    private val app = ApplicationManager.getApplication()
    val appearance = app.getService(TabAppearanceSettings::class.java)
    val custom = app.getService(LastCustomAppearanceSettings::class.java)
    val presets = app.getService(UserAppearancePresets::class.java)
    val groups = app.getService(ProjectGroups::class.java)

    fun snapshot(): SharedIconSettingsSnapshot = SharedIconSettingsLock.withLock {
        SharedIconSettingsSnapshot(appearance.state, custom.state.appearance, presets.state.presets, groups.state)
    }
}

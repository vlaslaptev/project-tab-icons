package dev.vlas.projecttabicons

internal data class ProjectTabGrouping(val groups: List<Pair<String, Map<String, Int>>>, val memberships: Map<String, String>)
internal fun ProjectGroupsState.tabGrouping(): ProjectTabGrouping = ProjectTabGrouping(
    groups.map { it.id to it.memberPositions }, memberships)

/** Порядок принадлежит окнам, а не названиям: одинаковые имена не объединяют проекты. */
internal class OpenProjectTabOrder<T : Any> {
    var tabs: List<T> = emptyList()
        private set
    private var grouping: ProjectTabGrouping? = null

    fun update(open: List<T>, state: ProjectGroupsState, key: (T) -> String?): List<T> {
        val present = open.toSet()
        val known = tabs.toSet()
        val added = open.filterNot { it in known }
        val retained = tabs.filter { it in present } + added
        val nextGrouping = state.tabGrouping()
        tabs = if (added.isNotEmpty() || grouping != nextGrouping) orderedProjectGroupTabs(retained, state, key) else retained
        grouping = nextGrouping
        return tabs
    }

    /** Устаревший callback закрытого окна не может удалить новые вкладки. */
    fun moved(value: List<T>): Boolean {
        if (value == tabs || value.size != tabs.size || value.toSet() != tabs.toSet()) return false
        tabs = value.toList()
        return true
    }

    fun arrange(state: ProjectGroupsState, key: (T) -> String?): List<T> {
        tabs = orderedProjectGroupTabs(tabs, state, key)
        return tabs
    }

    fun clear() { tabs = emptyList(); grouping = null }
}

package dev.vlas.projecttabicons

/** Группы слева в порядке таблицы, остальные вкладки сохраняют взаимный порядок справа. */
internal fun <T : Any> orderedProjectGroupTabs(tabs: List<T>, state: ProjectGroupsState, key: (T) -> String?): List<T> {
    val grouped = tabs.filter { state.groupFor(key(it).orEmpty()) != null }.toSet()
    if (grouped.isEmpty()) return tabs
    val block = state.groups.flatMap { group ->
        val members = tabs.filter { state.memberships[key(it)] == group.id }
        val slots = MutableList<T?>(members.size) { null }
        val pinned = members.filter { key(it) in group.memberPositions }.sortedBy { group.memberPositions[key(it)] }
        var last = -1
        pinned.forEachIndexed { index, member ->
            // Отсутствующие/закрытые участники не оставляют дыр. Порядок закреплённых остаётся прежним.
            val requested = group.memberPositions.getValue(requireNotNull(key(member))) - 1
            val slot = requested.coerceIn(last + 1, members.size - pinned.size + index)
            slots[slot] = member; last = slot
        }
        val automatic = members.filterNot { it in pinned }.iterator()
        slots.map { it ?: automatic.next() }
    }
    return block + tabs.filterNot { it in grouped }
}

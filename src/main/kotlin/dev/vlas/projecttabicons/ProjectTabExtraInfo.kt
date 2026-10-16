package dev.vlas.projecttabicons

internal enum class TabGitBranchFormat { FULL, COMPACT }

internal enum class GitTabDiffState { LOADING, READY, NO_COMMITS, UNAVAILABLE }

internal data class GitTabDiffStatus(
    val state: GitTabDiffState,
    val added: Long = 0,
    val removed: Long = 0,
)

/** Никакой зависимости от Git4Idea: одна модель для Preview и настоящих вкладок. */
internal sealed interface ProjectTabExtraInfo {
    val text: String
    val accessibleText: String get() = text
    val tooltip: String

    data class Branch(
        val fullName: String,
        val compact: Boolean = false,
        val status: Status? = null,
    ) : ProjectTabExtraInfo {
        val displayName: String = if (compact) compactGitBranch(fullName) else fullName
        override val text: String get() = "[$displayName]" + (status?.let { " ${it.text}" } ?: "")
        override val accessibleText: String get() = "[$fullName]" + (status?.let { " ${it.accessibleText}" } ?: "")
        override val tooltip: String get() = "Git branch: $fullName" + (status?.let { " · ${it.tooltip}" } ?: "")
    }

    data class Status(val value: GitTabDiffStatus) : ProjectTabExtraInfo {
        override val text: String get() = when (value.state) {
            GitTabDiffState.READY -> "+${value.added} -${value.removed}"
            GitTabDiffState.LOADING -> "…"
            GitTabDiffState.NO_COMMITS, GitTabDiffState.UNAVAILABLE -> "—"
        }
        override val tooltip: String get() = when (value.state) {
            GitTabDiffState.READY -> "Git status: $text lines. Saved tracked changes against HEAD, staged and unstaged. Binary and untracked files are excluded."
            GitTabDiffState.LOADING -> "Git status: calculating changed lines…"
            GitTabDiffState.NO_COMMITS -> "Git status: available after the first commit."
            GitTabDiffState.UNAVAILABLE -> "Git status: changed-line count is unavailable."
        }
    }
}

/** Убираем префиксы до последнего /; максимум 13 символов, включая многоточие слева; сохраняем конец. */
internal fun compactGitBranch(branch: String): String {
    val name = branch.substringAfterLast('/').ifEmpty { branch }
    val limit = 13
    return if (name.codePointCount(0, name.length) <= limit) name
        else "…" + name.substring(name.offsetByCodePoints(name.length, -(limit - 1)))
}

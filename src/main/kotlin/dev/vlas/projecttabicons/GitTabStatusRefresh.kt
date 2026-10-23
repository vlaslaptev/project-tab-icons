package dev.vlas.projecttabicons

/** Планирование и публикация на EDT; часы и таймер внедряются для виртуального времени. */
internal class GitTabStatusRefresh(
    private val nowMillis: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val start: (Long) -> Unit,
    private val changed: () -> Unit,
) {
    @Volatile var generation = 0L
        private set
    var status: GitTabDiffStatus? = null
        private set
    private var cancelTimer: (() -> Unit)? = null
    private var running = false
    private var dirty = false
    private var firstEvent = 0L
    private var lastEvent = 0L
    private var disposed = false

    fun reset(initial: GitTabDiffStatus?) {
        generation++
        cancelTimer?.invoke()
        cancelTimer = null
        running = false
        dirty = false
        status = initial
    }

    fun invalidate() {
        if (disposed) return
        val now = nowMillis()
        if (!dirty) firstEvent = now
        lastEvent = now
        dirty = true
        if (!running && cancelTimer == null) arm()
    }

    fun complete(run: Long, result: GitTabDiffStatus) {
        if (disposed || run != generation || !running) return
        running = false
        val notify = status != result
        status = result
        if (dirty) arm()
        if (notify) changed()
    }

    fun dispose() {
        disposed = true
        reset(null)
    }

    private fun remainingDelay(): Long =
        (minOf(lastEvent + QUIET_MILLIS, firstEvent + MAX_WAIT_MILLIS) - nowMillis()).coerceAtLeast(0)

    private fun arm() {
        val run = generation
        cancelTimer = schedule(remainingDelay()) {
            if (!disposed && run == generation) {
                cancelTimer = null
                if (remainingDelay() > 0) {
                    arm()
                } else {
                    dirty = false
                    running = true
                    start(run)
                }
            }
        }
    }

    companion object {
        const val QUIET_MILLIS = 500L
        // Ограничено ожидание запуска; время Git-команды и занятость EDT сюда не входят.
        const val MAX_WAIT_MILLIS = 2_000L
    }
}

/** Структурные события не фильтруются по кешу: delete/move могут затронуть tracked-файлы. */
internal fun affectsGitTabStatusPath(
    root: String,
    path: String,
    contentChange: Boolean,
    knownIgnoredOrUntracked: (String) -> Boolean,
): Boolean = path.startsWith("$root/") && path != "$root/.git" && !path.startsWith("$root/.git/") &&
    (!contentChange || !knownIgnoredOrUntracked(path))

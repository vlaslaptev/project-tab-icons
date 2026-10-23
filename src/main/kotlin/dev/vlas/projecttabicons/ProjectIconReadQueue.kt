package dev.vlas.projecttabicons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

/** Маркеры принадлежат отдельным запросам; все обращения к ним выполняются на UI-потоке. */
internal class ProjectIconReadQueue(
    private val scope: CoroutineScope,
    private val workerContext: CoroutineContext,
    private val uiContext: CoroutineContext,
    private val postToUi: (() -> Unit) -> Unit,
    private val canPublish: () -> Boolean,
) {
    private val loading = IdentityHashMap<Any, Any>()

    fun invalidate() = loading.clear()

    fun <T> launch(
        delegate: Any,
        prepare: () -> Unit = {},
        read: suspend () -> T,
        publish: (T) -> Unit,
    ): Job? {
        if (loading.containsKey(delegate)) return null
        val token = Any()
        loading[delegate] = token
        fun release(): Boolean {
            if (loading[delegate] !== token) return false
            loading.remove(delegate)
            return true
        }
        return try {
            prepare()
            scope.launch(workerContext) {
                val result = read()
                withContext(uiContext) {
                    if (loading[delegate] === token && canPublish()) {
                        release()
                        publish(result)
                    }
                }
            }.also { job ->
                // В отличие от finally внутри launch, срабатывает и при отмене до старта тела.
                // Публикация очистки не зависит от уже отменённого scope/Job.
                job.invokeOnCompletion { postToUi { release() } }
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }
}

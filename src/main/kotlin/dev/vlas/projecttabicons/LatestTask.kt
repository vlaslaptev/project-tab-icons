package dev.vlas.projecttabicons

import java.util.concurrent.Future

/** UI-owned: отмена — оптимизация; только идентичность запроса разрешает публикацию. */
internal class LatestTask {
    private var current: Any? = null
    private var worker: Future<*>? = null
    fun begin(): Any { cancel(); return Any().also { current = it } }
    fun accepts(token: Any): Boolean = current === token
    fun attach(token: Any, future: Future<*>) {
        if (accepts(token)) worker = future else future.cancel(true)
    }
    fun cancel() { current = null; worker?.cancel(true); worker = null }
}

/** У закрытой и повторно открытой панели независимые маркеры отложенного обновления. */
internal class ScopedUiRefresh(private val post: (() -> Unit) -> Unit) {
    private val queued = java.util.IdentityHashMap<Any, Unit>()
    fun request(scope: Any, active: () -> Boolean, refresh: () -> Unit) {
        synchronized(queued) { if (queued.put(scope, Unit) != null) return }
        post {
            synchronized(queued) { queued.remove(scope) }
            if (active()) refresh()
        }
    }
}

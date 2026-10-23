package dev.vlas.projecttabicons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** До входа в UI один проход на пачку событий; события из apply получают следующий проход. */
internal class CoalescingRefreshQueue(
    private val scope: CoroutineScope,
    private val worker: CoroutineContext,
    private val ui: CoroutineContext,
) {
    private val queued = AtomicBoolean()

    fun <T> submit(prepare: suspend () -> T, apply: (T) -> Unit) {
        if (!queued.compareAndSet(false, true)) return
        val handedToUi = AtomicBoolean()
        scope.launch(worker) {
            val result = prepare()
            withContext(ui) {
                handedToUi.set(true)
                queued.set(false)
                apply(result)
            }
        }.invokeOnCompletion { if (!handedToUi.get()) queued.set(false) }
    }
}

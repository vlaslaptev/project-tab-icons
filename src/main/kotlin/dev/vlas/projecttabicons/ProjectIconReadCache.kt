package dev.vlas.projecttabicons

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.IdentityHashMap
import javax.swing.Icon

/** Ключ включает размер: один delegate может обслуживать несколько представлений. Все методы — UI. */
internal class ProjectIconReadCache(
    private val queue: ProjectIconReadQueue,
    private val read: ((Icon, Int) -> IconColorRead)? = null,
    private val resolve: (IconColorRead) -> IconColorRead,
    private val changed: (Icon, Boolean) -> Unit,
    private val report: (Exception) -> Unit,
    private val retryDelay: suspend () -> Unit = { delay(200) },
) {
    private class Entry(var ready: Boolean = false, var result: IconColorRead? = null)
    private val entries = IdentityHashMap<Icon, MutableMap<Int, Entry>>()

    fun get(delegate: Icon, size: Int): IconColorRead? = entries[delegate]?.get(size)?.result

    fun request(delegate: Icon, size: Int, readCurrent: (() -> IconColorRead)? = null,
                prepare: () -> Unit = {}): IconColorRead? {
        val entry = entries.getOrPut(delegate) { mutableMapOf() }.getOrPut(size) { Entry() }
        if (entry.ready) return entry.result
        queue.launch(entry, prepare, read = {
            var pending = false
            val result = try { readCurrent?.invoke() ?: requireNotNull(read)(delegate, size) }
            catch (_: IconColorNotReady) { pending = true; null }
            catch (error: ProcessCanceledException) { throw error }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error); null }
            if (pending) retryDelay()
            result to pending
        }, publish = { (result, pending) ->
            if (entries[delegate]?.get(size) === entry) {
                if (!pending) { entry.result = result?.let(resolve); entry.ready = true }
                changed(delegate, !pending)
            }
        })
        return null
    }

    fun clear() { entries.clear(); queue.invalidate() }
    fun invalidate(delegate: Icon) { entries.remove(delegate) }
    fun retain(delegates: Set<Icon>) { entries.keys.retainAll(delegates) }
}

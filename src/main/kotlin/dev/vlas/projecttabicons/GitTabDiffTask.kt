package dev.vlas.projecttabicons

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import java.util.concurrent.Future

/** Отмена принадлежит конкретному запуску, включая таймер и уже поставленную публикацию. */
internal class GitTabDiffTask(
    private val submit: (() -> Unit) -> Future<*>,
    private val timeout: (() -> Unit) -> (() -> Unit),
    private val post: (() -> Unit) -> Unit,
    private val report: (Exception) -> Unit,
) {
    private class Run {
        val progress = ProgressIndicatorBase()
        var worker: Future<*>? = null
        var cancelTimeout: (() -> Unit)? = null
    }
    private val lock = Any()
    private var current: Run? = null

    fun start(read: (ProgressIndicatorBase) -> GitTabDiffStatus, publish: (GitTabDiffStatus) -> Unit) {
        cancel()
        synchronized(lock) {
            val run = Run(); current = run
            run.cancelTimeout = timeout { run.progress.cancel() }
            try {
                run.worker = submit {
                    val result = try { run.progress.checkCanceled(); read(run.progress).also { run.progress.checkCanceled() } }
                    catch (_: ProcessCanceledException) { GitTabDiffStatus(GitTabDiffState.UNAVAILABLE) }
                    catch (error: Exception) { report(error); GitTabDiffStatus(GitTabDiffState.UNAVAILABLE) }
                    finally { run.cancelTimeout?.invoke() }
                    post {
                        val accepted = synchronized(lock) {
                            if (current === run) { current = null; true } else false
                        }
                        if (accepted) publish(result)
                    }
                }
            } catch (error: Throwable) {
                current = null; run.cancelTimeout?.invoke(); run.progress.cancel(); throw error
            }
        }
    }

    fun cancel() {
        val previous = synchronized(lock) { current.also { current = null } } ?: return
        previous.progress.cancel()
        previous.worker?.cancel(true)
        previous.cancelTimeout?.invoke()
    }
}

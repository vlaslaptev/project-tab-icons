package dev.vlas.projecttabicons

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException

/** Граница ошибок всего refresh. Состояние меняется на EDT; фон читает только blocked. */
internal class ProjectTabRefreshRecovery(
    private val restore: (incompatible: Boolean) -> Unit,
    private val scheduleRetry: (delayMillis: Long, resume: () -> Unit) -> Unit,
    private val requestRefresh: () -> Unit,
    private val report: (Throwable, incompatible: Boolean) -> Unit,
) {
    @Volatile var blocked = false
        private set
    private var incompatible = false
    private var failures = 0
    private var generation = 0L

    fun run(update: () -> Unit) {
        if (blocked) return
        try {
            update()
            failures = 0
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(error, permanent = false)
        } catch (error: LinkageError) {
            failed(error, permanent = true)
        }
    }

    fun resetTransientFailures() {
        if (incompatible) return
        generation++
        failures = 0
        blocked = false
    }

    fun dispose() {
        generation++
        blocked = true
    }

    private fun failed(error: Throwable, permanent: Boolean) {
        // События от снятия собственного оформления не запускают новый refresh.
        blocked = true
        incompatible = permanent
        failures = (failures + 1).coerceAtMost(MAX_AUTOMATIC_RETRIES + 1)
        try {
            restore(incompatible)
        } catch (cancelled: ProcessCanceledException) {
            blocked = incompatible
            throw cancelled
        } catch (cancelled: CancellationException) {
            blocked = incompatible
            throw cancelled
        } catch (cleanupError: Exception) {
            if (cleanupError !== error) error.addSuppressed(cleanupError)
        } catch (cleanupError: LinkageError) {
            incompatible = true
            if (cleanupError !== error) error.addSuppressed(cleanupError)
        }
        if (failures == 1 || incompatible) report(error, incompatible)
        if (incompatible) return
        if (failures > MAX_AUTOMATIC_RETRIES) {
            // Подписки остаются: следующее внешнее событие может восстановить UI.
            // Самостоятельные повторы исчерпаны, одинаковая ошибка больше не логируется.
            blocked = false
            return
        }
        val token = ++generation
        scheduleRetry(1_000L shl (failures - 1)) {
            if (generation == token && !incompatible) {
                blocked = false
                requestRefresh()
            }
        }
    }

    private companion object {
        const val MAX_AUTOMATIC_RETRIES = 3
    }
}

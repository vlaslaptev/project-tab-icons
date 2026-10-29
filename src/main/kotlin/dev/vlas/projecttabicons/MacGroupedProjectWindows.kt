package dev.vlas.projecttabicons

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.mac.MacWinTabsHandlerV2
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Explicitly merge only the requested projects into the invoking window's native group. */
internal suspend fun joinMacGroupedProjectWindows(anchor: Project, projects: List<Project>): IdeFrameImpl? =
    withContext(Dispatchers.EDT) {
        val helpers = projectFrameHelpers()
        val destination = helpers.entries.firstOrNull { it.value.project === anchor }?.key ?: return@withContext null
        val requested = projects.map { project ->
            helpers.entries.firstOrNull { it.value.project === project }?.key ?: return@withContext null
        }.plus(destination).distinct()
        if (requested.any { !it.isDisplayable || it.rootPane.getClientProperty(WINDOW_TABS_CONTAINER_KEY) == null }) return@withContext null

        // Resolve the SDK 262 bridge before mutating any native window group.
        val remove = MacWinTabsHandlerV2::class.java.getDeclaredMethod(
            "removeFromFrame", IdeFrameImpl::class.java, IdeFrameImpl::class.java,
        ).apply { isAccessible = true }
        val create = MacWinTabsHandlerV2::class.java.getDeclaredMethod(
            "createTabBarsForFrame", IdeFrameImpl::class.java, ProjectFrameHelper::class.java, Array<IdeFrameImpl>::class.java,
        ).apply { isAccessible = true }

        coroutineContext.ensureActive()
        // A native merge and its Swing synchronization form one short indivisible operation.
        // Cancellation may stop opening more projects, but must not leave stale tab bars behind.
        withContext(NonCancellable) {
            val result = CompletableDeferred<List<List<IdeFrameImpl>>?>()
            Foundation.executeOnMainThread(true, false) {
                try {
                    val windows = helpers.keys.associateWith { MacUtil.getWindowFromJavaWindow(it) }
                        .filterValues { it != ID.NIL }
                    if (requested.any { it !in windows }) { result.complete(null); return@executeOnMainThread }
                    val byWindow = windows.entries.associate { it.value.toLong() to it.key }
                    val target = windows.getValue(destination)
                    val identifier = Foundation.invoke(target, "tabbingIdentifier")
                    if (identifier == ID.NIL || requested.any {
                            Foundation.invoke(identifier, "isEqualToString:", Foundation.invoke(windows.getValue(it), "tabbingIdentifier")).toInt() == 0
                        }) { result.complete(null); return@executeOnMainThread }
                    val affected = requested.flatMap { nativeGroupWindows(windows.getValue(it)) }.distinctBy { it.toLong() }
                    if (affected.any { it.toLong() !in byWindow }) { result.complete(null); return@executeOnMainThread }
                    var changed = false
                    for (frame in requested) {
                        val window = windows.getValue(frame)
                        if (nativeGroupWindows(target).none { it.toLong() == window.toLong() }) {
                            Foundation.invoke(target, "addTabbedWindow:ordered:", window, 1L)
                            changed = true
                        }
                    }
                    if (changed) {
                        Foundation.invoke(Foundation.invoke(target, "tabGroup"), "setSelectedWindow:", target)
                        result.complete(affected.map { window ->
                            nativeGroupWindows(window).map { byWindow.getValue(it.toLong()) }
                        }.distinctBy { it.toSet() })
                    } else result.complete(emptyList())
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
            val groups = result.await() ?: return@withContext null
            if (groups.isNotEmpty()) {
                val current = projectFrameHelpers()
                if (groups.flatten().any { !it.isDisplayable || it !in current }) return@withContext null
                // Rebuild the destination and any source groups affected by moving their members.
                // Unrelated windows and native groups are left intact.
                groups.flatten().distinct().forEach { remove.invoke(null, it, null) }
                groups.filter { it.size > 1 }.forEach { group ->
                    val members = group.toTypedArray()
                    group.forEach { create.invoke(null, it, current.getValue(it), members) }
                }
            }
            destination
        }
    }

internal fun projectFrameHelpers(): Map<IdeFrameImpl, ProjectFrameHelper> = WindowManager.getInstance().allProjectFrames
    .filterIsInstance<ProjectFrameHelper>().filter { it.project?.isDisposed == false }
    .associateBy { it.frame }

private fun nativeGroupWindows(window: ID): List<ID> {
    val tabs = Foundation.invoke(window, "tabbedWindows")
    val count = Foundation.invoke(tabs, "count").toInt()
    return if (count == 0) listOf(window) else (0 until count).map { Foundation.invoke(tabs, "objectAtIndex:", it) }
}

package dev.vlas.projecttabicons

import com.intellij.ide.ProjectFileIcon
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.lang.ref.WeakReference
import javax.swing.CellRendererPane
import javax.swing.Icon
import javax.swing.SwingUtilities

internal fun projectSvgIcon(asset: SvgAsset, size: Int): Icon = PreparedProjectIcon(asset.digest, true, size) {
    asset.tabArtwork() ?: asset.bytes
}

internal fun projectPngIcon(bytes: ByteArray, size: Int): Icon = PreparedProjectIcon(sha256(bytes), false, size) { bytes }

internal data class ProjectRasterKey(val digest: String, val svg: Boolean, val size: Int,
                                     val width: Int, val sysScale: Float, val pixScale: Float)

/** Обёртка не хранит растр: все изображения, включая старые масштабы, ограничены общим LRU. */
private class PreparedProjectIcon(val digest: String, val svg: Boolean, val size: Int,
                                  val bytes: () -> ByteArray) : Icon {
    private var previous: ProjectRasterKey? = null
    override fun getIconWidth(): Int = JBUI.scale(size)
    override fun getIconHeight(): Int = iconWidth

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g as? Graphics2D ?: return
        val key = ProjectRasterKey(digest, svg, size, iconWidth,
            JBUIScale.sysScale(graphics), JBUI.pixScale(graphics.deviceConfiguration))
        val cache = ApplicationManager.getApplication().getService(ProjectIconRasterCache::class.java)
        val ready = cache.request(key, bytes, c)
        val image = ready ?: previous?.let(cache::get) ?: return
        if (ready != null) previous = key
        val copy = graphics.create() as Graphics2D
        try {
            copy.translate(x, y)
            copy.scale(iconWidth.toDouble() / image.iconWidth, iconHeight.toDouble() / image.iconHeight)
            image.paintIcon(c, copy, 0, 0)
        } finally { copy.dispose() }
    }
}

/** Только видимые размеры; парсинг, очистка SVG и getScaledIcon никогда не выполняются в paint. */
@Service(Service.Level.APP)
internal class ProjectIconRasterCache(scope: CoroutineScope) : Disposable {
    @Volatile private var disposed = false
    private val workers = Semaphore(2)
    // Результат меняет только кеш изображений и repaint. Он нужен и внутри модальных
    // диалогов выбора иконки/цвета; PSI, VFS и настройки здесь не меняются.
    private val cache = ProjectRasterStore(ProjectIconReadQueue(scope,
        Dispatchers.Default + ModalityState.any().asContextElement(), Dispatchers.UI,
        postToUi = { SwingUtilities.invokeLater(it) }, canPublish = { !disposed }), render = { key, bytes ->
        workers.withPermit {
            try {
                // SDK применяет текущий user scale внутри getScaledIcon. Не публикуем результат
                // старого запроса, если масштаб успел измениться во время фоновой подготовки.
                if (JBUI.scale(key.size) != key.width) null to true else {
                    val source = RecentProjectIconHelper.createIcon(bytes(), key.svg, key.size) as ProjectFileIcon
                    val image = source.iconData.getScaledIcon(key.sysScale, key.pixScale)
                    image to (JBUI.scale(key.size) != key.width)
                }
            } catch (error: ProcessCanceledException) { throw error }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Logger.getInstance(ProjectIconRasterCache::class.java).debug("Cannot rasterize a prepared project icon", error)
                null to false
            }
        }
    })

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC,
            LafManagerListener { cache.clear() })
    }

    fun get(key: ProjectRasterKey): Icon? = if (disposed) null else cache.get(key)
    fun request(key: ProjectRasterKey, bytes: () -> ByteArray, component: Component?): Icon? =
        if (disposed) null else cache.request(key, bytes, component)

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) cache.clear() else SwingUtilities.invokeLater { cache.clear() }
    }
}

/** Все методы — UI. Очередь защищает от отмены, а identity записи — от eviction/reload. */
internal class ProjectRasterStore(
    private val queue: ProjectIconReadQueue,
    private val limit: Int = 256,
    private val render: suspend (ProjectRasterKey, () -> ByteArray) -> Pair<Icon?, Boolean>,
) {
    private class Entry {
        var ready = false
        var image: Icon? = null
        var job: Job? = null
        val viewers = mutableListOf<WeakReference<Component>>()
        fun repaint() = viewers.forEach { it.get()?.repaint() }
    }
    private val entries = LinkedHashMap<ProjectRasterKey, Entry>(32, 0.75f, true)
    fun get(key: ProjectRasterKey): Icon? = entries[key]?.image

    fun request(key: ProjectRasterKey, bytes: () -> ByteArray, component: Component?): Icon? {
        val entry = entries.getOrPut(key) { Entry() }
        val viewer = component?.let { SwingUtilities.getWindowAncestor(it)
            ?: SwingUtilities.getAncestorOfClass(CellRendererPane::class.java, it)?.parent ?: it }
        entry.viewers.removeAll { it.get() == null }
        if (viewer != null && entry.viewers.none { it.get() === viewer })
            entry.viewers.add(WeakReference(viewer))
        while (entries.size > limit) {
            val iterator = entries.entries.iterator()
            iterator.next().value.job?.cancel()
            iterator.remove()
        }
        if (!entry.ready) entry.job = queue.launch(entry, read = { render(key, bytes) }, publish = { (image, obsolete) ->
            if (entries[key] === entry) {
                if (obsolete) entries.remove(key) else {
                    entry.image = image
                    entry.ready = true
                }
                entry.repaint()
            }
        }) ?: entry.job
        return entry.image
    }

    fun clear() {
        queue.invalidate()
        entries.values.forEach { it.job?.cancel(); it.repaint() }
        entries.clear()
    }
}

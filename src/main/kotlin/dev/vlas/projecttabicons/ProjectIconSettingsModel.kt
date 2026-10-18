package dev.vlas.projecttabicons

import com.intellij.openapi.project.Project
import java.awt.Color
import java.nio.file.Path
import javax.swing.Icon

internal data class OpenProjectIconTarget(val project: Project, val path: Path?, val preview: Icon?,
                                          val automaticColor: Color? = null) {
    val key: String = path?.let(::projectIconPathKey) ?: "unavailable:${System.identityHashCode(project)}"
    val name: String get() = project.name
}

internal data class IconChoice(val asset: SvgAsset, val color: Color?)

internal data class LoadedProjectIcon(
    val target: OpenProjectIconTarget,
    val snapshot: IconSnapshot?,
    val choice: IconChoice?,
    val color: Color?,
    val failure: String? = null,
)

/** Черновик строки: до Apply не изменяются ни SVG, ни независимые цвета проекта. */
internal class ProjectIconSettingsRow(loaded: LoadedProjectIcon, overrides: ProjectColorOverrides) {
    var target = loaded.target
        private set
    var snapshot = loaded.snapshot
        private set
    var current = loaded.choice
        private set
    private var currentColor = loaded.color
    var failure = loaded.failure
        private set
    private var colorBaseline = overrides
    var colorOverrides = overrides
        private set
    private var choiceDraft: IconChoice? = null
    var conflicted = false
        private set

    val key: String get() = target.key
    val ready: Boolean get() = snapshot != null && failure == null && !target.project.isDisposed
    val svgModified: Boolean get() = choiceDraft != null
    val colorModified: Boolean get() = colorOverrides != colorBaseline
    val modified: Boolean get() = svgModified || colorModified
    val activeChoice: IconChoice? get() = choiceDraft ?: current
    val builder: BuilderSelection? get() = activeChoice?.asset?.builder
    val automaticIcon: Boolean get() = activeChoice == null && snapshot?.files?.isEmpty() == true
    val editableIconColor: Boolean get() = builder != null || automaticIcon
    val sourceColor: Color? get() = if (choiceDraft != null) choiceDraft?.color else current?.color ?: currentColor
    val color: Color? get() = if (automaticIcon) colorOverrides.icon ?: sourceColor else sourceColor
    val outlineColor: Color? get() = colorOverrides.outline ?: color
    val tabColor: Color? get() = colorOverrides.tab ?: color

    fun color(target: ProjectColorTarget): Color? = when (target) {
        ProjectColorTarget.ICON -> color
        ProjectColorTarget.OUTLINE -> outlineColor
        ProjectColorTarget.TAB -> tabColor
    }

    fun previewIcon(avatarColor: Color? = colorOverrides.icon,
                    initials: TabIconInitials = DEFAULT_ICON_INITIALS,
                    letterWeight: TabIconLetterWeight = TabIconLetterWeight.BOLD): Icon? =
        if (automaticIcon) (target.preview as? CenteredProjectIcon)
            ?.withAvatarAppearance(avatarColor, initials, letterWeight) ?: target.preview
        else target.preview

    fun choose(value: IconChoice) {
        // Явное назначение также объединяет light/dark варианты в один SVG.
        val variants = snapshot?.files?.keys.orEmpty().any { it != "icon.svg" }
        choiceDraft = value.takeIf { it.asset.digest != current?.asset?.digest || variants }
        // Автоматические цвета следуют новой иконке; ручные outline/tab не сбрасываются.
        colorOverrides = colorOverrides.copy(icon = null)
    }

    fun changeColor(target: ProjectColorTarget, value: Color?) {
        if (target == ProjectColorTarget.ICON && builder != null) {
            val generated = requireNotNull(builder)
            val selected = requireNotNull(value) { "Choose an icon color." }
            val contour = ContourCatalog.entries.first { it.id == generated.contourId }
            val asset = SvgAsset(contour.svg(selected), contour.title,
                BuilderSelection(contour.id, selected.hex(), background = false))
            choose(IconChoice(asset, selected))
        } else {
            require(target != ProjectColorTarget.ICON || automaticIcon) { "Original SVG/PNG colors are preserved." }
            colorOverrides = colorOverrides.with(target, value)
        }
    }

    fun reload(loaded: LoadedProjectIcon, overrides: ProjectColorOverrides, keepDraft: Boolean) {
        if (!keepDraft) {
            target = loaded.target
            snapshot = loaded.snapshot
            current = loaded.choice
            currentColor = loaded.color
            failure = loaded.failure
            colorBaseline = overrides
            colorOverrides = overrides
            choiceDraft = null
            conflicted = false
            return
        }
        target = loaded.target
        failure = loaded.failure
        if (modified && (snapshot?.fingerprint != loaded.snapshot?.fingerprint || snapshot?.directory != loaded.snapshot?.directory)) {
            conflicted = true
        }
        if (!svgModified) {
            snapshot = loaded.snapshot
            current = loaded.choice
            currentColor = loaded.color
        }
        // Обновляем каждый чистый цвет независимо; остальные черновики сохраняем.
        ProjectColorTarget.entries.forEach { channel ->
            if (colorOverrides.value(channel) == colorBaseline.value(channel)) {
                colorBaseline = colorBaseline.with(channel, overrides.value(channel))
                colorOverrides = colorOverrides.with(channel, overrides.value(channel))
            }
        }
    }

    fun saved(installed: IconSnapshot?) {
        installed?.let {
            snapshot = it
            current = choiceDraft ?: current
            currentColor = current?.color
        }
        choiceDraft = null
        colorBaseline = colorOverrides
        conflicted = false
        failure = null
    }

    fun discardDraft() {
        choiceDraft = null
        colorOverrides = colorBaseline
        conflicted = false
    }

    fun markConflict() {
        conflicted = true
    }
}

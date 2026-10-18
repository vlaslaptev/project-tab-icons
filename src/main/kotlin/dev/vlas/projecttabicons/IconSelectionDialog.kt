package dev.vlas.projecttabicons

import com.intellij.ide.RecentProjectIconHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.util.Locale
import java.util.concurrent.Future
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.SwingConstants

/** Диалог редактирует только черновик иконки; цвет остаётся отдельным действием строки. */
internal class IconSelectionDialog(
    parent: JComponent,
    private val project: Project,
    projectName: String,
    private val color: Color,
    initial: IconChoice?,
    available: List<IconChoice>,
) : DialogWrapper(parent, true) {
    private val body = JPanel(BorderLayout(0, JBUI.scale(16)))
    private val preview = IconPreviewWell()
    private val selectionLabel = JBLabel("Choose an icon").apply { font = font.deriveFont(Font.BOLD) }
    private val selectionDetail = settingsHint("Preview at actual tab sizes")
    private val samples = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0))
    private val status = JTextArea("Saved with Apply · Replaces light and dark icon variants", 3, 0).apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getContextHelpForeground()
        accessibleContext.accessibleName = "Icon import status"
    }
    private val tabs = JTabbedPane()
    private val search = SearchTextField(false)
    private val customSearch = SearchTextField(false)
    private val category = ComboBox((listOf("All categories") + ContourCatalog.entries.map { it.category }.distinct().sorted()).toTypedArray())
    private val resultCount = settingsHint("")
    private val customCount = settingsHint("")
    private val shapeIcons = mutableMapOf<String, Icon>()
    private val customIcons = mutableMapOf<String, Icon>()
    private val shapes = IconChoiceGrid<Contour>({ it.title }) { shape ->
        shapeIcons.getOrPut(shape.id) {
            RecentProjectIconHelper.createIcon(shape.svg(color), true, 28)
        }
    }
    private val customGrid = IconChoiceGrid<IconChoice>(
        { it.asset.label.substringBefore(" · ") }, { it.asset.label.substringAfter(" · ", "") },
    ) { choice ->
        customIcons.getOrPut(choice.asset.digest) { RecentProjectIconHelper.createIcon(choice.asset.tabArtwork() ?: choice.asset.bytes, true, 28) }
    }
    private val importButton = JButton("Import SVG…")
    private var gallery = (listOfNotNull(initial) + available).distinctBy { it.asset.digest }
    private var custom = initial
    private var shapeId = initial?.asset?.builder?.contourId
    private var useShape = shapeId != null || initial == null
    private var filtering = false
    private var worker: Future<*>? = null
    private var importGeneration = 0
    private var closed = false

    init {
        title = "Choose icon · $projectName"
        setOKButtonText("Choose icon")
        body.border = JBUI.Borders.empty(8)
        body.preferredSize = JBUI.size(680, 600)
        body.minimumSize = JBUI.size(560, 460)
        body.add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(preview, BorderLayout.WEST)
            add(JPanel(GridBagLayout()).apply {
                minimumSize = Dimension(0, JBUI.scale(56))
                add(selectionLabel, GridBagConstraints().apply {
                    gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                })
                add(selectionDetail, GridBagConstraints().apply {
                    gridx = 0; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST; insets = JBUI.insetsTop(4)
                })
            }, BorderLayout.CENTER)
            add(samples, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        search.textEditor.emptyText.text = "Search ${ContourCatalog.entries.size} icons…"
        search.textEditor.accessibleContext.accessibleName = "Search built-in icons"
        customSearch.textEditor.emptyText.text = "Search project SVGs"
        customSearch.textEditor.accessibleContext.accessibleName = "Search project SVGs"
        category.accessibleContext.accessibleName = "Icon category"
        shapes.accessibleContext.accessibleName = "Built-in icons"
        customGrid.accessibleContext.accessibleName = "Project SVGs"
        val toolbarHeight = maxOf(search.preferredSize.height, category.preferredSize.height,
            importButton.preferredSize.height, JBUI.scale(30))
        listOf(search, customSearch, category, importButton).forEach {
            it.preferredSize = Dimension(it.preferredSize.width, toolbarHeight)
        }
        customGrid.fixedCellWidth = JBUI.scale(162)
        customGrid.fixedCellHeight = JBUI.scale(100)
        val filterBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(search, BorderLayout.CENTER); add(category, BorderLayout.EAST)
        }
        tabs.addTab("Built-in icons", catalogPanel(filterBar, shapes, resultCount))
        tabs.addTab("Project SVGs", catalogPanel(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(customSearch, BorderLayout.CENTER); add(importButton, BorderLayout.EAST)
        }, customGrid, customCount))
        body.add(tabs, BorderLayout.CENTER)
        body.add(status, BorderLayout.SOUTH)
        search.addDocumentListener(onTextChanged { filterShapes() })
        customSearch.addDocumentListener(onTextChanged { filterCustom() })
        category.addActionListener { filterShapes() }
        shapes.addListSelectionListener { event ->
            if (!filtering && !event.valueIsAdjusting) shapes.selectedValue?.let {
                shapeId = it.id
                useShape = true
                showSelection()
            }
        }
        customGrid.addListSelectionListener { event ->
            if (!filtering && !event.valueIsAdjusting) customGrid.selectedValue?.let {
                custom = it
                useShape = false
                showSelection()
            }
        }
        importButton.addActionListener { importSvg() }
        filterShapes()
        filterCustom()
        tabs.selectedIndex = if (useShape) 0 else 1
        tabs.addChangeListener {
            useShape = tabs.selectedIndex == 0
            showSelection()
        }
        focusGridOnDown(search, shapes)
        focusGridOnDown(customSearch, customGrid)
        body.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "searchIcons")
        body.actionMap.put("searchIcons", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                (if (tabs.selectedIndex == 0) search else customSearch).textEditor.requestFocusInWindow()
            }
        })
        init()
        showSelection()
        initValidation()
        Disposer.register(disposable, Disposable {
            closed = true
            importGeneration++
            worker?.cancel(true)
        })
    }

    private fun catalogPanel(toolbar: JComponent, grid: IconChoiceGrid<*>, count: JComponent): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12, 0, 0, 0)
            grid.addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && grid.isShowing) {
                    revealSelection(grid)
                }
            }
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(grid).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.customLine(JBColor.border())
            }, BorderLayout.CENTER)
            add(count, BorderLayout.SOUTH)
        }

    private fun focusGridOnDown(field: SearchTextField, grid: IconChoiceGrid<*>) {
        field.textEditor.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DOWN"), "focusIcons")
        field.textEditor.actionMap.put("focusIcons", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (grid.model.size == 0) return
                if (grid.selectedIndex < 0) grid.selectedIndex = 0
                grid.ensureIndexIsVisible(grid.selectedIndex)
                grid.requestFocusInWindow()
            }
        })
    }

    private fun filterShapes() {
        val terms = iconSearchTerms(search.text)
        val visible = ContourCatalog.entries.filter {
            (category.selectedIndex == 0 || it.category == category.selectedItem) && it.matches(terms)
        }
        filtering = true
        try {
            shapes.replaceItems(visible, visible.firstOrNull { it.id == shapeId })
        } finally {
            filtering = false
        }
        shapes.emptyText.text = "No icons found. Try another name or category."
        resultCount.text = "${visible.size} of ${ContourCatalog.entries.size} icons · Arrow keys to navigate"
        revealSelection(shapes)
    }

    private fun filterCustom() {
        val terms = iconSearchTerms(customSearch.text)
        val visible = gallery.filter { choice ->
            val label = choice.asset.label.lowercase(Locale.ROOT)
            terms.all { it in label }
        }
        filtering = true
        try {
            customGrid.replaceItems(visible, visible.firstOrNull { it.asset.digest == custom?.asset?.digest })
        } finally {
            filtering = false
        }
        customGrid.emptyText.text = if (gallery.isEmpty()) "No project SVGs yet. Import an SVG to get started."
            else "No SVGs found. Try another name."
        customCount.text = "${visible.size} project SVG${if (visible.size == 1) "" else "s"} · Original colors"
        revealSelection(customGrid)
    }

    private fun revealSelection(grid: IconChoiceGrid<*>) {
        // До layout горизонтальная сетка считает себя одним столбцом: выбранная
        // ячейка получает неверную Y-координату и уводит viewport в конец каталога.
        SwingUtilities.invokeLater {
            if (!closed && grid.isShowing && grid.model.size > 0) {
                grid.ensureIndexIsVisible(grid.selectedIndex.coerceAtLeast(0))
                // После ensureIndexIsVisible верх мог начинаться посреди ячейки.
                // Поднимаем начало следующего полного ряда, сохраняя выбранный видимым.
                val viewport = grid.parent as? JViewport
                if (viewport != null) {
                    val cellHeight = grid.fixedCellHeight
                    val y = ((viewport.viewPosition.y + cellHeight - 1) / cellHeight) * cellHeight
                    val maxY = (grid.height - viewport.extentSize.height).coerceAtLeast(0)
                    viewport.viewPosition = Point(0, y.coerceAtMost(maxY))
                }
            }
        }
    }

    private fun importSvg() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("svg")
        val file = FileChooser.chooseFile(descriptor, body, project, null) ?: return
        val path = file.toNioPath()
        val token = ++importGeneration
        worker?.cancel(true)
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Loading SVG…"
        importButton.isEnabled = false
        setOKActionEnabled(false)
        worker = ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val asset = SvgAsset.read(path, "Imported · ${path.fileName}")
                Result.success(IconChoice(asset, svgIconColor(asset)))
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            ApplicationManager.getApplication().invokeLater({
                if (closed || token != importGeneration) return@invokeLater
                importButton.isEnabled = true
                result.fold(onSuccess = { choice ->
                    gallery = (gallery + choice).distinctBy { it.asset.digest }
                    custom = choice
                    useShape = false
                    customSearch.text = ""
                    filterCustom()
                    tabs.selectedIndex = 1
                    status.foreground = UIUtil.getContextHelpForeground()
                    status.text = "Saved with Apply · Replaces light and dark icon variants"
                    showSelection()
                }, onFailure = { error ->
                    status.foreground = JBColor.RED
                    status.text = svgImportErrorMessage(error)
                    status.toolTipText = status.text
                    showSelection(clearImportError = false)
                })
            }, ModalityState.any())
        }
    }

    fun selection(): IconChoice? {
        if (!useShape) return custom
        val shape = ContourCatalog.entries.firstOrNull { it.id == shapeId } ?: return null
        val asset = SvgAsset(shape.svg(color), shape.title,
            BuilderSelection(shape.id, color.hex(), background = false))
        return IconChoice(asset, color)
    }

    private fun showSelection(clearImportError: Boolean = true) {
        if (clearImportError && importButton.isEnabled) {
            status.foreground = UIUtil.getContextHelpForeground()
            status.text = "Saved with Apply · Replaces light and dark icon variants"
            status.toolTipText = null
        }
        val selected = selection()
        preview.icon = selected?.let { RecentProjectIconHelper.createIcon(it.asset.tabArtwork() ?: it.asset.bytes, true, 32) }
        selectionLabel.text = selected?.asset?.label?.let { settingsLiteral(it.substringBefore(" · ")) } ?: "Choose an icon"
        selectionLabel.toolTipText = selected?.asset?.label?.let(::settingsLiteral)
        selectionDetail.text = when {
            selected == null -> "Select a result to preview it at tab sizes."
            useShape -> "Built-in icon · ${colorName(color)}"
            else -> selected.asset.label.substringAfter(" · ", "SVG") + " · Original colors"
        }
        samples.removeAll()
        if (selected != null) listOf(20, 24, 28).forEach { size ->
            samples.add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                preferredSize = JBUI.size(44, 56)
                add(JBLabel(RecentProjectIconHelper.createIcon(selected.asset.tabArtwork() ?: selected.asset.bytes, true, size)).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                }, BorderLayout.CENTER)
                add(settingsHint("$size px").apply { horizontalAlignment = SwingConstants.CENTER }, BorderLayout.SOUTH)
            })
        }
        samples.revalidate()
        samples.repaint()
        setOKActionEnabled(selected != null && importButton.isEnabled)
    }

    override fun doValidate(): ValidationInfo? = when {
        !importButton.isEnabled -> ValidationInfo("Wait for the imported SVG to load.")
        selection() != null -> null
        else -> ValidationInfo("Choose a built-in icon or a project SVG.", tabs)
    }

    override fun createCenterPanel(): JComponent = body
    override fun getPreferredFocusedComponent(): JComponent =
        if (useShape) search.textEditor else customSearch.textEditor
    override fun getDimensionServiceKey(): String = "ProjectTabIcons.IconSelection.Refined"
}

package dev.vlas.projecttabicons

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

/** Выбор иконки и импорт библиотеки остаются черновиком до Apply страницы Settings. */
internal class IconSelectionDialog(
    parent: JComponent,
    private val project: Project,
    projectName: String,
    private val color: Color,
    initial: IconChoice?,
    available: List<IconChoice>,
    sets: List<ImportedIconSet>,
    private val immediate: Boolean = false,
) : DialogWrapper(parent, true) {
    private val saveHint = if (immediate) "Applies immediately · Replaces light and dark icon variants" else "Saved with Apply · Replaces light and dark icon variants"
    private val body = JPanel(BorderLayout(0, JBUI.scale(16)))
    private val preview = IconPreviewWell()
    private val selectionLabel = JBLabel("Choose an icon").apply { font = font.deriveFont(Font.BOLD) }
    private val selectionDetail = settingsHint("Preview at actual tab sizes")
    private val samples = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0))
    private val status = JTextArea(saveHint, 3, 0).apply {
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
    private val category = ComboBox<LibraryCategory>()
    private val resultCount = settingsHint("")
    private val customCount = settingsHint("")
    private val shapeIcons = mutableMapOf<String, Icon>()
    private val customIcons = mutableMapOf<String, Icon>()
    private val shapes = IconChoiceGrid<LibraryIcon>({ it.title }) { item ->
        shapeIcons.getOrPut(item.id) {
            projectSvgIcon(item.choice(color).asset, 28)
        }
    }
    private val customGrid = IconChoiceGrid<IconChoice>(
        { it.asset.label.substringBefore(" · ") }, { it.asset.label.substringAfter(" · ", "") },
    ) { choice ->
        customIcons.getOrPut(choice.asset.digest) { projectSvgIcon(choice.asset, 28) }
    }
    private val importButton = JButton("Import SVG…")
    private val importFolderButton = JButton("Import folder…")
    private var iconSets = sets
    private val addedSets = mutableListOf<ImportedIconSet>()
    private var library = libraryItems()
    private val textEditor = TextIconEditor(project, initial?.asset?.textIcon, color) { showSelection() }
    private var gallery = (listOfNotNull(initial) + available).distinctBy { it.asset.digest }
    private var custom = initial
    private var shapeId = initial?.asset?.builder?.contourId?.let { "builtin:$it" }
        ?: library.firstOrNull { it.imported?.asset?.digest == initial?.asset?.digest }?.id
    private var filtering = false
    private val importTask = LatestTask()
    private var closed = false
    private var busy = false

    private fun libraryItems(): List<LibraryIcon> = ContourCatalog.entries.map {
        LibraryIcon("builtin:${it.id}", it.title, "builtin:${it.category}", it)
    } + iconSets.flatMap { set -> set.icons.map { choice ->
        LibraryIcon("${set.id}:${choice.asset.digest}", choice.asset.label.substringBeforeLast(" · "), set.id, imported = choice)
    } }

    private fun rebuildCategories(selectedKey: String? = null) {
        val values = listOf(LibraryCategory(null, "All categories")) +
            ContourCatalog.entries.map { it.category }.distinct().sorted().map { LibraryCategory("builtin:$it", it) } +
            iconSets.map { LibraryCategory(it.id, "★ ${it.name}") }
        category.removeAllItems()
        values.forEach(category::addItem)
        category.selectedItem = values.firstOrNull { it.key == selectedKey } ?: values.first()
        category.maximumSize = JBUI.size(220, 40)
        category.preferredSize = Dimension(JBUI.scale(190), category.preferredSize.height)
    }

    fun importedSets(): List<ImportedIconSet> = addedSets.toList()

    private fun setLoading(value: Boolean) {
        busy = value
        importButton.isEnabled = !value
        importFolderButton.isEnabled = !value
        if (value) setOKActionEnabled(false)
    }

    init {
        title = "Choose icon · $projectName"
        setOKButtonText(if (immediate) "Apply icon" else "Choose icon")
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

        search.textEditor.emptyText.text = "Search icons…"
        search.textEditor.accessibleContext.accessibleName = "Search icon library"
        customSearch.textEditor.emptyText.text = "Search project SVGs"
        customSearch.textEditor.accessibleContext.accessibleName = "Search project SVGs"
        category.accessibleContext.accessibleName = "Icon category"
        shapes.accessibleContext.accessibleName = "Icon library"
        customGrid.accessibleContext.accessibleName = "Project SVGs"
        val toolbarHeight = maxOf(search.preferredSize.height, category.preferredSize.height,
            importButton.preferredSize.height, importFolderButton.preferredSize.height, JBUI.scale(30))
        listOf(search, customSearch, category, importButton, importFolderButton).forEach {
            it.preferredSize = Dimension(it.preferredSize.width, toolbarHeight)
        }
        customGrid.fixedCellWidth = JBUI.scale(162)
        customGrid.fixedCellHeight = JBUI.scale(100)
        val filterBar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(search, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(category, BorderLayout.CENTER); add(importFolderButton, BorderLayout.EAST)
            }, BorderLayout.EAST)
        }
        tabs.addTab("Icon library", catalogPanel(filterBar, shapes, resultCount))
        tabs.addTab("Project SVGs", catalogPanel(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(customSearch, BorderLayout.CENTER); add(importButton, BorderLayout.EAST)
        }, customGrid, customCount))
        tabs.addTab("Text icon", textEditor)
        body.add(tabs, BorderLayout.CENTER)
        body.add(status, BorderLayout.SOUTH)
        search.addDocumentListener(onTextChanged { filterShapes() })
        customSearch.addDocumentListener(onTextChanged { filterCustom() })
        category.addActionListener { filterShapes() }
        shapes.addListSelectionListener { event ->
            if (!filtering && !event.valueIsAdjusting) shapes.selectedValue?.let {
                shapeId = it.id
                showSelection()
            }
        }
        customGrid.addListSelectionListener { event ->
            if (!filtering && !event.valueIsAdjusting) customGrid.selectedValue?.let {
                custom = it
                showSelection()
            }
        }
        importButton.addActionListener { importSvg() }
        importFolderButton.addActionListener { importFolder() }
        rebuildCategories()
        filterShapes()
        filterCustom()
        tabs.selectedIndex = when {
            initial?.asset?.textIcon != null -> 2
            shapeId != null || initial == null -> 0
            else -> 1
        }
        tabs.addChangeListener {
            showSelection()
        }
        focusGridOnDown(search, shapes)
        focusGridOnDown(customSearch, customGrid)
        body.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "searchIcons")
        body.actionMap.put("searchIcons", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                when (tabs.selectedIndex) {
                    0 -> search.textEditor.requestFocusInWindow()
                    1 -> customSearch.textEditor.requestFocusInWindow()
                    else -> textEditor.textField.requestFocusInWindow()
                }
            }
        })
        init()
        showSelection()
        initValidation()
        Disposer.register(disposable, Disposable {
            closed = true
            importTask.cancel()
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
        val selectedCategory = category.selectedItem as? LibraryCategory
        val visible = library.filter {
            (selectedCategory?.key == null || it.categoryKey == selectedCategory.key) && it.matches(terms)
        }
        filtering = true
        try {
            shapes.replaceItems(visible, visible.firstOrNull { it.id == shapeId })
        } finally {
            filtering = false
        }
        shapes.emptyText.text = "No icons found. Try another name or category."
        resultCount.text = "${visible.size} of ${library.size} icons · Arrow keys to navigate"
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
        val token = importTask.begin()
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Loading SVG…"
        setLoading(true)
        importTask.attach(token, ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val asset = SvgAsset.read(path, "Imported · ${path.fileName}")
                Result.success(IconChoice(asset, svgIconColor(asset)))
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            ApplicationManager.getApplication().invokeLater({
                if (closed || !importTask.accepts(token)) return@invokeLater
                setLoading(false)
                result.fold(onSuccess = { choice ->
                    gallery = (gallery + choice).distinctBy { it.asset.digest }
                    custom = choice
                    customSearch.text = ""
                    filterCustom()
                    tabs.selectedIndex = 1
                    status.foreground = UIUtil.getContextHelpForeground()
                    status.text = saveHint
                    showSelection()
                }, onFailure = { error ->
                    status.foreground = JBColor.RED
                    status.text = svgImportErrorMessage(error)
                    status.toolTipText = status.text
                    showSelection(clearImportError = false)
                })
            }, ModalityState.any())
        })
    }

    private fun importFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "Import icon folder"
            description = "SVG files in this folder and its subfolders become a shared icon category."
            isForcedToUseIdeaFileChooser = true
        }
        val folder = FileChooser.chooseFile(descriptor, body, project, null)?.toNioPath() ?: return
        val token = importTask.begin()
        val names = iconSets.map { it.name }.toSet() + ContourCatalog.entries.map { it.category }
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Importing icon folder…"
        setLoading(true)
        importTask.attach(token, ApplicationManager.getApplication().executeOnPooledThread {
            val result = try { Result.success(importIconSet(folder, names)) }
            catch (error: ProcessCanceledException) { throw error }
            catch (error: Exception) { Result.failure(error) }
            ApplicationManager.getApplication().invokeLater({
                if (closed || !importTask.accepts(token)) return@invokeLater
                setLoading(false)
                result.fold(onSuccess = { imported ->
                    iconSets = iconSets + imported.set
                    addedSets += imported.set
                    library = libraryItems()
                    shapeId = "${imported.set.id}:${imported.set.icons.first().asset.digest}"
                    search.text = ""
                    rebuildCategories(imported.set.id)
                    filterShapes()
                    tabs.selectedIndex = 0
                    status.foreground = UIUtil.getContextHelpForeground()
                    status.text = "Imported ${imported.set.icons.size} icons into ${imported.set.name}. " +
                        "Skipped invalid files: ${imported.skipped.size}; duplicates: ${imported.duplicates}. " + if (immediate) "Saved with Apply icon." else "Saved with Apply."
                    status.toolTipText = imported.skipped.take(8).joinToString("\n").ifEmpty { null }
                    showSelection(clearImportError = false)
                }, onFailure = { error ->
                    status.foreground = JBColor.RED
                    status.text = when (error) {
                        is IllegalArgumentException -> settingsMessage(error.message ?: "Choose a folder containing SVG icons.")
                        else -> "The folder could not be read. Check its permissions and try again."
                    }
                    status.toolTipText = status.text
                    showSelection(clearImportError = false)
                })
            }, ModalityState.any())
        })
    }

    fun selection(): IconChoice? {
        return when (tabs.selectedIndex) {
            0 -> library.firstOrNull { it.id == shapeId }?.choice(color)
            1 -> custom
            else -> textEditor.selection()
        }
    }

    private fun showSelection(clearImportError: Boolean = true) {
        if (clearImportError && !busy) {
            status.foreground = UIUtil.getContextHelpForeground()
            status.text = saveHint
            status.toolTipText = null
        }
        val selected = selection()
        preview.icon = selected?.let { projectSvgIcon(it.asset, 32) }
        selectionLabel.text = selected?.asset?.label?.let { settingsLiteral(it.substringBefore(" · ")) } ?: "Choose an icon"
        selectionLabel.toolTipText = selected?.asset?.label?.let(::settingsLiteral)
        selectionDetail.text = when {
            selected == null -> "Select a result to preview it at tab sizes."
            selected.asset.textIcon != null -> "${selected.asset.textIcon.font.title} · ${selected.asset.textIcon.style.title} · ${selected.color?.hex()}"
            tabs.selectedIndex == 0 && selected.asset.builder != null -> "Built-in icon · ${colorName(color)}"
            else -> selected.asset.label.substringAfter(" · ", "SVG") + " · Original colors"
        }
        samples.removeAll()
        if (selected != null) listOf(20, 24, 28).forEach { size ->
            samples.add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                preferredSize = JBUI.size(44, 56)
                add(JBLabel(projectSvgIcon(selected.asset, size)).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                }, BorderLayout.CENTER)
                add(settingsHint("$size px").apply { horizontalAlignment = SwingConstants.CENTER }, BorderLayout.SOUTH)
            })
        }
        samples.revalidate()
        samples.repaint()
        setOKActionEnabled(selected != null && !busy)
    }

    override fun doValidate(): ValidationInfo? = when {
        busy -> ValidationInfo("Wait for the icons to load.")
        tabs.selectedIndex == 2 && textEditor.validationError != null -> ValidationInfo(textEditor.validationError!!, textEditor.textField)
        selection() != null -> null
        else -> ValidationInfo("Choose an icon or enter a text icon.", tabs)
    }

    override fun createCenterPanel(): JComponent = body
    override fun getPreferredFocusedComponent(): JComponent = when (tabs.selectedIndex) {
        0 -> search.textEditor
        1 -> customSearch.textEditor
        else -> textEditor.textField
    }
    override fun getDimensionServiceKey(): String = "ProjectTabIcons.IconSelection.Refined"
}

private data class LibraryCategory(val key: String?, val title: String) {
    override fun toString(): String = title
}

private data class LibraryIcon(val id: String, val title: String, val categoryKey: String,
                               val contour: Contour? = null, val imported: IconChoice? = null) {
    private val searchText = (title + " " + imported?.asset?.label.orEmpty()).lowercase(Locale.ROOT)
    fun choice(color: Color): IconChoice = imported ?: requireNotNull(contour).let {
        IconChoice(SvgAsset(it.svg(color), it.title, BuilderSelection(it.id, color.hex(), background = false)), color)
    }
    fun matches(terms: List<String>): Boolean = contour?.matches(terms) ?: terms.all { it in searchText }
}

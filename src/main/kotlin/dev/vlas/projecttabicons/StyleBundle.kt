package dev.vlas.projecttabicons

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

internal data class StyleBundle(val presets: List<UserAppearancePreset>, val categories: List<ImportedIconSet>) {
    val empty get() = presets.isEmpty() && categories.isEmpty()
}
internal enum class StyleConflict(val title: String) {
    KEEP_BOTH("Keep both"), REPLACE("Replace matching names"), SKIP("Skip matching names");
    override fun toString() = title
}
private const val MAX_BUNDLE_BYTES = 24 * 1024 * 1024

/** Переносимый формат не содержит ID проектов, путей или назначений иконок. */
internal fun writeStyleBundle(path: Path, bundle: StyleBundle) {
    val json = JsonObject().apply {
        addProperty("format", "project-tab-icons"); addProperty("version", 1)
        add("presets", JsonArray().apply { bundle.presets.forEach { preset -> add(JsonObject().apply {
            addProperty("name", preset.name); add("appearance", encodeAppearance(preset.appearance))
        }) } })
        add("categories", JsonArray().apply { bundle.categories.forEach { category -> add(JsonObject().apply {
            addProperty("name", category.name)
            add("icons", JsonArray().apply { category.icons.forEach { icon -> add(JsonObject().apply {
                addProperty("name", icon.asset.label.substringBeforeLast(" · ").take(128))
                addProperty("svg", Base64.getEncoder().encodeToString(icon.asset.bytes))
            }) } })
        }) } })
    }
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_BUNDLE_BYTES) { "This style bundle is too large (24 MiB maximum)." }
    val destination = path.toAbsolutePath()
    val temporary = Files.createTempFile(destination.parent, ".project-tab-styles-", ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

internal fun readStyleBundle(path: Path): StyleBundle {
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BUNDLE_BYTES + 1) }
    require(bytes.size <= MAX_BUNDLE_BYTES) { "Choose a style bundle smaller than 24 MiB." }
    val source = bytes.toString(Charsets.UTF_8)
    // Ограничиваем вложенность до парсинга; скобки внутри JSON-строк пропускаются.
    var depth = 0; var quoted = false; var escaped = false
    source.forEach { char ->
        if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
        else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 12) { "The style bundle is nested too deeply." } }; '}', ']' -> depth-- }
    }
    val root = JsonParser.parseString(source).objectValue()
    require(root.string("format") == "project-tab-icons" && root.integer("version", 0) == 1) { "Unsupported Project Tab Icons style bundle." }
    val presetsJson = root.array("presets")
    require(presetsJson.size() <= MAX_USER_APPEARANCE_PRESETS) { "A bundle can contain up to $MAX_USER_APPEARANCE_PRESETS presets." }
    val presets = presetsJson.map { item ->
        val value = item.objectValue()
        val name = value.name(32)
        require(userPresetNameError(name, emptyList()) == null) { "Invalid preset name: $name" }
        UserAppearancePreset(UUID.randomUUID().toString(), name, decodeAppearance(value.getAsJsonObject("appearance")))
    }
    val categoriesJson = root.array("categories")
    require(categoriesJson.size() <= MAX_LIBRARY_SETS) { "A bundle can contain up to $MAX_LIBRARY_SETS categories." }
    var total = 0L
    val categories = categoriesJson.map { item ->
        val value = item.objectValue(); val name = value.name(64); val icons = value.array("icons")
        require(icons.size() in 1..MAX_SET_ICONS) { "A category must contain 1–$MAX_SET_ICONS icons." }
        ImportedIconSet(UUID.randomUUID().toString(), name, icons.map { element ->
            val icon = element.objectValue(); val label = icon.name(128); val svg = icon.string("svg")
            require(svg.length <= MAX_ICON_BYTES * 4 / 3 + 8) { "An SVG in this bundle is too large." }
            val raw = Base64.getDecoder().decode(svg)
            total += raw.size
            require(total <= MAX_LIBRARY_BYTES) { "The SVG library in this bundle exceeds 16 MiB." }
            val asset = SvgAsset.validate(raw, "$label · $name")
            IconChoice(asset, svgIconColor(asset))
        })
    }
    return StyleBundle(presets, categories).also { require(!it.empty) { "This style bundle is empty." } }
}

internal fun mergeStyleBundle(current: StyleBundle, incoming: StyleBundle, conflict: StyleConflict): StyleBundle {
    val presets = current.presets.toMutableList()
    incoming.presets.forEach { preset ->
        val index = presets.indexOfFirst { it.name.equals(preset.name, true) }
        when {
            index < 0 -> presets += preset
            conflict == StyleConflict.REPLACE -> presets[index] = preset.copy(id = presets[index].id)
            conflict == StyleConflict.KEEP_BOTH -> presets += preset.copy(name = uniqueStyleName(preset.name, presets.map { it.name }, 32))
        }
    }
    require(presets.size <= MAX_USER_APPEARANCE_PRESETS) { "Import would exceed $MAX_USER_APPEARANCE_PRESETS user presets. Select fewer presets or replace matching names." }
    val sets = current.categories.toMutableList()
    incoming.categories.forEach { category ->
        val index = sets.indexOfFirst { it.name.equals(category.name, true) }
        when {
            index < 0 -> sets += category
            conflict == StyleConflict.REPLACE -> sets[index] = category.copy(id = sets[index].id)
            conflict == StyleConflict.KEEP_BOTH -> sets += category.copy(name = uniqueStyleName(category.name, sets.map { it.name }, 64))
        }
    }
    require(sets.size <= MAX_LIBRARY_SETS) { "Import would exceed $MAX_LIBRARY_SETS icon categories." }
    return StyleBundle(presets, sets)
}
private fun uniqueStyleName(base: String, names: List<String>, max: Int): String {
    var suffix = 2
    while (true) {
        val ending = " ($suffix)"
        val points = base.codePoints().toArray().take(max - ending.length).toIntArray()
        val name = String(points, 0, points.size) + ending
        if (names.none { it.equals(name, true) } && (max != 32 || userPresetNameError(name, emptyList()) == null)) return name
        suffix++
    }
}
private fun JsonElement.objectValue(): JsonObject { require(isJsonObject) { "Expected a JSON object." }; return asJsonObject }
private fun JsonObject.array(key: String): JsonArray = get(key)?.also { require(it.isJsonArray) { "Expected $key to be a list." } }?.asJsonArray ?: JsonArray()
private fun JsonObject.string(key: String): String = get(key)?.also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid $key." } }?.asString ?: error("Missing $key.")
private fun JsonObject.name(max: Int): String = string("name").also { require(it.isNotBlank() && it.codePointCount(0, it.length) <= max && it.none(Char::isISOControl)) { "Invalid style name." } }
private fun JsonObject.bool(key: String, default: Boolean): Boolean = get(key)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Invalid $key." }; it.asBoolean } ?: default
private fun JsonObject.integer(key: String, default: Int): Int = get(key)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Invalid $key." }; it.asString.toIntOrNull() ?: error("Invalid $key.") } ?: default
private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T = if (!has(key)) default else enumValues<T>().firstOrNull { it.name == string(key) } ?: error("Unknown $key.")

private fun encodeAppearance(value: TabAppearanceState): JsonObject = JsonObject().apply {
    addProperty("showIcons", value.showIcons)
    addProperty("showOutline", value.showOutline)
    addProperty("iconSize", value.iconSize.name)
    addProperty("iconPosition", value.iconPosition.name)
    addProperty("outlineThickness", value.outlineThickness)
    addProperty("outlineStyle", value.outlineStyle.name)
    addProperty("closeButtonPosition", value.closeButtonPosition.name)
    addProperty("tabHeight", value.tabHeight)
    addProperty("showProjectPath", value.showProjectPath)
    addProperty("showGitBranch", value.showGitBranch)
    addProperty("gitBranchFormat", value.gitBranchFormat.name)
    addProperty("showGitStatus", value.showGitStatus)
    addProperty("branchPosition", value.branchPosition.name)
    addProperty("showTabBackground", value.showTabBackground)
    addProperty("tabFontSize", value.tabFontSize)
    addProperty("tabFillInset", value.tabFillInset)
    addProperty("tabFillIntensity", value.tabFillIntensity)
    addProperty("iconInitials", value.iconInitials.name)
    addProperty("iconLetterWeight", value.iconLetterWeight.name)
}
private fun decodeAppearance(value: JsonObject): TabAppearanceState {
    val defaults = TabAppearanceState.defaults()
    return defaults.copy(
        showIcons = value.bool("showIcons", defaults.showIcons),
        showOutline = value.bool("showOutline", defaults.showOutline),
        iconSize = value.enum("iconSize", defaults.iconSize),
        iconPosition = value.enum("iconPosition", defaults.iconPosition),
        outlineThickness = value.integer("outlineThickness", defaults.outlineThickness),
        outlineStyle = value.enum("outlineStyle", defaults.outlineStyle),
        closeButtonPosition = value.enum("closeButtonPosition", defaults.closeButtonPosition),
        tabHeight = value.integer("tabHeight", defaults.tabHeight),
        showProjectPath = value.bool("showProjectPath", defaults.showProjectPath),
        showGitBranch = value.bool("showGitBranch", defaults.showGitBranch),
        gitBranchFormat = value.enum("gitBranchFormat", defaults.gitBranchFormat),
        showGitStatus = value.bool("showGitStatus", defaults.showGitStatus),
        branchPosition = value.enum("branchPosition", defaults.branchPosition),
        showTabBackground = value.bool("showTabBackground", defaults.showTabBackground),
        tabFontSize = value.integer("tabFontSize", defaults.tabFontSize),
        tabFillInset = value.integer("tabFillInset", defaults.tabFillInset),
        tabFillIntensity = value.integer("tabFillIntensity", defaults.tabFillIntensity),
        iconInitials = value.enum("iconInitials", defaults.iconInitials),
        iconLetterWeight = value.enum("iconLetterWeight", defaults.iconLetterWeight),
    ).normalized().asCustomAppearance()
}

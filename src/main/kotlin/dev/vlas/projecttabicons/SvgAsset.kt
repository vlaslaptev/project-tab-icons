package dev.vlas.projecttabicons

import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal const val MAX_ICON_BYTES = 2 * 1024 * 1024
internal val PROJECT_ICON_FILES = listOf("icon.svg", "icon.png", "icon_dark.svg", "icon_dark.png")

internal data class BuilderSelection(val contourId: String, val colorHex: String, val background: Boolean)

internal data class SvgAsset(val bytes: ByteArray, val label: String, val builder: BuilderSelection? = null) {
    val digest: String = sha256(bytes)

    /** Убирает только старую подложку конструктора из отображения, не меняя сохранённый SVG. */
    fun tabArtwork(): ByteArray? {
        if (builder?.background != true) return null
        val document = parseSvgDocument(bytes)
        val root = document.documentElement
        val allowed = setOf("width", "height", "rx", "fill", "fill-opacity", "stroke")
        val backdrop = (0 until root.childNodes.length).asSequence().map { root.childNodes.item(it) }
            .filterIsInstance<Element>().firstOrNull { element ->
                element.localName == "rect" &&
                    (0 until element.attributes.length).all { element.attributes.item(it).nodeName in allowed } &&
                    element.getAttribute("width").toDoubleOrNull() == 24.0 &&
                    element.getAttribute("height").toDoubleOrNull() == 24.0 &&
                    element.getAttribute("rx").toDoubleOrNull() == 4.5 &&
                    element.getAttribute("fill-opacity").toDoubleOrNull() == 0.5 &&
                    element.getAttribute("stroke") == "none" &&
                    element.getAttribute("fill").uppercase() in setOf("#12161E", "#F1F5F9")
            } ?: return null
        root.removeChild(backdrop)
        root.setAttribute("data-project-tab-icons-background", "none")
        val factory = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }
        val output = ByteArrayOutputStream()
        factory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }

    companion object {
        private val URL_PATTERN = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

        fun read(path: Path, label: String): SvgAsset = validate(readIconBytes(path), label)

        fun validate(bytes: ByteArray, label: String): SvgAsset {
            if (bytes.size > MAX_ICON_BYTES) throw SvgValidationException(SvgImportProblem.TOO_LARGE)
            if (bytes.isEmpty()) throw SvgValidationException(SvgImportProblem.EMPTY)
            val document = parseSvgDocument(bytes)
            val root = document.documentElement
            if (root.localName != "svg" || root.namespaceURI != "http://www.w3.org/2000/svg")
                throw SvgValidationException(SvgImportProblem.NOT_SVG)
            val elements = root.getElementsByTagName("*")
            validateElement(root)
            for (index in 0 until elements.length) validateElement(elements.item(index) as Element)
            val id = root.getAttribute("data-project-tab-icons-contour")
            val color = parseIconColor(root.getAttribute("data-project-tab-icons-color"))
            val builder = if (color != null && ContourCatalog.entries.any { it.id == id }) {
                BuilderSelection(id, color.hex(), root.getAttribute("data-project-tab-icons-background") == "auto")
            } else null
            return SvgAsset(bytes.copyOf(), label, builder)
        }

        private fun validateElement(element: Element) {
            if (element.localName in setOf("script", "foreignObject"))
                throw SvgValidationException(SvgImportProblem.ACTIVE_CONTENT)
            for (index in 0 until element.attributes.length) {
                val attribute = element.attributes.item(index)
                if (attribute.localName == "href") {
                    val value = attribute.nodeValue.trim()
                    if (!value.startsWith('#') && !value.startsWith("data:image/"))
                        throw SvgValidationException(SvgImportProblem.EXTERNAL_RESOURCES)
                }
                checkUrls(attribute.nodeValue)
            }
            if (element.localName == "style") checkUrls(element.textContent)
        }

        private fun checkUrls(value: String) {
            URL_PATTERN.findAll(value).forEach {
                if (!it.groupValues[1].trim().trim('\'', '"').startsWith('#'))
                    throw SvgValidationException(SvgImportProblem.EXTERNAL_RESOURCES)
            }
            if (value.contains("@import", ignoreCase = true))
                throw SvgValidationException(SvgImportProblem.EXTERNAL_RESOURCES)
        }
    }
}

private fun parseSvgDocument(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    isXIncludeAware = false
    isExpandEntityReferences = false
}.newDocumentBuilder().apply {
    setErrorHandler(object : DefaultHandler() {
        override fun error(error: SAXParseException) { throw error }
        override fun fatalError(error: SAXParseException) { throw error }
    })
}.let { parser -> ByteArrayInputStream(bytes).use(parser::parse) }

internal fun readIconBytes(path: Path): ByteArray = Files.newInputStream(path).use { input ->
    input.readNBytes(MAX_ICON_BYTES + 1).also {
        if (it.size > MAX_ICON_BYTES) throw SvgValidationException(SvgImportProblem.TOO_LARGE)
    }
}

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

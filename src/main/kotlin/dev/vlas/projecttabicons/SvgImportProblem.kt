package dev.vlas.projecttabicons

import org.xml.sax.SAXParseException
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

internal enum class SvgImportProblem(val explanation: String) {
    TOO_LARGE("The icon is larger than 2 MiB. Export a simpler SVG or reduce embedded image sizes."),
    EMPTY("The SVG file is empty. Export the artwork again and choose the new file."),
    NOT_SVG("This file is not an SVG image. Export it as SVG with the standard SVG namespace and try again."),
    ACTIVE_CONTENT("This SVG contains scripts or embedded web content. Export a static SVG without scripts or foreignObject elements."),
    EXTERNAL_RESOURCES("This SVG references external resources. Embed its images and styles, or convert them to paths before exporting."),
    DOCTYPE("This SVG uses a document type declaration. Export a self-contained SVG without a DOCTYPE declaration."),
}

internal class SvgValidationException(val problem: SvgImportProblem) : IllegalArgumentException(problem.explanation)

/** Не выводим системные пути и сырые XML-ошибки вместо объяснения и следующего действия. */
internal fun svgImportErrorMessage(error: Throwable): String = when (error) {
    is SvgValidationException -> error.problem.explanation
    is SAXParseException -> if (error.message.orEmpty().contains("DOCTYPE", ignoreCase = true)) {
        SvgImportProblem.DOCTYPE.explanation
    } else {
        val location = if (error.lineNumber > 0) " at line ${error.lineNumber}, column ${error.columnNumber.coerceAtLeast(1)}" else ""
        "The SVG has invalid XML$location. Open it in an SVG editor, fix or re-export it, then try again."
    }
    is NoSuchFileException, is FileNotFoundException ->
        "The selected file is no longer available. Check that it still exists, then choose it again."
    is AccessDeniedException, is SecurityException ->
        "The selected file cannot be accessed. Check its read permissions or copy it to a readable folder, then try again."
    is IOException ->
        "The SVG could not be read. Check the file and its read permissions, then choose it again."
    else -> "The SVG could not be imported. Re-export it as a static, self-contained SVG and try again."
}

package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import io.shipbook.shipbooksdk.ShipBook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val templateLog = ShipBook.getLogger("ObsidianTemplate")

object ObsidianTemplateEngine {

    fun renderMarkdown(
        context: Context,
        templateUriString: String,
        title: String,
        created: Date,
        modified: Date,
        content: String,
        pdfPath: String? = null,
        fallback: () -> String
    ): String {
        if (templateUriString.isBlank()) return fallback()

        val template = try {
            val uri = Uri.parse(templateUriString)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            templateLog.e("Failed to read Obsidian template: ${e.message}")
            null
        }

        if (template.isNullOrBlank()) return fallback()

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val createdValue = isoFormatter.format(created)
        val modifiedValue = isoFormatter.format(modified)

        var rendered = template
        rendered = rendered.replace(Regex("(?m)^(\\s*created\\s*:\\s*).*$"), "$1$createdValue")
        rendered = rendered.replace(Regex("(?m)^(\\s*modified\\s*:\\s*).*$"), "$1$modifiedValue")

        rendered = rendered.replace("{{title}}", title)

        val hasContentPlaceholder = rendered.contains("{{content}}")
        rendered = rendered.replace("{{content}}", content.trim())

        val pdfEmbed = pdfPath?.let { "![]($it)" }.orEmpty()
        val hasPdfPlaceholder = rendered.contains("{{pdf}}")
        rendered = rendered.replace("{{pdf}}", pdfEmbed)

        if (!hasContentPlaceholder) {
            val appendBlock = buildString {
                if (pdfEmbed.isNotEmpty() && !hasPdfPlaceholder) {
                    appendLine(pdfEmbed)
                    appendLine()
                }
                append(content.trim())
            }.trimEnd()
            rendered = rendered.trimEnd() + "\n\n" + appendBlock
        }

        return rendered
    }
}

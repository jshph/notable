package com.ethran.notable.io

import android.content.Context
import android.graphics.RectF
import android.os.Environment
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Annotation
import com.ethran.notable.data.db.AnnotationType
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.datastore.GlobalAppSettings
import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = ShipBook.getLogger("InboxSyncEngine")

object InboxSyncEngine {

    /**
     * Sync an inbox page to Obsidian. Tags come from the UI (pill selection),
     * content is recognized from all strokes on the page via Onyx HWR (MyScript).
     * Annotation boxes mark regions to wrap in [[wiki links]] or #tags.
     */
    suspend fun syncInboxPage(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>,
        context: Context
    ) {
        log.i("Starting inbox sync for page $pageId with tags: $tags")

        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(pageId)
        val page = pageWithStrokes.page
        val allStrokes = pageWithStrokes.strokes
        val annotations = appRepository.annotationRepository.getByPageId(pageId)

        if (allStrokes.isEmpty() && tags.isEmpty()) {
            log.i("No strokes and no tags on inbox page, skipping sync")
            return
        }

        val serviceReady = try {
            OnyxHWREngine.bindAndAwait(context)
        } catch (e: Exception) {
            log.e("OnyxHWR bind failed: ${e.message}")
            false
        }

        // 1. Recognize ALL strokes together to preserve natural text flow
        var fullText = if (serviceReady && allStrokes.isNotEmpty()) {
            log.i("Recognizing all ${allStrokes.size} strokes")
            val result = recognizeStrokesSafe(allStrokes)
            postProcessRecognition(result)
        } else ""

        log.i("Full recognized text: '${fullText.take(200)}'")

        // 2. For each annotation, recognize its strokes separately to get the annotation text,
        //    then find and replace that text inline with the wrapped version
        if (serviceReady && annotations.isNotEmpty()) {
            for (annotation in annotations) {
                val annotRect = RectF(
                    annotation.x, annotation.y,
                    annotation.x + annotation.width,
                    annotation.y + annotation.height
                )
                val overlapping = findStrokesInRect(allStrokes, annotRect)
                if (overlapping.isNotEmpty()) {
                    val annotText = recognizeStrokesSafe(overlapping).trim()
                    if (annotText.isNotBlank()) {
                        log.i("Annotation ${annotation.type}: '$annotText'")
                        val wrapped = when (annotation.type) {
                            AnnotationType.WIKILINK.name -> "[[${annotText}]]"
                            AnnotationType.TAG.name -> "#${annotText.replace(" ", "-")}"
                            else -> annotText
                        }
                        // Replace the annotation text inline in the full recognized text
                        fullText = fullText.replaceFirst(annotText, wrapped)
                    }
                }
            }
        }

        val finalContent = fullText

        val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)
        val markdown = generateMarkdown(createdDate, tags, finalContent)

        val inboxPath = GlobalAppSettings.current.obsidianInboxPath
        writeMarkdownFile(markdown, page.createdAt, inboxPath)

        log.i("Inbox sync complete for page $pageId")
    }

    private suspend fun recognizeStrokesSafe(strokes: List<Stroke>): String {
        return try {
            OnyxHWREngine.recognizeStrokes(strokes, viewWidth = 1404f, viewHeight = 1872f) ?: ""
        } catch (e: Exception) {
            log.e("OnyxHWR failed: ${e.message}")
            ""
        }
    }

    private fun findStrokesInRect(strokes: List<Stroke>, rect: RectF): List<Stroke> {
        return strokes.filter { stroke ->
            val strokeRect = RectF(stroke.left, stroke.top, stroke.right, stroke.bottom)
            RectF.intersects(strokeRect, rect)
        }
    }


    /**
     * Post-process recognition output:
     * - Normalize any bracket/paren wrapping to [[wiki links]]
     * - Collapse space between # and the following word into a proper #tag
     */
    private fun postProcessRecognition(text: String): String {
        var result = text

        // Normalize bracket/paren wrapping to [[wiki links]]
        result = result.replace(Regex("""[(\[]{1,2}([^)\]\n]+?)[)\]]{1,2}""")) { match ->
            "[[${match.groupValues[1].trim()}]]"
        }

        // Collapse space between # and the word following it
        result = result.replace(Regex("""#\s+(\w+)""")) { match ->
            "#${match.groupValues[1]}"
        }

        return result
    }

    private fun generateMarkdown(
        createdDate: String,
        tags: List<String>,
        content: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("created: \"[[$createdDate]]\"")
        if (tags.isNotEmpty()) {
            sb.appendLine("tags:")
            tags.forEach { sb.appendLine("  - $it") }
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(content.trim())
        return sb.toString()
    }

    private fun writeMarkdownFile(markdown: String, createdAt: Date, inboxPath: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(createdAt)
        val fileName = "$timestamp.md"

        val dir = if (inboxPath.startsWith("/")) {
            File(inboxPath)
        } else {
            File(Environment.getExternalStorageDirectory(), inboxPath)
        }

        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(markdown)
        log.i("Written inbox note to ${file.absolutePath}")
    }
}

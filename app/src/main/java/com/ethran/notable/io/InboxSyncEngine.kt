package com.ethran.notable.io

import android.content.Context
import android.os.Environment
import com.ethran.notable.data.AppRepository
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
        val strokes = pageWithStrokes.strokes

        if (strokes.isEmpty() && tags.isEmpty()) {
            log.i("No strokes and no tags on inbox page, skipping sync")
            return
        }

        val contentText = if (strokes.isNotEmpty()) {
            log.i("Recognizing ${strokes.size} content strokes")
            try {
                val serviceReady = OnyxHWREngine.bindAndAwait(context)
                if (serviceReady) {
                    val result = OnyxHWREngine.recognizeStrokes(
                        strokes,
                        viewWidth = 1404f,
                        viewHeight = 1872f
                    )
                    if (result != null) {
                        log.i("OnyxHWR succeeded, ${result.length} chars")
                        postProcessRecognition(result)
                    } else {
                        log.w("OnyxHWR returned null")
                        ""
                    }
                } else {
                    log.w("OnyxHWR service not available")
                    ""
                }
            } catch (e: Exception) {
                log.e("OnyxHWR failed: ${e.message}")
                ""
            }
        } else ""

        log.i("Recognized content: '${contentText.take(100)}'")

        val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)
        val markdown = generateMarkdown(createdDate, tags, contentText)

        val inboxPath = GlobalAppSettings.current.obsidianInboxPath
        writeMarkdownFile(markdown, page.createdAt, inboxPath)

        log.i("Inbox sync complete for page $pageId")
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

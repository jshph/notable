package com.ethran.notable.io

import android.os.Environment
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Stroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val log = ShipBook.getLogger("InboxSyncEngine")

object InboxSyncEngine {

    private val modelIdentifier =
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    private val model =
        modelIdentifier?.let { DigitalInkRecognitionModel.builder(it).build() }
    private val recognizer = model?.let {
        DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(it).build()
        )
    }

    /**
     * Sync an inbox page to Obsidian. Tags come from the UI (pill selection),
     * content is recognized from all strokes on the page.
     */
    suspend fun syncInboxPage(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>
    ) {
        log.i("Starting inbox sync for page $pageId with tags: $tags")

        val page = appRepository.pageRepository.getById(pageId)
        if (page == null) {
            log.e("Page $pageId not found")
            return
        }

        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(pageId)
        val strokes = pageWithStrokes.strokes

        if (strokes.isEmpty() && tags.isEmpty()) {
            log.i("No strokes and no tags on inbox page, skipping sync")
            return
        }

        val contentText = if (strokes.isNotEmpty()) {
            ensureModelDownloaded()
            log.i("Recognizing ${strokes.size} content strokes")
            val raw = recognizeStrokes(strokes)
            postProcessRecognition(raw)
        } else ""

        log.i("Recognized content: '${contentText.take(100)}'")

        val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)
        val markdown = generateMarkdown(createdDate, tags, contentText)

        val inboxPath = GlobalAppSettings.current.obsidianInboxPath
        writeMarkdownFile(markdown, page.createdAt, inboxPath)

        log.i("Inbox sync complete for page $pageId")
    }

    private suspend fun ensureModelDownloaded() {
        val m = model ?: throw IllegalStateException("ML Kit model identifier not found")
        val manager = RemoteModelManager.getInstance()

        val isDownloaded = suspendCancellableCoroutine<Boolean> { cont ->
            manager.isModelDownloaded(m)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        if (!isDownloaded) {
            log.i("Downloading ML Kit model...")
            suspendCancellableCoroutine<Void?> { cont ->
                manager.download(m, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(null) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            log.i("Model downloaded")
        }
    }

    /**
     * Segment strokes into lines based on vertical position, recognize each
     * line independently with WritingArea and pre-context, then join results.
     */
    private suspend fun recognizeStrokes(strokes: List<Stroke>): String {
        val rec = recognizer
            ?: throw IllegalStateException("ML Kit recognizer not initialized")

        val lines = segmentIntoLines(strokes)
        log.i("Segmented ${strokes.size} strokes into ${lines.size} lines")

        val recognizedLines = mutableListOf<String>()
        var preContext = ""

        for (lineStrokes in lines) {
            val ink = buildInk(lineStrokes)
            val writingArea = computeWritingArea(lineStrokes)
            val context = RecognitionContext.builder()
                .setPreContext(preContext)
                .setWritingArea(writingArea)
                .build()

            val text = suspendCancellableCoroutine<String> { cont ->
                rec.recognize(ink, context)
                    .addOnSuccessListener { result ->
                        cont.resume(result.candidates.firstOrNull()?.text ?: "")
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            if (text.isNotBlank()) {
                recognizedLines.add(text)
                // Use last ~20 chars as pre-context for the next line
                preContext = text.takeLast(20)
            }
        }

        return recognizedLines.joinToString("\n")
    }

    /**
     * Group strokes into horizontal lines by clustering on vertical midpoint.
     * Strokes whose vertical centers are within half a line-height of each
     * other belong to the same line.
     */
    private fun segmentIntoLines(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()

        // Sort by vertical midpoint, then left edge
        val sorted = strokes.sortedWith(
            compareBy<Stroke> { (it.top + it.bottom) / 2f }.thenBy { it.left }
        )

        // Estimate typical line height from median stroke height
        val strokeHeights = sorted.map { it.bottom - it.top }.filter { it > 0 }.sorted()
        val medianHeight = if (strokeHeights.isNotEmpty()) {
            strokeHeights[strokeHeights.size / 2]
        } else {
            50f // fallback
        }
        // Strokes within 0.75x median height of each other are on the same line
        val lineGapThreshold = medianHeight * 0.75f

        val lines = mutableListOf<MutableList<Stroke>>()
        var currentLine = mutableListOf(sorted.first())
        var currentLineCenter = (sorted.first().top + sorted.first().bottom) / 2f

        for (stroke in sorted.drop(1)) {
            val strokeCenter = (stroke.top + stroke.bottom) / 2f
            if (strokeCenter - currentLineCenter > lineGapThreshold) {
                // New line
                lines.add(currentLine)
                currentLine = mutableListOf(stroke)
                currentLineCenter = strokeCenter
            } else {
                currentLine.add(stroke)
                // Update running average of line center
                currentLineCenter = currentLine.map { (it.top + it.bottom) / 2f }.average().toFloat()
            }
        }
        lines.add(currentLine)

        // Sort strokes within each line left-to-right by creation time
        return lines.map { line ->
            line.sortedWith(compareBy<Stroke> { it.createdAt.time }.thenBy { it.left })
        }
    }

    private fun buildInk(strokes: List<Stroke>): Ink {
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            val baseTime = stroke.createdAt.time
            for ((i, point) in stroke.points.withIndex()) {
                val t = if (point.dt != null) {
                    baseTime + point.dt.toLong()
                } else {
                    baseTime + (i * 10L)
                }
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    /**
     * Compute a WritingArea from the bounding box of a set of strokes.
     * Uses the line's full width and height so ML Kit can judge relative
     * character sizes (e.g. uppercase vs lowercase).
     */
    private fun computeWritingArea(strokes: List<Stroke>): WritingArea {
        val minLeft = strokes.minOf { it.left }
        val maxRight = strokes.maxOf { it.right }
        val minTop = strokes.minOf { it.top }
        val maxBottom = strokes.maxOf { it.bottom }
        val width = (maxRight - minLeft).coerceAtLeast(1f)
        val height = (maxBottom - minTop).coerceAtLeast(1f)
        return WritingArea(width, height)
    }

    /**
     * Post-process ML Kit recognition output:
     * - Normalize any bracket/paren wrapping to [[wiki links]]
     * - Collapse space between # and the following word into a proper #tag
     */
    private fun postProcessRecognition(text: String): String {
        var result = text

        // Normalize bracket/paren wrapping to [[wiki links]]
        // Handles: [text], ((text)), ([text]), [(text)], [[text]], etc.
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

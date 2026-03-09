package com.ethran.notable.io

import android.os.Environment
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.drawing.INBOX_CONTENT_START_Y
import com.ethran.notable.editor.drawing.INBOX_DIVIDER_Y
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
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

    suspend fun syncInboxPage(appRepository: AppRepository, pageId: String) {
        log.i("Starting inbox sync for page $pageId")

        val page = appRepository.pageRepository.getById(pageId)
        if (page == null) {
            log.e("Page $pageId not found")
            return
        }

        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(pageId)
        val strokes = pageWithStrokes.strokes

        if (strokes.isEmpty()) {
            log.i("No strokes on inbox page, deleting")
            appRepository.pageRepository.delete(pageId)
            return
        }

        ensureModelDownloaded()

        // Classify strokes by zone using bounding box position
        val tagStrokes = strokes.filter { it.top < INBOX_DIVIDER_Y }
        val contentStrokes = strokes.filter { it.top >= INBOX_CONTENT_START_Y }

        log.i("Classified ${tagStrokes.size} tag strokes, ${contentStrokes.size} content strokes")

        val tagText = if (tagStrokes.isNotEmpty()) recognizeStrokes(tagStrokes) else ""
        val contentText = if (contentStrokes.isNotEmpty()) recognizeStrokes(contentStrokes) else ""

        log.i("Recognized tags: '$tagText', content: '${contentText.take(100)}'")

        val tags = parseTags(tagText)
        val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)
        val markdown = generateMarkdown(createdDate, tags, contentText)

        val inboxPath = GlobalAppSettings.current.obsidianInboxPath
        writeMarkdownFile(markdown, page.createdAt, inboxPath)

        appRepository.pageRepository.delete(pageId)
        log.i("Inbox sync complete, page $pageId deleted")
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

    private suspend fun recognizeStrokes(strokes: List<Stroke>): String {
        val rec = recognizer
            ?: throw IllegalStateException("ML Kit recognizer not initialized")

        val inkBuilder = Ink.builder()

        // Sort strokes by creation time, then by position for deterministic order
        val sortedStrokes = strokes.sortedWith(
            compareBy<Stroke> { it.createdAt.time }.thenBy { it.top }.thenBy { it.left }
        )

        // Use actual stroke creation timestamps as base, with synthetic
        // per-point timing (~10ms between points, simulating natural writing)
        for (stroke in sortedStrokes) {
            val strokeBuilder = Ink.Stroke.builder()
            val baseTime = stroke.createdAt.time
            for ((i, point) in stroke.points.withIndex()) {
                val t = if (point.dt != null) {
                    baseTime + point.dt.toLong()
                } else {
                    baseTime + (i * 10L) // 10ms between points
                }
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        val ink = inkBuilder.build()
        return suspendCancellableCoroutine { cont ->
            rec.recognize(ink)
                .addOnSuccessListener { result ->
                    val text = result.candidates.firstOrNull()?.text ?: ""
                    cont.resume(text)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private fun parseTags(rawText: String): List<String> {
        return rawText
            .replace("#", "")
            .replace(",", " ")
            .split("\\s+".toRegex())
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    private fun generateMarkdown(
        createdDate: String,
        tags: List<String>,
        content: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("created: $createdDate")
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

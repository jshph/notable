package com.ethran.notable.io

import android.graphics.RectF
import android.os.Environment
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Annotation
import com.ethran.notable.data.db.AnnotationType
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.datastore.GlobalAppSettings
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

    @Volatile private var currentLanguage: String? = null
    private var recognizer: com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer? = null
    private var currentModel: DigitalInkRecognitionModel? = null

    /**
     * Sync an inbox page to Obsidian. Tags come from the UI (pill selection),
     * content is recognized from all strokes on the page via ML Kit.
     * Annotation boxes mark regions to wrap in [[wiki links]] or #tags.
     */
    suspend fun syncInboxPage(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>
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

        ensureRecognizer(GlobalAppSettings.current.hwrLanguage)

        // 1. Recognize ALL strokes together to preserve natural text flow
        var fullText = if (allStrokes.isNotEmpty()) {
            log.i("Recognizing all ${allStrokes.size} strokes")
            val raw = recognizeStrokes(allStrokes)
            postProcessRecognition(raw)
        } else ""

        log.i("Full recognized text: '${fullText.take(200)}'")

        // 2. Find annotation text by diffing full recognition vs non-annotation recognition.
        //    Falls back to per-annotation recognition if the diff produces a count mismatch
        //    (which happens when removing strokes changes HWR context enough to alter other words).
        if (annotations.isNotEmpty()) {
            val sortedAnnotations = annotations.sortedWith(compareBy({ it.y }, { it.x }))

            // Collect stroke IDs that fall inside any annotation box
            val annotationStrokeIds = mutableSetOf<String>()
            val annotationStrokeMap = mutableMapOf<String, List<Stroke>>()
            for (annotation in sortedAnnotations) {
                val annotRect = RectF(
                    annotation.x, annotation.y,
                    annotation.x + annotation.width,
                    annotation.y + annotation.height
                )
                val overlapping = findStrokesInRect(allStrokes, annotRect)
                annotationStrokeMap[annotation.id] = overlapping
                overlapping.forEach { annotationStrokeIds.add(it.id) }
            }

            // Try diff-based approach first (better accuracy when it works)
            var diffSucceeded = false
            val nonAnnotStrokes = allStrokes.filter { it.id !in annotationStrokeIds }
            if (nonAnnotStrokes.isNotEmpty()) {
                val baseText = postProcessRecognition(recognizeStrokes(nonAnnotStrokes))
                log.i("Base text (without annotations): '${baseText.take(200)}'")

                val annotationTexts = diffWords(baseText, fullText)
                log.i("Diffed annotation texts: $annotationTexts")

                if (annotationTexts.size == sortedAnnotations.size) {
                    diffSucceeded = true
                    for ((i, annotation) in sortedAnnotations.withIndex()) {
                        val annotText = annotationTexts[i]
                        if (annotText.isBlank()) continue
                        log.i("Annotation ${annotation.type} (diff): '$annotText'")
                        // Strip trailing punctuation so it stays outside the markup
                        val cleaned = annotText.trimEnd('.', ',', ';', ':', '!', '?')
                        val trailing = annotText.removePrefix(cleaned)
                        val wrapped = wrapAnnotationText(annotation, cleaned) + trailing
                        fullText = fullText.replaceFirst(annotText, wrapped)
                    }
                } else {
                    log.w("Diff found ${annotationTexts.size} segments but have ${sortedAnnotations.size} annotations — falling back to per-annotation recognition")
                }
            }

            // Fallback: recognize each annotation's strokes individually
            if (!diffSucceeded) {
                for (annotation in sortedAnnotations) {
                    val overlapping = annotationStrokeMap[annotation.id] ?: continue
                    if (overlapping.isEmpty()) continue
                    val rawAnnotText = recognizeStrokes(overlapping).trim()
                    if (rawAnnotText.isBlank()) continue
                    log.i("Annotation ${annotation.type} (fallback raw): '$rawAnnotText'")

                    val matchText = findBestMatch(rawAnnotText, fullText)
                    if (matchText != null) {
                        log.i("Annotation ${annotation.type} (fallback matched): '$matchText'")
                        val cleaned = matchText.trimEnd('.', ',', ';', ':', '!', '?')
                        val trailing = matchText.removePrefix(cleaned)
                        val wrapped = wrapAnnotationText(annotation, cleaned) + trailing
                        fullText = fullText.replaceFirst(matchText, wrapped)
                    } else {
                        log.w("Annotation ${annotation.type}: could not find '$rawAnnotText' in full text")
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

    private suspend fun ensureRecognizer(language: String) {
        if (language == currentLanguage && recognizer != null) return

        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(language)
            ?: throw IllegalStateException("Unsupported HWR language: $language")
        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val manager = RemoteModelManager.getInstance()

        val isDownloaded = suspendCancellableCoroutine<Boolean> { cont ->
            manager.isModelDownloaded(model)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        if (!isDownloaded) {
            log.i("Downloading ML Kit model for $language...")
            suspendCancellableCoroutine<Void?> { cont ->
                manager.download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(null) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            log.i("Model downloaded")
        }

        recognizer?.close()
        currentModel = model
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        currentLanguage = language
    }

    /**
     * Segment strokes into lines based on vertical position, recognize each
     * line independently with WritingArea and pre-context, then join results.
     */
    private suspend fun recognizeStrokes(strokes: List<Stroke>): String {
        val rec = recognizer
            ?: throw IllegalStateException("ML Kit recognizer not initialized — call ensureRecognizer first")

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

        val sorted = strokes.sortedWith(
            compareBy<Stroke> { (it.top + it.bottom) / 2f }.thenBy { it.left }
        )

        val strokeHeights = sorted.map { it.bottom - it.top }.filter { it > 0 }.sorted()
        val medianHeight = if (strokeHeights.isNotEmpty()) {
            strokeHeights[strokeHeights.size / 2]
        } else {
            50f
        }
        val lineGapThreshold = medianHeight * 0.75f

        val lines = mutableListOf<MutableList<Stroke>>()
        var currentLine = mutableListOf(sorted.first())
        var currentLineCenter = (sorted.first().top + sorted.first().bottom) / 2f

        for (stroke in sorted.drop(1)) {
            val strokeCenter = (stroke.top + stroke.bottom) / 2f
            if (strokeCenter - currentLineCenter > lineGapThreshold) {
                lines.add(currentLine)
                currentLine = mutableListOf(stroke)
                currentLineCenter = strokeCenter
            } else {
                currentLine.add(stroke)
                currentLineCenter = currentLine.map { (it.top + it.bottom) / 2f }.average().toFloat()
            }
        }
        lines.add(currentLine)

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
     * Find the best matching substring of [annotText] within [fullText].
     * Tries the full recognized text first, then individual words (longest first).
     */
    private fun findBestMatch(annotText: String, fullText: String): String? {
        if (fullText.contains(annotText)) return annotText

        val words = annotText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val sorted = words.sortedByDescending { it.length }
        for (word in sorted) {
            if (word.length >= 2 && fullText.contains(word)) return word
        }
        return null
    }

    private fun wrapAnnotationText(annotation: Annotation, text: String): String {
        return when (annotation.type) {
            AnnotationType.WIKILINK.name -> "[[${text}]]"
            AnnotationType.TAG.name -> "#${text.replace(" ", "-")}"
            else -> text
        }
    }

    /**
     * Find contiguous word segments in [fullText] that are absent from [baseText].
     * Uses a simple word-level LCS diff.
     */
    private fun diffWords(baseText: String, fullText: String): List<String> {
        val baseWords = baseText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }

        val m = baseWords.size
        val n = fullWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val matched = BooleanArray(n)
        var i = m; var j = n
        while (i > 0 && j > 0) {
            if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                matched[j - 1] = true
                i--; j--
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        val segments = mutableListOf<String>()
        var current = mutableListOf<String>()
        for (k in fullWords.indices) {
            if (!matched[k]) {
                current.add(fullWords[k])
            } else if (current.isNotEmpty()) {
                segments.add(current.joinToString(" "))
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) segments.add(current.joinToString(" "))

        return segments
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

        result = result.replace(Regex("""[(\[]{1,2}([^)\]\n]+?)[)\]]{1,2}""")) { match ->
            "[[${match.groupValues[1].trim()}]]"
        }

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

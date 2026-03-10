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

        // 2. Find annotation text by diffing full recognition vs non-annotation recognition.
        //    Falls back to per-annotation recognition if the diff produces a count mismatch
        //    (which happens when removing strokes changes HWR context enough to alter other words).
        if (serviceReady && annotations.isNotEmpty()) {
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
                val baseText = postProcessRecognition(recognizeStrokesSafe(nonAnnotStrokes))
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
                    val rawAnnotText = recognizeStrokesSafe(overlapping).trim()
                    if (rawAnnotText.isBlank()) continue
                    log.i("Annotation ${annotation.type} (fallback raw): '$rawAnnotText'")

                    // Per-annotation HWR can be noisy — find the best matching
                    // substring in fullText, trying the full text first, then
                    // individual words from longest to shortest
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

    /**
     * Find the best matching substring of [annotText] within [fullText].
     * Tries the full recognized text first, then individual words (longest first).
     * Returns the matching substring as it appears in fullText, or null.
     */
    private fun findBestMatch(annotText: String, fullText: String): String? {
        if (fullText.contains(annotText)) return annotText

        // Try individual words, longest first (longer words are more specific)
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

    private suspend fun recognizeStrokesSafe(strokes: List<Stroke>): String {
        return try {
            OnyxHWREngine.recognizeStrokes(strokes, viewWidth = 1404f, viewHeight = 1872f) ?: ""
        } catch (e: Exception) {
            log.e("OnyxHWR failed: ${e.message}")
            ""
        }
    }

    /**
     * Find contiguous word segments in [fullText] that are absent from [baseText].
     * Uses a simple word-level LCS diff. Returns segments in the order they appear
     * in fullText, with consecutive inserted words joined by spaces.
     *
     * Example: baseText="This is a document", fullText="This is a new document pkm"
     *   → ["new", "pkm"]
     */
    private fun diffWords(baseText: String, fullText: String): List<String> {
        val baseWords = baseText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }

        // LCS to find which words in fullText are "matched" to baseText
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

        // Backtrack to find which fullText words are NOT in the LCS (= annotation words)
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

        // Group consecutive unmatched words into segments
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

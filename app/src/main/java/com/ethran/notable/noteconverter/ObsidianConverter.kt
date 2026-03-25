package com.ethran.notable.noteconverter

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.ethran.notable.io.OnyxHWREngine
import com.ethran.notable.io.OnyxNoteParser
import com.ethran.notable.io.ObsidianTemplateEngine
import com.ethran.notable.io.SearchablePdfGenerator
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val log = ShipBook.getLogger("ObsidianConverter")

private inline fun <T> profileStage(
    timings: MutableList<Pair<String, Long>>,
    stageName: String,
    block: () -> T
): T {
    val startNs = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
    timings += stageName to elapsedMs
    return result
}

private fun logProfileSummary(fileName: String, timings: List<Pair<String, Long>>) {
    if (timings.isEmpty()) return

    val totalMs = timings.sumOf { it.second }
    val breakdown = timings.joinToString(" | ") { (name, ms) ->
        val pct = if (totalMs > 0L) ((ms.toDouble() * 100.0) / totalMs.toDouble()).roundToInt() else 0
        "$name=${ms}ms (${pct}%)"
    }

    val message = "PROFILE [$fileName] total=${totalMs}ms :: $breakdown"
    log.i(message)
    android.util.Log.i("ObsidianConverter", message)
}

/**
 * Converts .note files to Obsidian-compatible Markdown with YAML frontmatter.
 */
class ObsidianConverter(private val context: Context) {
    
    data class ConversionResult(
        val success: Boolean,
        val inputFile: File,
        val outputFile: File? = null,
        val recognizedText: String? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Convert a single .note file to Obsidian markdown.
     */
    suspend fun convertToObsidian(
        noteFile: File,
        outputDir: File
    ): ConversionResult = withContext(Dispatchers.IO) {
        
        try {
            log.i("Converting: ${noteFile.name}")
            
            // Step 1: Parse .note file
            val noteDoc: OnyxNoteParser.NoteDocument? = noteFile.inputStream().use { stream ->
                OnyxNoteParser.parseNoteFile(stream)
            }
            
            if (noteDoc == null) {
                return@withContext ConversionResult(
                    success = false,
                    inputFile = noteFile,
                    errorMessage = "Failed to parse .note file"
                )
            }
            
            val notePages = noteDoc.pages.filter { it.strokes.isNotEmpty() }
            if (notePages.isEmpty()) {
                return@withContext ConversionResult(
                    success = false,
                    inputFile = noteFile,
                    errorMessage = "No strokes found"
                )
            }
            
            log.i("Parsed ${notePages.size} page(s), ${noteDoc.strokes.size} total strokes")
            
            // Step 2: Bind to HWR service
            val serviceReady = try {
                OnyxHWREngine.bindAndAwait(context, timeoutMs = 3000)
            } catch (e: Exception) {
                log.e("HWR service bind failed: ${e.message}")
                false
            }
            
            if (!serviceReady) {
                return@withContext ConversionResult(
                    success = false,
                    inputFile = noteFile,
                    errorMessage = "MyScript HWR service unavailable"
                )
            }
            
            // Step 3: Recognize handwriting
            val language = com.ethran.notable.data.datastore.GlobalAppSettings.current.recognitionLanguage
            val pageTexts = notePages.map { page ->
                OnyxHWREngine.recognizeStrokes(
                    strokes = page.strokes,
                    viewWidth = noteDoc.pageWidth,
                    viewHeight = noteDoc.pageHeight,
                    language = language
                ).orEmpty().trim()
            }
            val recognizedText = pageTexts.filter { it.isNotBlank() }.joinToString("\n\n")

            if (recognizedText.isNullOrBlank()) {
                return@withContext ConversionResult(
                    success = false,
                    inputFile = noteFile,
                    errorMessage = "Recognition returned no text"
                )
            }
            
            log.i("Recognized ${recognizedText.length} characters")
            
            // Step 4: Generate Obsidian markdown
            val markdown = generateObsidianMarkdown(
                title = noteFile.nameWithoutExtension,
                content = recognizedText,
                sourceFile = noteFile
            )
            
            // Step 5: Write output file
            outputDir.mkdirs()
            val outputFilename = NoteFileScanner.generateOutputFilename(noteFile)
            val outputFile = File(outputDir, outputFilename)
            
            outputFile.writeText(markdown)
            
            log.i("Wrote: ${outputFile.absolutePath}")
            
            ConversionResult(
                success = true,
                inputFile = noteFile,
                outputFile = outputFile,
                recognizedText = recognizedText
            )
            
        } catch (e: Exception) {
            log.e("Conversion failed for ${noteFile.name}: ${e.message}")
            e.printStackTrace()
            ConversionResult(
                success = false,
                inputFile = noteFile,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Convert multiple files in batch.
     */
    suspend fun convertBatch(
        noteFiles: List<File>,
        outputDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<ConversionResult> {
        
        val results = mutableListOf<ConversionResult>()
        
        noteFiles.forEachIndexed { index, file ->
            onProgress(index + 1, noteFiles.size)
            val result = convertToObsidian(file, outputDir)
            results.add(result)
        }
        
        return results
    }
    
    /**
     * Convert a single .note DocumentFile to Obsidian markdown.
     */
    suspend fun convertToObsidianWithDocumentFile(
        noteFile: DocumentFile,
        outputDir: File,
        pdfOutputDir: File? = null
    ): ConversionResult = withContext(Dispatchers.IO) {
        
        try {
            val fileName = noteFile.name ?: "unknown.note"
            val timings = mutableListOf<Pair<String, Long>>()
            val emitProfile = { outcome: String ->
                logProfileSummary("$fileName/$outcome", timings)
            }

            log.i("Converting: $fileName")
            android.util.Log.i("ObsidianConverter", "Converting: $fileName")
            
            // Step 1: Parse .note file from DocumentFile
            android.util.Log.d("ObsidianConverter", "Opening input stream for: ${noteFile.uri}")
            val noteDoc: OnyxNoteParser.NoteDocument? = profileStage(timings, "parseNoteFile") {
                context.contentResolver.openInputStream(noteFile.uri)?.use { stream ->
                    android.util.Log.d("ObsidianConverter", "Parsing .note file...")
                    OnyxNoteParser.parseNoteFile(stream)
                }
            }
            
            android.util.Log.d("ObsidianConverter", "Parse result: ${noteDoc != null}")
            
            if (noteDoc == null) {
                android.util.Log.e("ObsidianConverter", "Failed to parse .note file")
                emitProfile("parse-failed")
                return@withContext ConversionResult(
                    success = false,
                    inputFile = File(fileName),  // Dummy file for result
                    errorMessage = "Failed to parse .note file"
                )
            }
            
            val notePages = noteDoc.pages.filter { it.strokes.isNotEmpty() }
            if (notePages.isEmpty()) {
                android.util.Log.w("ObsidianConverter", "No strokes found in file")
                emitProfile("no-strokes")
                return@withContext ConversionResult(
                    success = false,
                    inputFile = File(fileName),
                    errorMessage = "No strokes found"
                )
            }
            
            log.i("Parsed ${notePages.size} page(s), ${noteDoc.strokes.size} total strokes")
            android.util.Log.i("ObsidianConverter", "Parsed ${notePages.size} page(s), ${noteDoc.strokes.size} total strokes")
            
            // Step 2: Bind to HWR service
            android.util.Log.d("ObsidianConverter", "Binding to HWR service...")
            val serviceReady = try {
                profileStage(timings, "bindHwrService") {
                    OnyxHWREngine.bindAndAwait(context, timeoutMs = 3000)
                }
            } catch (e: Exception) {
                log.e("HWR service bind failed: ${e.message}")
                android.util.Log.e("ObsidianConverter", "HWR service bind failed: ${e.message}", e)
                false
            }
            
            android.util.Log.d("ObsidianConverter", "HWR service ready: $serviceReady")
            
            if (!serviceReady) {
                android.util.Log.e("ObsidianConverter", "MyScript HWR service unavailable")
                emitProfile("hwr-unavailable")
                return@withContext ConversionResult(
                    success = false,
                    inputFile = File(fileName),
                    errorMessage = "MyScript HWR service unavailable"
                )
            }
            
            // Step 3: Recognize handwriting
            android.util.Log.d("ObsidianConverter", "Recognizing ${notePages.size} page(s)...")
            val language = com.ethran.notable.data.datastore.GlobalAppSettings.current.recognitionLanguage
            val pdfModeEnabled = com.ethran.notable.data.datastore.GlobalAppSettings.current.batchConverterPdfMode
            val needsDetailedRecognition = pdfModeEnabled && pdfOutputDir != null

            val pageRecognition = if (needsDetailedRecognition) {
                notePages.mapIndexed { index, page ->
                    profileStage(timings, "hwrPage${index + 1}Detailed") {
                        android.util.Log.d(
                            "ObsidianConverter",
                            "Recognizing page ${index + 1}/${notePages.size} (detailed) with ${page.strokes.size} strokes"
                        )
                        OnyxHWREngine.recognizeStrokesDetailed(
                            strokes = page.strokes,
                            viewWidth = noteDoc.pageWidth,
                            viewHeight = noteDoc.pageHeight,
                            language = language
                        )
                    }
                }
            } else {
                emptyList()
            }

            val pageTexts = if (needsDetailedRecognition) {
                pageRecognition.map { it?.text.orEmpty().trim() }
            } else {
                notePages.mapIndexed { index, page ->
                    profileStage(timings, "hwrPage${index + 1}Fast") {
                        android.util.Log.d(
                            "ObsidianConverter",
                            "Recognizing page ${index + 1}/${notePages.size} (fast) with ${page.strokes.size} strokes"
                        )
                        OnyxHWREngine.recognizeStrokes(
                            strokes = page.strokes,
                            viewWidth = noteDoc.pageWidth,
                            viewHeight = noteDoc.pageHeight,
                            language = language
                        ).orEmpty().trim()
                    }
                }
            }
            val recognizedText = profileStage(timings, "assembleRecognizedText") {
                pageTexts
                    .mapIndexedNotNull { index, text ->
                        if (text.isBlank()) null else "## Page ${index + 1}\n\n$text"
                    }
                    .joinToString("\n\n")
            }
            
            android.util.Log.d("ObsidianConverter", "Recognition result: ${recognizedText.length} characters")
            
            if (recognizedText.isNullOrBlank()) {
                android.util.Log.e("ObsidianConverter", "Recognition returned no text")
                emitProfile("empty-recognition")
                return@withContext ConversionResult(
                    success = false,
                    inputFile = File(fileName),
                    errorMessage = "Recognition returned no text"
                )
            }
            
            log.i("Recognized ${recognizedText.length} characters")
            android.util.Log.i("ObsidianConverter", "Recognized ${recognizedText.length} characters")
            
            // Step 4: Generate PDF if PDF mode is enabled
            var pdfRelativePath: String? = null
            
            if (pdfModeEnabled && pdfOutputDir != null) {
                android.util.Log.d("ObsidianConverter", "PDF mode enabled, generating searchable PDF")
                
                val baseName = fileName.substringBeforeLast(".note", fileName)
                val pdfFile = File(pdfOutputDir, "$baseName.pdf")

                val pdfPages = profileStage(timings, "assemblePdfPages") {
                    notePages.mapIndexed { index, page ->
                        SearchablePdfGenerator.PdfPageContent(
                            strokes = page.strokes,
                            recognizedText = pageTexts.getOrElse(index) { "" },
                            recognizedWords = if (needsDetailedRecognition) {
                                pageRecognition.getOrNull(index)?.words.orEmpty()
                            } else {
                                emptyList()
                            },
                            pageWidth = noteDoc.pageWidth,
                            pageHeight = noteDoc.pageHeight
                        )
                    }
                }

                val pdfResult = profileStage(timings, "generateSearchablePdf") {
                    SearchablePdfGenerator.generateSearchablePdfForPages(
                        pages = pdfPages,
                        outputFile = pdfFile
                    )
                }
                
                if (pdfResult.success && pdfResult.pdfFile != null) {
                    // Use just the filename - Obsidian will find it anywhere in the vault
                    pdfRelativePath = pdfResult.pdfFile.name
                    android.util.Log.i("ObsidianConverter", "PDF generated: ${pdfResult.pdfFile.name}")
                } else {
                    android.util.Log.w("ObsidianConverter", "PDF generation failed: ${pdfResult.errorMessage}")
                }
            }
            
            // Step 5: Generate Obsidian markdown
            val baseName = fileName.substringBeforeLast(".note", fileName)
            val markdown = profileStage(timings, "generateMarkdown") {
                generateObsidianMarkdown(
                    title = baseName,
                    content = recognizedText,
                    sourceFile = null,  // DocumentFile, use metadata instead
                    created = Date(noteFile.lastModified()),
                    pdfPath = pdfRelativePath
                )
            }
            
            // Step 6: Write output file
            android.util.Log.d("ObsidianConverter", "Writing output to: ${outputDir.absolutePath}")
            val outputFile = profileStage(timings, "writeMarkdownFile") {
                outputDir.mkdirs()
                val outputFilename = "$baseName.md"
                val outputFile = File(outputDir, outputFilename)
                outputFile.writeText(markdown)
                outputFile
            }
            
            log.i("Wrote: ${outputFile.absolutePath}")
            android.util.Log.i("ObsidianConverter", "Wrote: ${outputFile.absolutePath}")
            emitProfile("success")
            
            ConversionResult(
                success = true,
                inputFile = File(fileName),
                outputFile = outputFile,
                recognizedText = recognizedText
            )
            
        } catch (e: Exception) {
            log.e("Conversion failed: ${e.message}")
            android.util.Log.e("ObsidianConverter", "Conversion failed: ${e.message}", e)
            e.printStackTrace()
            ConversionResult(
                success = false,
                inputFile = File(noteFile.name ?: "unknown"),
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Convert multiple DocumentFiles in batch.
     */
    suspend fun convertBatchWithDocumentFile(
        context: Context,
        noteFiles: List<DocumentFile>,
        outputDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<ConversionResult> {
        
        val results = mutableListOf<ConversionResult>()
        
        noteFiles.forEachIndexed { index, file ->
            onProgress(index + 1, noteFiles.size)
            val result = convertToObsidianWithDocumentFile(file, outputDir)
            results.add(result)
        }
        
        return results
    }
    
    /**
     * Generate Obsidian-compatible markdown with YAML frontmatter.
     * 
     * Format:
     * ```
     * ---
     * title: Note Title
     * created: 2026-03-16T09:00:00
     * modified: 2026-03-16T10:30:00
     * tags:
     *   - status/todo
     * source: onyx-note
     * ---
     * 
     * # Note Title
     * 
     * [Recognized text content]
     * ```
     */
    private fun generateObsidianMarkdown(
        title: String,
        content: String,
        sourceFile: File? = null,
        created: Date? = null,
        pdfPath: String? = null
    ): String {
        val now = Date()
        val createdDate = created ?: (sourceFile?.let { Date(it.lastModified()) } ?: now)

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val templateUri = com.ethran.notable.data.datastore.GlobalAppSettings.current.obsidianTemplateUri

        return ObsidianTemplateEngine.renderMarkdown(
            context = context,
            templateUriString = templateUri,
            title = title,
            created = createdDate,
            modified = now,
            content = content,
            pdfPath = pdfPath
        ) {
            buildString {
                appendLine("---")
                appendLine("title: $title")
                appendLine("created: ${isoFormatter.format(createdDate)}")
                appendLine("modified: ${isoFormatter.format(now)}")
                appendLine("tags:")
                appendLine("  - status/todo")
                appendLine("source: onyx-note")
                appendLine("---")
                appendLine()
                appendLine("# $title")
                appendLine()

                if (pdfPath != null) {
                    appendLine("![]($pdfPath)")
                    appendLine()
                }

                appendLine(content)
            }
        }
    }
    
    /**
     * Calculate relative path from source directory to target file.
     */
    private fun calculateRelativePath(fromDir: String, toFile: String): String {
        // Simple implementation: if both are on external SD, use relative path
        // Otherwise use absolute path
        val from = File(fromDir).canonicalFile
        val to = File(toFile).canonicalFile
        
        return try {
            from.toPath().relativize(to.toPath()).toString().replace('\\', '/')
        } catch (e: Exception) {
            // Fallback to filename only if same directory, otherwise absolute
            to.name
        }
    }
}

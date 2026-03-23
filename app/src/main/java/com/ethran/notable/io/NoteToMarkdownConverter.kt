package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import com.ethran.notable.data.db.Stroke
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = ShipBook.getLogger("NoteToMarkdownConverter")

/**
 * Converts Onyx .note files to Markdown using MyScript handwriting recognition.
 * 
 * Usage:
 * ```
 * val converter = NoteToMarkdownConverter(context)
 * val result = converter.convertToMarkdown(noteFileUri, outputDir)
 * ```
 */
class NoteToMarkdownConverter(private val context: Context) {
    
    data class ConversionResult(
        val success: Boolean,
        val outputFile: File? = null,
        val recognizedText: String? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Convert a .note file to markdown.
     * 
     * @param noteFileUri URI of the .note file (from SAF file picker)
     * @param outputDir Directory to write the .md file
     * @param filename Optional output filename (defaults to timestamp)
     * @return ConversionResult with output file path or error
     */
    suspend fun convertToMarkdown(
        noteFileUri: Uri,
        outputDir: File,
        filename: String? = null
    ): ConversionResult = withContext(Dispatchers.IO) {
        
        try {
            // Step 1: Parse the .note file
            log.i("Parsing .note file: $noteFileUri")
            val noteDoc: OnyxNoteParser.NoteDocument? = context.contentResolver.openInputStream(noteFileUri)?.use { stream ->
                OnyxNoteParser.parseNoteFile(stream)
            }
            
            if (noteDoc == null) {
                return@withContext ConversionResult(
                    success = false,
                    errorMessage = "Failed to parse .note file"
                )
            }
            
            log.i("Parsed ${noteDoc.strokes.size} strokes from ${noteDoc.title}")
            
            if (noteDoc.strokes.isEmpty()) {
                return@withContext ConversionResult(
                    success = false,
                    errorMessage = "No strokes found in .note file"
                )
            }
            
            // Step 2: Bind to MyScript HWR service
            val serviceReady = try {
                OnyxHWREngine.bindAndAwait(context, timeoutMs = 3000)
            } catch (e: Exception) {
                log.e("Failed to bind to HWR service: ${e.message}")
                false
            }
            
            if (!serviceReady) {
                return@withContext ConversionResult(
                    success = false,
                    errorMessage = "MyScript HWR service unavailable. Is this running on an Onyx Boox device?"
                )
            }
            
            // Step 3: Recognize handwriting
            log.i("Recognizing handwriting...")
            val language = com.ethran.notable.data.datastore.GlobalAppSettings.current.recognitionLanguage
            val recognizedText = OnyxHWREngine.recognizeStrokes(
                strokes = noteDoc.strokes,
                viewWidth = noteDoc.pageWidth,
                viewHeight = noteDoc.pageHeight,
                language = language
            )
            
            if (recognizedText.isNullOrBlank()) {
                return@withContext ConversionResult(
                    success = false,
                    errorMessage = "Recognition returned no text"
                )
            }
            
            log.i("Recognized ${recognizedText.length} characters")
            
            // Step 4: Generate markdown
            val markdown = generateMarkdown(
                title = noteDoc.title,
                content = recognizedText,
                createdDate = Date()
            )
            
            // Step 5: Write to file
            val outputFilename = filename ?: generateFilename()
            val outputFile = File(outputDir, outputFilename)
            outputFile.writeText(markdown)
            
            log.i("Wrote markdown to: ${outputFile.absolutePath}")
            
            ConversionResult(
                success = true,
                outputFile = outputFile,
                recognizedText = recognizedText
            )
            
        } catch (e: Exception) {
            log.e("Conversion failed: ${e.message}")
            e.printStackTrace()
            ConversionResult(
                success = false,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Convert multiple .note files in batch.
     */
    suspend fun convertMultiple(
        noteFileUris: List<Uri>,
        outputDir: File
    ): List<ConversionResult> {
        return noteFileUris.map { uri ->
            convertToMarkdown(uri, outputDir)
        }
    }
    
    /**
     * Generate markdown content with YAML frontmatter.
     */
    private fun generateMarkdown(
        title: String,
        content: String,
        createdDate: Date
    ): String {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dateStr = dateFormatter.format(createdDate)
        
        return buildString {
            appendLine("---")
            appendLine("title: $title")
            appendLine("created: $dateStr")
            appendLine("source: onyx-note")
            appendLine("---")
            appendLine()
            appendLine("# $title")
            appendLine()
            appendLine(content)
        }
    }
    
    /**
     * Generate timestamped filename for output.
     */
    private fun generateFilename(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        return "$timestamp.md"
    }
}

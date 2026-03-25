package com.ethran.notable.ui.components

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.noteconverter.NoteFileScanner
import com.ethran.notable.noteconverter.ObsidianConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt

data class ConversionProgress(
    val isConverting: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val results: List<String> = emptyList()
)

private inline fun <T> profileBatchStage(
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

private fun logBatchProfile(timings: List<Pair<String, Long>>) {
    if (timings.isEmpty()) return
    val totalMs = timings.sumOf { it.second }
    val breakdown = timings.joinToString(" | ") { (name, ms) ->
        val pct = if (totalMs > 0L) ((ms.toDouble() * 100.0) / totalMs.toDouble()).roundToInt() else 0
        "$name=${ms}ms (${pct}%)"
    }
    android.util.Log.i("BatchConverter", "BATCH_PROFILE total=${totalMs}ms :: $breakdown")
}

private fun writeFileToTreeUri(
    context: android.content.Context,
    treeUri: Uri,
    fileName: String,
    mimeType: String,
    sourceFile: File
): Uri? {
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

    val existingUri = findChildDocumentUriByName(context, treeUri, treeDocId, fileName)

    // Overwrite existing file if present.
    if (existingUri != null) {
        try {
            context.contentResolver.openOutputStream(existingUri, "w")?.use { out ->
                sourceFile.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            } ?: return null
            return existingUri
        } catch (_: FileNotFoundException) {
            // Stale handle from provider; create a new document as fallback.
        } catch (_: SecurityException) {
            // Provider may reject old handle, fallback to createDocument.
        } catch (_: IllegalArgumentException) {
            // Some providers throw this if uri no longer valid.
        }
    }

    val createdUri = DocumentsContract.createDocument(
        context.contentResolver,
        parentDocUri,
        mimeType,
        fileName
    ) ?: return null

    context.contentResolver.openOutputStream(createdUri, "w")?.use { out ->
        sourceFile.inputStream().use { inp ->
            inp.copyTo(out)
        }
    } ?: return null

    return createdUri
}

private fun findChildDocumentUriByName(
    context: android.content.Context,
    treeUri: Uri,
    treeDocId: String,
    fileName: String
): Uri? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )

    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (docIdIndex == -1 || nameIndex == -1) return null

        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex) ?: continue
            if (name == fileName) {
                val docId = cursor.getString(docIdIndex) ?: continue
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }
        }
    }

    return null
}

@Composable
fun BatchConverterSection(
    modifier: Modifier = Modifier,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var progress by remember { mutableStateOf(ConversionProgress()) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Batch Converter",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "Convert .note files to markdown. Configure folders in Settings → General.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Status display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val inputStatus = if (settings.batchConverterInputUri.isNotEmpty()) {
                    "✓ Input: " + settings.batchConverterInputUri.substringAfterLast("%2F").ifEmpty {
                        settings.batchConverterInputUri.substringAfterLast("/")
                    }
                } else {
                    "⚠ Input: Not set"
                }
                
                val outputStatus = if (settings.obsidianOutputUri.isNotEmpty()) {
                    "✓ Output: " + settings.obsidianOutputUri.substringAfterLast("%2F").ifEmpty {
                        settings.obsidianOutputUri.substringAfterLast("/")
                    }
                } else {
                    "⚠ Output: Not set"
                }
                
                Text(
                    inputStatus,
                    style = MaterialTheme.typography.caption,
                    color = if (settings.batchConverterInputUri.isNotEmpty()) Color(0xFF2E7D32) else Color(0xFFFF6F00)
                )
                Text(
                    outputStatus,
                    style = MaterialTheme.typography.caption,
                    color = if (settings.obsidianOutputUri.isNotEmpty()) Color(0xFF2E7D32) else Color(0xFFFF6F00)
                )
                
                // PDF status if PDF mode is enabled
                if (settings.batchConverterPdfMode) {
                    val pdfStatus = if (settings.batchConverterPdfUri.isNotEmpty()) {
                        "✓ PDF: " + settings.batchConverterPdfUri.substringAfterLast("%2F").ifEmpty {
                            settings.batchConverterPdfUri.substringAfterLast("/")
                        }
                    } else {
                        "⚠ PDF: Not set"
                    }
                    
                    Text(
                        pdfStatus,
                        style = MaterialTheme.typography.caption,
                        color = if (settings.batchConverterPdfUri.isNotEmpty()) Color(0xFF2E7D32) else Color(0xFFFF6F00)
                    )
                }
            }
        }
        
        // Last scan info
        if (settings.batchConverterLastScan > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                val lastScanDate = formatter.format(java.util.Date(settings.batchConverterLastScan))
                
                Text(
                    "Last scan: $lastScanDate",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
                
                TextButton(
                    onClick = {
                        onSettingsChange(settings.copy(batchConverterLastScan = 0))
                    },
                    modifier = Modifier.padding(0.dp)
                ) {
                    Text(
                        "Reset",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Convert button
        Button(
            onClick = {
                if (settings.batchConverterInputUri.isEmpty() || settings.obsidianOutputUri.isEmpty()) {
                    return@Button
                }
                
                scope.launch {
                    progress = ConversionProgress(isConverting = true)
                    
                    try {
                        val results = withContext(Dispatchers.IO) {
                            val timings = mutableListOf<Pair<String, Long>>()
                            // Scan for .note files
                            val inputUri = Uri.parse(settings.batchConverterInputUri)
                            
                            val scanResult = profileBatchStage(timings, "scanForChangedFiles") {
                                NoteFileScanner.scanForChangedFilesWithDocumentFile(
                                    context = context,
                                    inputDirUri = inputUri,
                                    lastScanTime = settings.batchConverterLastScan
                                )
                            }
                            
                            val noteFiles = scanResult.newFiles + scanResult.modifiedFiles
                            
                            if (noteFiles.isEmpty()) {
                                logBatchProfile(timings)
                                return@withContext listOf("No .note files found")
                            }
                            
                            progress = progress.copy(total = noteFiles.size)
                            
                            // Get output directory as File
                            // For SAF URIs, we need to extract the path or work with DocumentFile
                            val outputUri = Uri.parse(settings.obsidianOutputUri)
                            val outputDocDir = profileBatchStage(timings, "resolveMarkdownOutput") {
                                DocumentFile.fromTreeUri(context, outputUri)
                            }
                            
                            if (outputDocDir == null || !outputDocDir.exists()) {
                                logBatchProfile(timings)
                                return@withContext listOf("Error: Output directory not accessible")
                            }
                            
                            // Get PDF output directory if PDF mode is enabled
                            val pdfDocDir = if (settings.batchConverterPdfMode && settings.batchConverterPdfUri.isNotEmpty()) {
                                profileBatchStage(timings, "resolvePdfOutput") {
                                    val pdfUri = Uri.parse(settings.batchConverterPdfUri)
                                    DocumentFile.fromTreeUri(context, pdfUri)
                                }
                            } else {
                                null
                            }
                            
                            if (settings.batchConverterPdfMode && (pdfDocDir == null || !pdfDocDir.exists())) {
                                logBatchProfile(timings)
                                return@withContext listOf("Error: PDF output directory not accessible")
                            }
                            
                            // Convert each file
                            val converter = ObsidianConverter(context)
                            val conversionResults = mutableListOf<String>()
                            
                            noteFiles.forEachIndexed { index, noteFile ->
                                progress = progress.copy(current = index + 1)
                                
                                try {
                                    // For now, write to a temp location then copy via SAF
                                    // This is a workaround since ObsidianConverter expects File
                                    val tempDir = context.cacheDir
                                    val tempPdfDir = if (settings.batchConverterPdfMode) {
                                        File(context.cacheDir, "pdf_temp").also { it.mkdirs() }
                                    } else {
                                        null
                                    }
                                    
                                    val tempResult = profileBatchStage(timings, "convertFile${index + 1}") {
                                        converter.convertToObsidianWithDocumentFile(
                                            noteFile = noteFile,
                                            outputDir = tempDir,
                                            pdfOutputDir = tempPdfDir
                                        )
                                    }
                                    
                                    if (tempResult.success && tempResult.outputFile != null) {
                                        // Copy markdown to SAF location
                                        val mdFileName = tempResult.outputFile.name
                                        val mdOutputUri = profileBatchStage(timings, "writeMdToSaf${index + 1}") {
                                            writeFileToTreeUri(
                                                context = context,
                                                treeUri = outputUri,
                                                fileName = mdFileName,
                                                mimeType = "text/markdown",
                                                sourceFile = tempResult.outputFile
                                            )
                                        }
                                        
                                        if (mdOutputUri != null) {
                                            tempResult.outputFile.delete()
                                            
                                            // Copy PDF to SAF location if PDF mode is enabled
                                            if (settings.batchConverterPdfMode && tempPdfDir != null && pdfDocDir != null) {
                                                val baseName = mdFileName.substringBeforeLast(".md")
                                                val pdfTempFile = File(tempPdfDir, "$baseName.pdf")
                                                
                                                if (pdfTempFile.exists()) {
                                                    val pdfFileName = "$baseName.pdf"
                                                    val pdfTreeUri = Uri.parse(settings.batchConverterPdfUri)
                                                    val pdfOutputUri = profileBatchStage(timings, "writePdfToSaf${index + 1}") {
                                                        writeFileToTreeUri(
                                                            context = context,
                                                            treeUri = pdfTreeUri,
                                                            fileName = pdfFileName,
                                                            mimeType = "application/pdf",
                                                            sourceFile = pdfTempFile
                                                        )
                                                    }
                                                    if (pdfOutputUri != null) {
                                                        pdfTempFile.delete()
                                                        android.util.Log.i("BatchConverter", "Copied PDF: $pdfOutputUri")
                                                    }
                                                }
                                            }
                                            
                                            conversionResults.add("✓ ${noteFile.name}")
                                        } else {
                                            conversionResults.add("✗ ${noteFile.name}: Failed to create output file")
                                        }
                                    } else {
                                        conversionResults.add("✗ ${noteFile.name}: ${tempResult.errorMessage}")
                                    }
                                } catch (e: Exception) {
                                    conversionResults.add("✗ ${noteFile.name}: ${e.message}")
                                }
                            }
                            logBatchProfile(timings)
                            
                            conversionResults
                        }
                        
                        progress = progress.copy(
                            isConverting = false,
                            results = results
                        )
                        
                        // Update last scan timestamp
                        onSettingsChange(settings.copy(
                            batchConverterLastScan = System.currentTimeMillis()
                        ))
                        
                    } catch (e: Exception) {
                        progress = progress.copy(
                            isConverting = false,
                            results = listOf("Error: ${e.message}")
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !progress.isConverting && 
                     settings.batchConverterInputUri.isNotEmpty() && 
                     settings.obsidianOutputUri.isNotEmpty() &&
                     (!settings.batchConverterPdfMode || settings.batchConverterPdfUri.isNotEmpty())
        ) {
            if (progress.isConverting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Converting ${progress.current}/${progress.total}...")
            } else {
                Text(if (settings.batchConverterLastScan > 0) "Convert New/Changed Files" else "Convert All Files")
            }
        }
        
        // Results
        if (progress.results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                "Results:",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium
            )
            
            progress.results.take(5).forEach { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.caption,
                    color = if (result.startsWith("✓")) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            
            if (progress.results.size > 5) {
                Text(
                    "... and ${progress.results.size - 5} more",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

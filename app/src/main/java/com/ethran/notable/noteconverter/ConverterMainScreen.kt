package com.ethran.notable.noteconverter

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private inline fun <T> profileUiStage(
    timings: MutableList<Pair<String, Long>>,
    stageName: String,
    block: () -> T
): T {
    val startNs = System.nanoTime()
    val result = block()
    timings += stageName to ((System.nanoTime() - startNs) / 1_000_000)
    return result
}

private fun logUiProfileSummary(timings: List<Pair<String, Long>>) {
    if (timings.isEmpty()) return
    val totalMs = timings.sumOf { it.second }
    val breakdown = timings.joinToString(" | ") { (name, ms) ->
        val pct = if (totalMs > 0L) ((ms.toDouble() * 100.0) / totalMs.toDouble()).roundToInt() else 0
        "$name=${ms}ms (${pct}%)"
    }
    android.util.Log.i("ConverterMain", "PIPELINE_PROFILE total=${totalMs}ms :: $breakdown")
}

/**
 * Main converter screen UI.
 */
@Composable
fun ConverterMainScreen(
    settings: ConverterSettings,
    converter: ObsidianConverter,
    onShowSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    
    var inputDirUri by remember { mutableStateOf<String?>(null) }
    var outputDirUri by remember { mutableStateOf<String?>(null) }
    var lastScanTime by remember { mutableStateOf<Long>(0) }
    
    var isScanning by remember { mutableStateOf(false) }
    var isConverting by remember { mutableStateOf(false) }
    var conversionResults by remember { mutableStateOf<List<ObsidianConverter.ConversionResult>>(emptyList()) }
    var currentProgress by remember { mutableStateOf(0 to 0) }
    var statusMessage by remember { mutableStateOf("Ready") }
    
    // Load settings
    LaunchedEffect(Unit) {
        launch {
            settings.inputDir.collect { dir -> inputDirUri = dir }
        }
        launch {
            settings.outputDir.collect { dir -> outputDirUri = dir }
        }
        launch {
            settings.lastScanTimestamp.collect { time -> lastScanTime = time }
        }
    }
    
    // Scan and convert function
    fun scanAndConvert() {
        if (inputDirUri == null || outputDirUri == null) {
            statusMessage = "Please configure directories in Settings"
            return
        }
        
        scope.launch {
            try {
                val timings = mutableListOf<Pair<String, Long>>()
                isScanning = true
                statusMessage = "Scanning for .note files..."
                
                // Use DocumentFile scanner (works with scoped storage)
                android.util.Log.d("ConverterMain", "Starting scan. Input URI: $inputDirUri, LastScan: $lastScanTime")
                val scanResult = profileUiStage(timings, "scanForChangedFiles") {
                    NoteFileScanner.scanForChangedFilesWithDocumentFile(
                        context = context,
                        inputDirUri = Uri.parse(inputDirUri),
                        lastScanTime = lastScanTime
                    )
                }
                
                android.util.Log.d("ConverterMain", "Scan result: ${scanResult.newFiles.size} new, ${scanResult.modifiedFiles.size} modified")
                
                isScanning = false
                
                if (scanResult.isEmpty) {
                    statusMessage = "No new or modified files found (scanned all subdirectories)"
                    return@launch
                }
                
                val filesToConvert = scanResult.newFiles + scanResult.modifiedFiles
                statusMessage = "Found ${filesToConvert.size} files to convert"
                android.util.Log.d("ConverterMain", "Files to convert: ${filesToConvert.size}")
                
                // Convert URIs to get output directory
                android.util.Log.d("ConverterMain", "Output URI: $outputDirUri")
                val outputDir = profileUiStage(timings, "resolveOutputUri") {
                    uriToFile(outputDirUri!!)
                }
                android.util.Log.d("ConverterMain", "Output dir as File: ${outputDir?.absolutePath}")
                
                if (outputDir == null) {
                    statusMessage = "Invalid output directory path"
                    android.util.Log.e("ConverterMain", "Failed to convert output URI to File")
                    isConverting = false
                    return@launch
                }
                
                // Convert files
                android.util.Log.i("ConverterMain", "Starting conversion of ${filesToConvert.size} files to ${outputDir.absolutePath}")
                isConverting = true
                val results = profileUiStage(timings, "convertBatch") {
                    converter.convertBatchWithDocumentFile(
                        context = context,
                        noteFiles = filesToConvert,
                        outputDir = outputDir,
                        onProgress = { current, total ->
                            currentProgress = current to total
                            statusMessage = "Converting: $current / $total"
                        }
                    )
                }
                
                conversionResults = results
                
                profileUiStage(timings, "updateLastScanTimestamp") {
                    settings.updateLastScanTimestamp()
                }
                
                val successCount = results.count { it.success }
                statusMessage = "Completed: $successCount / ${results.size} files converted"
                isConverting = false
                logUiProfileSummary(timings)
                
            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
                android.util.Log.e("ConverterMain", "Conversion error", e)
                isScanning = false
                isConverting = false
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note Converter") },
                actions = {
                    IconButton(onClick = onShowSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Configuration status
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.h6
                    )
                    
                    ConfigRow(
                        label = "Input:",
                        value = inputDirUri?.substringAfterLast("%2F") ?: "Not set",
                        isSet = inputDirUri != null
                    )
                    
                    ConfigRow(
                        label = "Output:",
                        value = outputDirUri?.substringAfterLast("%2F") ?: "Not set",
                        isSet = outputDirUri != null
                    )
                    
                    if (lastScanTime > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Last scan: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastScanTime))}",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        settings.resetLastScanTimestamp()
                                        statusMessage = "Scan history reset - all files will be converted next time"
                                    }
                                }
                            ) {
                                Text("Reset", style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                }
            }
            
            // Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.h6
                    )
                    
                    Text(text = statusMessage)
                    
                    if (isConverting) {
                        val (current, total) = currentProgress
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = current.toFloat() / total,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            
            // Convert button
            Button(
                onClick = { scanAndConvert() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isScanning && !isConverting && inputDirUri != null && outputDirUri != null
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isScanning -> "Scanning..."
                        isConverting -> "Converting..."
                        else -> "Scan & Convert"
                    }
                )
            }
            
            // Results list
            if (conversionResults.isNotEmpty()) {
                Text(
                    text = "Results",
                    style = MaterialTheme.typography.h6
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversionResults) { result ->
                        ResultCard(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String, isSet: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.body2)
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
            color = if (isSet) MaterialTheme.colors.primary else Color.Red
        )
    }
}

@Composable
private fun ResultCard(result: ObsidianConverter.ConversionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (result.success) {
            Color.Green.copy(alpha = 0.1f)
        } else {
            Color.Red.copy(alpha = 0.1f)
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.inputFile.name,
                    style = MaterialTheme.typography.body2
                )
                if (!result.success && result.errorMessage != null) {
                    Text(
                        text = result.errorMessage,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error
                    )
                } else if (result.success) {
                    Text(
                        text = "${result.recognizedText?.length ?: 0} characters",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            Icon(
                imageVector = if (result.success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (result.success) Color.Green else Color.Red
            )
        }
    }
}

/**
 * Convert URI string to File.
 * Handles file:// URIs, internal storage (primary:), and external SD cards.
 */
private fun uriToFile(uriString: String): File? {
    return try {
        // Handle simple file:// URIs
        if (uriString.startsWith("file://")) {
            File(Uri.parse(uriString).path ?: return null)
        }
        // Handle SAF tree URIs
        else if (uriString.contains("tree/")) {
            // Extract the tree document ID (e.g., "primary:Documents" or "1234-5678:Notes")
            val treeId = uriString.substringAfter("tree/")
                .substringBefore("/document")
                .replace("%3A", ":")
                .replace("%2F", "/")
            
            // Split into volume and path
            val parts = treeId.split(":", limit = 2)
            if (parts.isEmpty()) return null
            
            val volume = parts[0]
            val path = if (parts.size > 1) parts[1] else ""
            
            // Convert to file path based on volume type
            val basePath = when {
                volume == "primary" -> "/storage/emulated/0"
                volume.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")) -> "/storage/$volume"  // External SD card
                else -> return null  // Unknown volume type
            }
            
            val fullPath = if (path.isNotEmpty()) "$basePath/$path" else basePath
            File(fullPath)
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("ConverterMain", "Failed to convert URI: $uriString", e)
        null
    }
}

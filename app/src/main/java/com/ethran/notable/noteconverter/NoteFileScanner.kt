package com.ethran.notable.noteconverter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Scans a directory tree for .note files modified after a given timestamp.
 */
object NoteFileScanner {
    private const val DEBUG_VERBOSE_SCAN_LOGS = false
    
    data class ScanResult(
        val newFiles: List<File>,
        val modifiedFiles: List<File>
    ) {
        val totalFiles: Int get() = newFiles.size + modifiedFiles.size
        val isEmpty: Boolean get() = totalFiles == 0
    }
    
    data class DocumentScanResult(
        val newFiles: List<DocumentFile>,
        val modifiedFiles: List<DocumentFile>
    ) {
        val totalFiles: Int get() = newFiles.size + modifiedFiles.size
        val isEmpty: Boolean get() = totalFiles == 0
    }
    
    /**
     * Scan using DocumentFile (works with SAF URIs and scoped storage).
     * Recommended for Android 10+.
     */
    fun scanForChangedFilesWithDocumentFile(
        context: Context,
        inputDirUri: Uri,
        lastScanTime: Long
    ): DocumentScanResult {
        android.util.Log.d("NoteScanner", "Scanning DocumentFile URI: $inputDirUri")
        android.util.Log.d("NoteScanner", "Last scan time: $lastScanTime (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastScanTime))})")
        
        val inputDir = DocumentFile.fromTreeUri(context, inputDirUri)
        if (inputDir == null || !inputDir.exists() || !inputDir.isDirectory) {
            android.util.Log.e("NoteScanner", "Invalid DocumentFile or not a directory")
            return DocumentScanResult(emptyList(), emptyList())
        }
        
        android.util.Log.d("NoteScanner", "DocumentFile exists and is directory: ${inputDir.name}")
        
        val newFiles = mutableListOf<DocumentFile>()
        val modifiedFiles = mutableListOf<DocumentFile>()
        var totalNoteFiles = 0
        var totalFilesScanned = 0
        var totalDirectories = 0
        val scanStartNs = System.nanoTime()
        
        // Recursive scan helper
        fun scanDirectory(dir: DocumentFile, depth: Int = 0) {
            totalDirectories++
            val indent = "  ".repeat(depth)
            if (DEBUG_VERBOSE_SCAN_LOGS) {
                android.util.Log.d("NoteScanner", "${indent}Scanning: ${dir.name}")
            }
            
            val files = dir.listFiles()
            if (DEBUG_VERBOSE_SCAN_LOGS) {
                android.util.Log.d("NoteScanner", "${indent}  Found ${files.size} items")
            }
            
            for (file in files) {
                if (file.isDirectory) {
                    scanDirectory(file, depth + 1)
                } else if (file.isFile) {
                    totalFilesScanned++
                    val fileName = file.name ?: ""
                    
                    if (DEBUG_VERBOSE_SCAN_LOGS && totalFilesScanned <= 20) {
                        android.util.Log.d("NoteScanner", "${indent}  File: $fileName")
                    }
                    
                    if (fileName.endsWith(".note", ignoreCase = true)) {
                        totalNoteFiles++
                        val lastModified = file.lastModified()
                        if (DEBUG_VERBOSE_SCAN_LOGS) {
                            val modDateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))
                            android.util.Log.d("NoteScanner", "${indent}  Found .note: $fileName, modified: $modDateStr")
                        }
                        
                        when {
                            lastScanTime == 0L -> {
                                newFiles.add(file)
                                if (DEBUG_VERBOSE_SCAN_LOGS) {
                                    android.util.Log.d("NoteScanner", "${indent}    -> Added as NEW")
                                }
                            }
                            lastModified > lastScanTime -> {
                                if (file.length() > 0) {
                                    modifiedFiles.add(file)
                                    if (DEBUG_VERBOSE_SCAN_LOGS) {
                                        android.util.Log.d("NoteScanner", "${indent}    -> Added as MODIFIED")
                                    }
                                } else if (DEBUG_VERBOSE_SCAN_LOGS) {
                                    android.util.Log.d("NoteScanner", "${indent}    -> Skipped (empty)")
                                }
                            }
                            else -> {
                                if (DEBUG_VERBOSE_SCAN_LOGS) {
                                    android.util.Log.d("NoteScanner", "${indent}    -> Skipped (not modified)")
                                }
                            }
                        }
                    }
                }
            }
        }
        
        scanDirectory(inputDir)
        
        val scanElapsedMs = (System.nanoTime() - scanStartNs) / 1_000_000
        android.util.Log.d(
            "NoteScanner",
            "Scan complete in ${scanElapsedMs}ms: $totalFilesScanned files, $totalDirectories dirs, $totalNoteFiles .note files, ${newFiles.size} new, ${modifiedFiles.size} modified"
        )
        return DocumentScanResult(newFiles, modifiedFiles)
    }
    
    /**
     * Scan a directory (and subdirectories) for .note files changed since [lastScanTime].
     * 
     * @param inputDir Root directory to scan
     * @param lastScanTime Epoch milliseconds of last scan (0 = scan all files)
     * @return List of changed .note files
     */
    fun scanForChangedFiles(
        inputDir: File,
        lastScanTime: Long
    ): ScanResult {
        android.util.Log.d("NoteScanner", "Scanning directory: ${inputDir.absolutePath}")
        android.util.Log.d("NoteScanner", "Last scan time: $lastScanTime (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastScanTime))})")
        
        if (!inputDir.exists()) {
            android.util.Log.e("NoteScanner", "Directory does not exist!")
            return ScanResult(emptyList(), emptyList())
        }
        
        if (!inputDir.isDirectory) {
            android.util.Log.e("NoteScanner", "Path is not a directory!")
            return ScanResult(emptyList(), emptyList())
        }
        
        if (!inputDir.canRead()) {
            android.util.Log.e("NoteScanner", "Directory is not readable! Permission denied.")
            return ScanResult(emptyList(), emptyList())
        }
        
        android.util.Log.d("NoteScanner", "Directory exists, is directory, and is readable")
        
        val newFiles = mutableListOf<File>()
        val modifiedFiles = mutableListOf<File>()
        var totalNoteFiles = 0
        var totalFilesScanned = 0
        var totalDirectories = 0
        
        // First, list immediate children to see what's there
        try {
            val children = inputDir.listFiles()
            android.util.Log.d("NoteScanner", "Directory has ${children?.size ?: 0} immediate children")
            children?.take(10)?.forEach { child ->
                android.util.Log.d("NoteScanner", "  Child: ${child.name} (isDir: ${child.isDirectory}, isFile: ${child.isFile})")
            }
        } catch (e: Exception) {
            android.util.Log.e("NoteScanner", "Failed to list children: ${e.message}")
        }
        
        inputDir.walkTopDown()
            .onEnter { dir ->
                totalDirectories++
                android.util.Log.d("NoteScanner", "Entering directory: ${dir.absolutePath}")
                
                // Check what listFiles returns for this directory
                try {
                    val files = dir.listFiles()
                    android.util.Log.d("NoteScanner", "  listFiles() returned: ${files?.size ?: "null"} items")
                    if (files == null) {
                        android.util.Log.e("NoteScanner", "  listFiles() is NULL - permission denied?")
                    } else if (files.isEmpty()) {
                        android.util.Log.d("NoteScanner", "  Directory is empty")
                    } else {
                        files.take(5).forEach { f ->
                            android.util.Log.d("NoteScanner", "    ${f.name} (${if (f.isFile) "file" else "dir"})")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NoteScanner", "  Error listing: ${e.message}")
                }
                
                true  // Continue into subdirectories
            }
            .filter { it.isFile }
            .forEach { file ->
                totalFilesScanned++
                if (totalFilesScanned <= 20) {  // Log first 20 files to avoid spam
                    android.util.Log.d("NoteScanner", "File found: ${file.name}")
                }
                
                if (file.name.endsWith(".note", ignoreCase = true)) {
                    totalNoteFiles++
                    val lastModified = file.lastModified()
                    val modDateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastModified))
                    
                    android.util.Log.d("NoteScanner", "Found .note file: ${file.name}, modified: $modDateStr ($lastModified)")
                    
                    when {
                        lastScanTime == 0L -> {
                            newFiles.add(file)  // First scan, treat all as new
                            android.util.Log.d("NoteScanner", "  -> Added as NEW (first scan)")
                        }
                        lastModified > lastScanTime -> {
                            // File was modified after last scan
                            if (file.length() > 0) {  // Skip empty files
                                modifiedFiles.add(file)
                                android.util.Log.d("NoteScanner", "  -> Added as MODIFIED")
                            } else {
                                android.util.Log.d("NoteScanner", "  -> Skipped (empty file)")
                            }
                        }
                        else -> {
                            android.util.Log.d("NoteScanner", "  -> Skipped (not modified since last scan)")
                        }
                    }
                }
            }
        
        android.util.Log.d("NoteScanner", "Scan complete: $totalFilesScanned files scanned, $totalDirectories directories, $totalNoteFiles .note files, ${newFiles.size} new, ${modifiedFiles.size} modified")
        return ScanResult(newFiles, modifiedFiles)
    }
    
    /**
     * Get all .note files in a directory tree (no filtering).
     */
    fun getAllNoteFiles(inputDir: File): List<File> {
        if (!inputDir.exists() || !inputDir.isDirectory) {
            return emptyList()
        }
        
        return inputDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".note", ignoreCase = true) }
            .toList()
    }
    
    /**
     * Generate output filename for a .note file.
     * Preserves original name, replaces .note with .md
     */
    fun generateOutputFilename(noteFile: File): String {
        val baseName = noteFile.nameWithoutExtension
        return "$baseName.md"
    }
}

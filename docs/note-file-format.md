# Onyx .note File Parser & Converter

Convert native Onyx Boox `.note` files to Markdown using MyScript handwriting recognition.

## Overview

This implementation adds the ability to import and convert `.note` files (created by Onyx's built-in Notes app) into markdown text files using the device's MyScript HWR engine.

## Components

### 1. **OnyxNoteParser.kt**
Parses the `.note` ZIP archive format:
- Extracts protobuf metadata (document info, page dimensions)
- Parses shape metadata (stroke bounding boxes, pen settings)
- Decodes binary point data from chunk table entries (coordinates, pressure, timestamps)
- Converts to Notable's `Stroke` format

**Location**: `app/src/main/java/com/ethran/notable/io/OnyxNoteParser.kt`

### 2. **NoteToMarkdownConverter.kt**
Orchestrates the conversion process:
- Parses `.note` file using `OnyxNoteParser`
- Calls `OnyxHWREngine` for handwriting recognition
- Generates markdown with YAML frontmatter
- Writes output file

**Location**: `app/src/main/java/com/ethran/notable/io/NoteToMarkdownConverter.kt`

### 3. **OnyxHWREngine.kt** (existing)
Interfaces with Onyx's MyScript service for handwriting recognition.

**Location**: `app/src/main/java/com/ethran/notable/io/OnyxHWREngine.kt`

## Usage

### Basic Conversion

```kotlin
import com.ethran.notable.io.NoteToMarkdownConverter
import android.net.Uri
import java.io.File

// In a ViewModel or Repository (coroutine context)
suspend fun convertNoteFile(noteUri: Uri) {
    val converter = NoteToMarkdownConverter(context)
    val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "converted")
    outputDir.mkdirs()
    
    val result = converter.convertToMarkdown(noteUri, outputDir)
    
    if (result.success) {
        println("Converted to: ${result.outputFile?.absolutePath}")
        println("Recognized text: ${result.recognizedText}")
    } else {
        println("Conversion failed: ${result.errorMessage}")
    }
}
```

### Batch Conversion

```kotlin
suspend fun convertMultipleNotes(noteUris: List<Uri>) {
    val converter = NoteToMarkdownConverter(context)
    val outputDir = File(documentsDir, "converted")
    
    val results = converter.convertMultiple(noteUris, outputDir)
    
    val successCount = results.count { it.success }
    println("Converted $successCount of ${results.size} files")
}
```

### Direct Parsing (without recognition)

```kotlin
import com.ethran.notable.io.OnyxNoteParser

// Just parse the .note structure
context.contentResolver.openInputStream(noteUri)?.use { stream ->
    val noteDoc = OnyxNoteParser.parseNoteFile(stream)
    
    if (noteDoc != null) {
        println("Title: ${noteDoc.title}")
        println("Page size: ${noteDoc.pageWidth}x${noteDoc.pageHeight}")
        println("Strokes: ${noteDoc.strokes.size}")
        
        // Access individual strokes
        noteDoc.strokes.forEach { stroke ->
            println("  Stroke ${stroke.id}: ${stroke.points.size} points")
        }
    }
}
```

## Integration Options

### Option A: Add to Notable App

Add a new import option in `ImportEngine.kt`:

```kotlin
// In ImportEngine.import()
when {
    fileName.endsWith(".note", ignoreCase = true) -> {
        handleImportNote(uri, options)
    }
    // ... existing formats
}

private suspend fun handleImportNote(uri: Uri, options: ImportOptions): String {
    val converter = NoteToMarkdownConverter(context)
    val tempDir = File(context.cacheDir, "note_import")
    tempDir.mkdirs()
    
    val result = converter.convertToMarkdown(uri, tempDir)
    
    if (result.success && result.outputFile != null) {
        // Create a new Notable page with the recognized text
        val page = Page(/* ... */)
        pageRepo.insert(page)
        return "Imported ${result.recognizedText?.length} characters"
    } else {
        throw Exception(result.errorMessage ?: "Import failed")
    }
}
```

### Option B: Standalone Converter App

Create a minimal app with:
1. File picker for `.note` files
2. Progress indicator during recognition
3. Output directory selector
4. List view of converted files

Minimal `MainActivity.kt`:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private val pickNoteLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                convertNotes(uris)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            NoteConverterTheme {
                ConverterScreen(
                    onPickFiles = { pickNoteLauncher.launch("*/*") }
                )
            }
        }
    }
    
    private suspend fun convertNotes(uris: List<Uri>) {
        val converter = NoteToMarkdownConverter(this)
        val outputDir = File(getExternalFilesDir(null), "markdown")
        
        val results = converter.convertMultiple(uris, outputDir)
        
        // Show results in UI
        results.forEach { result ->
            if (result.success) {
                Toast.makeText(this, "Converted: ${result.outputFile?.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

## File Format Details

### .note Archive Structure

```
Notebook-1.note (ZIP)
├── a8e4ea1f6f6848cd99ccf081d108bbaa/
│   ├── note/pb/note_info          # Protobuf: document metadata, pen settings
│   ├── shape/
│   │   └── *.zip                  # Stroke metadata (bounding boxes)
│   ├── point/
│   │   └── *#points               # Binary point data (coordinates)
│   ├── template/json/
│   │   └── *.template_json        # Page template (background)
│   └── virtual/
│       ├── doc/pb/*               # Document structure
│       └── page/pb/*              # Page info
```

### Points File Format (Binary)

```
[Container]
    - Header/prefix (format-dependent)
    - Point chunk payloads
    - Tail chunk index table
    - Last 4 bytes: big-endian offset to chunk index table start

[Tail Chunk Index Table]
    - Repeated entries (44 bytes each):
        - Stroke UUID (36 bytes ASCII)
        - Chunk offset (4 bytes, big-endian uint32)
        - Chunk size (4 bytes, big-endian uint32)

[Chunk Payload]
    - Chunk header (4 bytes)
    - Repeating point records (16 bytes each):
        - X coordinate (4 bytes, big-endian float32)
        - Y coordinate (4 bytes, big-endian float32)
        - Timestamp delta (2 bytes, big-endian uint16)
        - Pressure (2 bytes, big-endian uint16, 0-4095 range)
        - Sequence/index (4 bytes, big-endian uint32)
```

### Parser Strategy (Current)

- Primary path (modern format):
    - Read table start offset from last 4 bytes.
    - Parse UUID/offset/size entries from tail table.
    - Decode points from each chunk using 16-byte records.
    - Match chunk stroke UUIDs to shape metadata IDs.
- Fallback path (legacy/unknown variants):
    - Use legacy flat parser to avoid hard failure on older files.

## Requirements

- **Device**: Must run on Onyx Boox hardware (MyScript service)
- **Android**: minSdk 29 (Android 10)
- **Permissions**: `READ_EXTERNAL_STORAGE` for file access

## Testing

### With Sample File

```kotlin
// Use the provided Notebook-1.note sample
val sampleUri = Uri.parse("file:///path/to/Notebook-1.note")
val result = converter.convertToMarkdown(sampleUri, outputDir)
```

### Output Example

```markdown
---
title: Notebook-1
created: 2026-03-16 09:00:00
source: onyx-note
---

# Notebook-1

[Recognized handwriting text appears here]
```

## Troubleshooting

### "MyScript HWR service unavailable"
- Ensure running on an Onyx Boox device
- Check that `com.onyx.android.ksync` service is running
- Try binding manually: `OnyxHWREngine.bindAndAwait(context)`

### "Failed to parse .note file"
- Verify the file is a valid .note ZIP archive
- Check file isn't corrupted: `unzip -t Notebook-1.note`
- Ensure file was created by Onyx Notes app (not third-party)

### Empty recognition results
- Strokes may be too short or ambiguous
- Try with longer, clearer handwriting
- Check stroke count: `noteDoc.strokes.size`

## Limitations

- **Page dimensions**: Currently assumes single-page notes
- **Multi-page notes**: Processes first page only (extend using `Notebook` structure)
- **Shapes/text boxes**: Only processes freehand strokes, not typed text or shapes
- **Images**: Embedded images are not extracted
- **Variant handling**: Different firmware may emit different point payload variants; parser includes fallback mode for compatibility

## Future Enhancements

- [ ] Multi-page support (iterate through all pages in notebook)
- [ ] Preserve metadata (creation date, author, tags)
- [ ] Image extraction from resource folder
- [ ] Shape recognition (rectangles, arrows)
- [ ] OCR for typed text boxes
- [ ] Custom markdown templates

## References

- **Existing parsers**: `XoppFile.kt` (Xournal++ format)
- **Import framework**: `ImportEngine.kt`
- **Recognition**: `OnyxHWREngine.kt`, `InboxSyncEngine.kt`
- **Notebook analysis**: `NoteFileParser.ipynb` (Jupyter notebook with detailed format documentation)

---

**Maintainer**: Joshua Pham (based on Ethran/notable)  
**License**: Apache 2.0 (same as Notable)

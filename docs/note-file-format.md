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

## Native .note Format Specification

### Container Format

The `.note` file is a **ZIP archive** containing metadata, shape information, and point data:

```
note-file.note (ZIP archive)
├── note/pb/note_info              # Protobuf with embedded JSON document metadata
├── shape/[page-id]#[timestamp].zip  # Nested ZIP with stroke metadata (bounding boxes)
├── point/[page-id]/#points          # Binary point data for all strokes
└── [stash/...]                    # Historical/deleted content (ignored by parser)
```

### Metadata Section (note_info)

The `note/pb/note_info` file is a Protobuf message containing embedded JSON strings:

```json
{
  "title": "Note Title",
  "pageInfo": {
    "width": 1860.0,
    "height": 2480.0
  }
}
```

**Fields:**
- `title` (string): Document/note name
- `pageInfo.width` (double): Page width in pixels (default: 1860)
- `pageInfo.height` (double): Page height in pixels (default: 2480)

### Shape Metadata Section (stroke metadata)

Located in `shape/[page-id]#[timestamp].zip`, this nested ZIP contains protobuf with stroke metadata extracted via JSON pattern matching:

**UUID patterns (36-byte ASCII strings):**
```
[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}
```

Each stroke is identified by two UUIDs:
1. **strokeId**: Unique identifier for the stroke
2. **pointsId**: Reference to point data in the point file

**Bounding box format (JSON objects):**
```json
{
  "bottom": 1234.5,
  "empty": false,
  "left": 100.0,
  "right": 500.0,
  "stability": 1,
  "top": 200.0
}
```

### Point Data Section (stroke points) — Binary Format

Located in `point/[page-id]/#points`. Uses a **chunk-based table lookup system**:

#### File Layout

```
[0 bytes to tableOffset)
    Stroke data chunks (variable size)
    
[tableOffset to EOF-4)
    Point chunk entry table
    - Repeated entries: 44 bytes each
      - Bytes 0-35: Stroke ID (36-char ASCII UUID)
      - Bytes 36-39: Chunk offset (big-endian int32)
      - Bytes 40-43: Chunk size (big-endian int32)
    
[EOF-4 to EOF)
    Table start offset (big-endian int32)
```

#### Chunk Structure

Each data chunk contains:
```
[0-3]:    4-byte header (purpose unknown)
[4+):     Point records (repeated)
```

#### Point Record Format

Each point is **16 bytes, big-endian**:

```
[0-3]:    x coordinate (float32)
[4-7]:    y coordinate (float32)
[8-9]:    dt - delta time in milliseconds (uint16)
[10-11]:  pressure - raw value 0-4095 (uint16)
[12-15]:  sequence number (uint32, unused)
```

**Total records:** `(chunk_size - 4) / 16`

#### Legacy Format (Fallback)

For older files where chunk parsing fails:

```
[0-87]:     Header/metadata (skipped)
[88+):      Point records
    [0-3]:  x (float32, big-endian)
    [4-7]:  y (float32, big-endian)
    [8-10]: 3-byte timestamp delta (big-endian)
    [11-12]:pressure (int16, big-endian, divide by 4095 for 0-1 range)
    [13-16]:sequence (int32, unused)
```

### Parser Strategy

**Primary path (modern format):**
1. Read table start offset from last 4 bytes (big-endian int32)
2. Validate offset range
3. Parse UUID/offset/size entries (44 bytes each) from tail table
4. Extract each chunk using offset and size
5. Decode point records (16 bytes each) from chunks
6. Match chunk stroke UUIDs to shape metadata IDs

**Fallback path (legacy/unknown variants):**
- Use legacy flat parser to avoid hard failure on older files
- Triggered if modern parser returns no chunks

### Data Type Specifications

#### StrokePoint (Internal representation)
```kotlin
data class StrokePoint(
    val x: Float,                    // Absolute x coordinate in pixels
    val y: Float,                    // Absolute y coordinate in pixels
    val pressure: Float? = null,     // Normalized 0-1 (pressureRaw / 4095)
    val tiltX: Int? = null,          // Tilt in degrees (-90 to 90, not in .note format)
    val tiltY: Int? = null,          
    val dt: UShort? = null           // Delta time in milliseconds since first point
)
```

#### Stroke (Internal representation)
```kotlin
data class Stroke(
    val id: String,                  // UUID from metadata
    val size: Float,                 // Pen width in pixels (default: 4.72)
    val pen: Pen,                    // Pen type (default: BALLPEN)
    val color: Int,                  // ARGB color (default: black, 0xFF000000)
    val maxPressure: Int,            // Pressure range max (default: 4095)
    val top: Float,                  // Bounding box top
    val bottom: Float,               // Bounding box bottom
    val left: Float,                 // Bounding box left
    val right: Float,                // Bounding box right
    val points: List<StrokePoint>,   // All coordinate and pressure points
    val pageId: String,              // Reference to containing page
    val createdAt: Date              // Import timestamp
)
```

### Byte Order & Encoding

- **Point data**: Big-endian (network byte order)
- **Timestamps**: Unix milliseconds
- **Coordinates**: Pixel units
- **Pressure**: Raw 0-4095 scale (divide by 4095 for normalized 0-1)
- **String encoding**: ASCII (UUIDs in tables), UTF-8 (metadata in protobuf)

### Default Values

| Parameter | Value | Notes |
|-----------|-------|-------|
| DPI | 320 | pixels per inch on standard Boox devices |
| Pen width | 4.7244096 | pixels (ballpoint pen) |
| Color | Black (0xFF000000) | ARGB format |
| Max pressure | 4095 | Physical stylus range |
| Page width | 1860 px | ≈ 5.8 inches at 320 DPI |
| Page height | 2480 px | ≈ 7.75 inches at 320 DPI |
| Pen type | BALLPEN | All strokes default to ballpoint |

### Known Limitations

1. **No tilt data**: Native format doesn't store pen tilt (always null in StrokePoint)
2. **Single pen type**: All strokes imported as ballpoint; original pen type not preserved
3. **No layer support**: Strokes flattened to single page
4. **Monochrome**: All strokes imported as black; color info not in point data
5. **Pressure normalization**: Raw values assumed 0-4095; may vary by device
6. **Legacy format complexity**: Older files require fallback binary parser

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

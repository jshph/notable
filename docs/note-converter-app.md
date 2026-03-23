# Note Converter - Onyx to Obsidian

A simple Android app for **Onyx Boox devices** that automatically converts native `.note` files to Obsidian-compatible Markdown.

## Features

✅ **Directory Configuration**
- Choose input directory (where .note files are located)
- Choose output directory (where .md files will be saved)
- Settings persist across app restarts

✅ **Smart Scanning**
- Recursively scans input directory and subdirectories
- Tracks file changes since last scan
- Only processes new or modified files

✅ **MyScript Recognition**
- Uses Onyx's built-in MyScript HWR engine
- Converts handwritten strokes to text
- Works offline (no cloud API needed)

✅ **Obsidian Compatible**
- Generates YAML frontmatter with metadata
- Includes `status/todo` tag automatically
- Preserves original filename

## Installation

### Add to Notable App

The converter is already integrated into the Notable codebase. To use:

1. **Build the app**:
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on your Onyx Boox device**:
   ```bash
   ./gradlew installDebug
   ```

3. **Launch the converter**:
   - Add a menu item in Notable's main screen to launch `NoteConverterActivity`
   - Or run directly: `adb shell am start -n com.ethran.notable/.noteconverter.NoteConverterActivity`

### Standalone App (Optional)

To create a separate converter app:

1. Update `AndroidManifest.xml` to set `NoteConverterActivity` as the launcher:
   ```xml
   <activity
       android:name=".noteconverter.NoteConverterActivity"
       android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.LAUNCHER" />
       </intent-filter>
   </activity>
   ```

2. Build and install

## Usage

### First-Time Setup

1. **Open Settings** (⚙️ icon)
2. **Choose Input Directory**:
   - Tap "Choose Input Directory"
   - Navigate to your .note files folder (e.g., `/storage/emulated/0/note/`)
3. **Choose Output Directory**:
   - Tap "Choose Output Directory"
   - Navigate to your Obsidian vault inbox (e.g., `/storage/emulated/0/Documents/ObsidianVault/inbox/`)
4. **Close Settings**

### Converting Files

1. **Tap "Scan & Convert"**
2. The app will:
   - Scan input directory for .note files
   - Compare against last scan timestamp
   - Convert changed files using MyScript
   - Save markdown files to output directory
3. **View Results** - Each file shows:
   - ✅ Green = success (with character count)
   - ❌ Red = failed (with error message)

### Output Format

Each converted file includes:

```markdown
---
title: My Note
created: 2026-03-16T09:00:00
modified: 2026-03-16T14:30:00
tags:
  - status/todo
source: onyx-note
---

# My Note

[Recognized handwriting text appears here]
```

## File Locations

### Code Structure

```
app/src/main/java/com/ethran/notable/noteconverter/
├── ConverterSettings.kt          # DataStore for directory paths
├── NoteFileScanner.kt            # Scan and track file changes
├── ObsidianConverter.kt          # Main conversion logic
├── SettingsScreen.kt             # Settings UI (directory pickers)
├── ConverterMainScreen.kt        # Main UI (scan button, results)
└── NoteConverterActivity.kt      # Entry point
```

### Dependencies (Already in Notable)

- `OnyxNoteParser.kt` - Parse .note ZIP format
- `OnyxHWREngine.kt` - MyScript handwriting recognition
- DataStore (preferences)
- Jetpack Compose UI
- Hilt dependency injection

## Permissions

The app requires:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

These are already declared in Notable's manifest.

## Troubleshooting

### "Please configure directories in Settings"
- Open Settings and choose both input and output directories
- Make sure to grant storage permissions

### "MyScript HWR service unavailable"
- This app **only works on Onyx Boox devices**
- Ensure MyScript service is running: `adb shell ps -A | grep ksync`
- Try restarting the device

### "No new or modified files found"
- Check that .note files exist in input directory
- Files are compared against last scan time
- First scan treats all files as new

### "Invalid directory paths"
- SAF content:// URIs may not convert properly
- Use direct file paths when possible (e.g., `/storage/emulated/0/note/`)
- Check logcat for URI parsing errors: `adb logcat | grep Converter`

### Conversion fails for specific files
- File may be corrupted or empty
- Check file size: `ls -lh input_dir/*.note`
- Try opening in Onyx Notes app first
- Check logcat for detailed error: `adb logcat -s ObsidianConverter:*`

## Advanced Usage

### Batch Processing

The app automatically processes all changed files in one operation. To force re-conversion:

1. Clear app data (Settings > Apps > Notable > Clear Data)
2. Run scan again (treats all files as new)

### Custom Tags

To modify the default `status/todo` tag:

Edit `ObsidianConverter.kt`:

```kotlin
private fun generateObsidianMarkdown(...): String {
    // ...
    appendLine("tags:")
    appendLine("  - status/todo")        // Change this
    appendLine("  - your/custom/tag")    // Add more tags
    // ...
}
```

### Integration with Notable

Add a menu item in Notable's main screen:

```kotlin
// In NotableNavHost.kt or main menu
Button(
    onClick = {
        val intent = Intent(context, NoteConverterActivity::class.java)
        context.startActivity(intent)
    }
) {
    Text("Convert Notes")
}
```

## Performance

- **Parsing**: ~50-100ms per file
- **Recognition**: ~300-500ms per page (MyScript)
- **Total**: ~400-600ms per file

Example: Converting 10 files takes ~5-6 seconds.

## Limitations

- **Single page**: Multi-page notebooks convert first page only
- **Handwriting only**: Typed text and shapes not extracted
- **No images**: Embedded images not copied
- **Linear processing**: Files converted sequentially (not parallel)

## Future Enhancements

- [ ] Background worker (WorkManager) for automatic scanning
- [ ] Multi-page notebook support
- [ ] Image extraction from .note files
- [ ] Custom tag configuration in UI
- [ ] Export format options (plain markdown, org-mode, etc.)
- [ ] Conflict resolution (skip/overwrite existing .md files)

## Contributing

To extend this converter:

1. **Add new output formats**: Modify `generateObsidianMarkdown()` in `ObsidianConverter.kt`
2. **Improve scanning**: Edit `NoteFileScanner.kt` for advanced filtering
3. **Add scheduling**: Use WorkManager for periodic background scans
4. **Better SAF support**: Improve `uriToFile()` in `ConverterMainScreen.kt`

## License

Apache 2.0 (same as Notable)

---

**Author**: Based on Notable by Ethran  
**MyScript Integration**: Uses Onyx firmware's built-in HWR engine  
**Parser**: Custom `.note` format reverse-engineering

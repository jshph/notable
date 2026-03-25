# Note Converter - Quick Start Guide

## What It Does

Converts Onyx Boox `.note` files → Obsidian-compatible Markdown files

```
Input:  /storage/emulated/0/note/MyNote.note
Output: /storage/emulated/0/Obsidian/inbox/MyNote.md
```

## How to Use

### 1. Configure Directories (First Time Only)

<img src="https://via.placeholder.com/300x500/1976D2/FFFFFF?text=Settings+Screen" width="250"/>

1. Tap ⚙️ Settings
2. Choose Input Directory (where .note files live)
3. Choose Output Directory (Obsidian inbox)
4. Tap "Close"

### 2. Convert Files

<img src="https://via.placeholder.com/300x500/4CAF50/FFFFFF?text=Main+Screen" width="250"/>

1. Tap **"Scan & Convert"**
2. Wait for processing
3. ✅ View results

That's it!

## What Gets Created

**Before** (`MyNote.note`):
- Binary file with handwritten strokes
- Created in Onyx Notes app

**After** (`MyNote.md`):
```markdown
---
title: MyNote
created: 2026-03-16T09:00:00
modified: 2026-03-16T14:30:00
tags:
  - status/todo
source: onyx-note
---

# MyNote

Your handwritten text appears here!
```

## Architecture

```
┌─────────────────────────────────────────────┐
│         NoteConverterActivity               │
│  (Main entry point - Compose UI)            │
└─────────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
┌───────▼──────────┐   ┌────────▼─────────┐
│ ConverterMain    │   │ SettingsScreen   │
│ Screen           │   │ (Dir Pickers)    │
└───────┬──────────┘   └──────────────────┘
        │                       │
        │              ┌────────▼─────────┐
        │              │ ConverterSettings│
        │              │ (DataStore)      │
        │              └──────────────────┘
        │
┌───────▼──────────┐
│ ObsidianConverter│  ← Main conversion logic
└───────┬──────────┘
        │
        ├──► OnyxNoteParser  (Parse .note ZIP)
        │
        ├──► OnyxHWREngine   (MyScript recognition)
        │
        ├──► NoteFileScanner (Find changed files)
        │
        └──► Markdown        (Generate output)
```

## File Flow

```
1. User taps "Scan & Convert"
   │
2. NoteFileScanner
   │  - Walks input directory tree
   │  - Finds .note files modified since last scan
   │
3. For each file:
   │
   ├─► OnyxNoteParser
   │   └─► Extracts strokes from ZIP
   │
   ├─► OnyxHWREngine
   │   └─► Recognizes handwriting via MyScript
   │
   └─► ObsidianConverter
       └─► Generates markdown with YAML
   │
4. Write to output directory
   │
5. Update last scan timestamp
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Smart Scanning** | Only processes files changed since last run |
| **Recursive** | Scans subdirectories automatically |
| **Offline** | Uses Onyx's MyScript (no internet needed) |
| **Obsidian Ready** | YAML frontmatter with tags |
| **Preserves Names** | `MyNote.note` → `MyNote.md` |
| **Batch Processing** | Converts multiple files in one go |

## Requirements

- ✅ Onyx Boox device (for MyScript)
- ✅ Android 10+ (minSdk 29)
- ✅ Storage permissions
- ✅ Notable app (or standalone build)

## Example Conversion

**Input:** Handwritten note with "Hello World" and "TODO: Buy milk"

**Output:** 
```markdown
---
title: shopping-list
created: 2026-03-16T09:00:00
modified: 2026-03-16T14:30:00
tags:
  - status/todo
source: onyx-note
---

# shopping-list

Hello World

TODO: Buy milk
```

## Terminal Commands (Development)

```bash
# Build and install
./gradlew installDebug

# Launch converter
adb shell am start -n com.ethran.notable/.noteconverter.NoteConverterActivity

# View logs
adb logcat -s ObsidianConverter:* OnyxNoteParser:* NoteConverter:*

# Check file permissions
adb shell ls -la /storage/emulated/0/note/

# Test conversion manually
adb shell
cd /storage/emulated/0/note
ls -lh *.note
```

## Troubleshooting Quick Reference

| Problem | Solution |
|---------|----------|
| "Please configure directories" | Open Settings, choose both directories |
| "HWR service unavailable" | Must run on Onyx Boox hardware |
| "No files found" | Check input directory path, look in subdirs |
| Empty .md files | Strokes may not be recognized (too short/unclear) |
| App crashes | Check logcat, ensure permissions granted |

## Next Steps

1. ✅ Configure directories in Settings
2. ✅ Place .note files in input directory
3. ✅ Tap "Scan & Convert"
4. ✅ Check output directory for .md files
5. ✅ Import to Obsidian

---

**Full Documentation**: See [note-converter-app.md](note-converter-app.md)

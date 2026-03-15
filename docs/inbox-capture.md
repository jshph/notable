# Inbox Capture

Inbox Capture replaces Quick Pages with a templated note-taking surface that syncs handwritten notes to an Obsidian vault as markdown files.

## Concept

The user creates an Inbox Capture page, writes tags in the frontmatter zone and note content below the divider. On page exit, the handwriting is recognized via ML Kit Digital Ink Recognition and written as a markdown file to the configured Obsidian inbox folder.

## Template Layout

The inbox page has three zones, rendered as a native background type (`"inbox"`):

```
+------------------------------------------+
|  created:  2026-03-08        (auto-filled)|
|  tags:  [handwrite tags here]            |
|  ~~~~~~~~~ gray background ~~~~~~~~~~~~  |
+==========================================+  <-- divider (Y=370)
|                                          |
|  [handwrite note content here]           |
|  ______________________________________  |
|  ______________________________________  |  <-- lined area
|  ______________________________________  |
+------------------------------------------+
```

### Zone constants (page coordinates)

| Constant | Value | Purpose |
|---|---|---|
| `INBOX_CREATED_Y` | 80 | Y position of "created:" label |
| `INBOX_TAGS_LABEL_Y` | 170 | Y position of "tags:" label |
| `INBOX_DIVIDER_Y` | 370 | Y position of the divider line |
| `INBOX_CONTENT_START_Y` | 400 | Where content lines begin |

### Stroke classification

Strokes are classified by their bounding box position relative to the divider:
- **Tags zone**: strokes with `top < INBOX_DIVIDER_Y`
- **Content zone**: strokes with `top >= INBOX_CONTENT_START_Y`

## On-Exit Sync Flow

When the user exits an inbox capture page (navigates back):

1. **Collect strokes** from the page database
2. **Classify strokes** into tags zone vs content zone by Y position
3. **Recognize handwriting** using ML Kit Digital Ink Recognition:
   - Tags zone strokes -> recognized as tag text
   - Content zone strokes -> recognized as note body text
4. **Generate markdown** with YAML frontmatter:
   ```markdown
   ---
   created: 2026-03-08
   tags:
     - relationships
     - pkm
   ---

   some content here
   ```
5. **Write the file** to the Obsidian inbox folder:
   - Default path: `Documents/primary/inbox/`
   - Filename: `YYYY-MM-DD-HH-mm-ss.md`
   - Configurable via folder picker in Notable settings
6. **Delete the Notable page** after successful sync

## Multi-Page Support

An inbox capture can span multiple pages (via notebook). All pages are processed in order, with content strokes from each page appended to the markdown body.

## Technical Dependencies

- **ML Kit Digital Ink Recognition** (`com.google.mlkit:digital-ink-recognition:19.0.0`)
  - Package: `com.google.mlkit.vision.digitalink.recognition`
  - ~20MB model download (one-time, on-device, offline after download)
  - ~100ms recognition per line of text on NoteAir5C
- **Onyx Pen SDK** for stroke capture (existing)
- **Android file system** for writing to Documents folder (existing MANAGE_EXTERNAL_STORAGE permission)

## Settings

- **Obsidian vault path**: folder picker, defaults to `Documents/primary/inbox/`
- Stored in `AppSettings` / `GlobalAppSettings`

## Files Modified/Created

### New files
- `app/src/main/java/com/ethran/notable/ui/views/InkTestView.kt` — ML Kit test screen (debug)

### Modified files
- `app/build.gradle` — added ML Kit dependency
- `app/src/main/java/com/ethran/notable/editor/drawing/backgrounds.kt` — added `drawInboxBg()` and inbox zone constants
- `app/src/main/java/com/ethran/notable/data/AppRepository.kt` — quick page creation uses `"inbox"` background
- `app/src/main/java/com/ethran/notable/data/model/BackgroundType.kt` — (no changes yet, uses existing Native type)
- `app/src/main/java/com/ethran/notable/navigation/NotableNavHost.kt` — added ink test route
- `app/src/main/java/com/ethran/notable/navigation/NotableNavigator.kt` — added ink test navigation
- `app/src/main/java/com/ethran/notable/ui/views/Settings.kt` — threaded ink test button
- `app/src/main/java/com/ethran/notable/ui/components/DebugSettings.kt` — added ink test toggle

### TODO (next steps)
- [ ] Implement on-exit sync flow in EditorView.kt
- [ ] Add stroke classification by zone (tags vs content)
- [ ] Integrate ML Kit recognition with existing stroke data (convert Onyx TouchPoints to ML Kit Ink)
- [ ] Generate markdown with YAML frontmatter
- [ ] Write markdown file to Obsidian inbox folder
- [ ] Add Obsidian vault folder picker in settings
- [ ] Delete Notable page after successful sync
- [ ] Handle multi-page inbox captures

# Notable for Obsidian

A handwriting capture surface for [Obsidian](https://obsidian.md), built for [Onyx Boox](https://www.boox.com/) e-ink tablets.

Fork of [Ethran/notable](https://github.com/Ethran/notable). The upstream project is a general-purpose note-taking app for Boox devices. This fork strips it down to a single purpose: **capture handwritten thoughts and sync them into your Obsidian vault as markdown**.

---

## Why this exists

Obsidian is great for organizing knowledge, but it assumes a keyboard. If you think with a pen — sketching, scrawling, drawing connections — there's a gap between the page and the graph. The Boox tablet is the best e-ink hardware for writing, and Notable is the best open-source app for that hardware. This fork bridges the two.

The core idea is **atomic capture**: each page is one thought. You write it, tag it with tags from your vault, and hit save. Handwriting recognition converts your strokes to text. The result lands in your Obsidian inbox as a markdown file with frontmatter, ready to be processed in your normal workflow.

The tablet becomes a dedicated input device for your second brain. No file management, no notebooks, no folders. Just capture and sync.

---

## What changed from upstream

Everything here serves the Obsidian capture loop. Changes fall into three categories:

### Capture workflow
- **Atomic capture model** — every new page is a capture. No notebooks, folders, or file organization on the device. The library is a flat grid of captures.
- **Tag UI** — collapsible toolbar at the top with tag pills pulled from your Obsidian vault (ranked by frequency and recency), text search with autocomplete. Tags flow into the markdown frontmatter on sync.
- **Annotation boxes** — draw a box over handwritten text to mark it as a `[[wiki link]]` or `#tag`. These render as bracket/hash overlays on the canvas and get recognized inline during sync.
- **Save & exit** — one button. Returns to the library instantly; sync happens in background.

### Handwriting recognition
- **Onyx HWR (MyScript)** — uses the Boox firmware's built-in MyScript engine via AIDL IPC. Replaced Google ML Kit, which had poor accuracy. MyScript is significantly better, especially for short phrases and mixed content.
- **Annotation-aware recognition** — strokes inside `[[]]` boxes become wiki links, strokes inside `#` boxes become tags. Recognition diffs full-page vs. non-annotated strokes for better accuracy (recognizing isolated short words like "pkm" in a box is harder than letting full-page context disambiguate).
- **Line segmentation** — multi-line captures are clustered by vertical position and recognized line-by-line with sequential context feeding forward.

### Obsidian sync
- **Markdown output** — captures sync as `.md` files to a configurable folder in your vault with YAML frontmatter (`created`, `tags`).
- **Vault tag scanning** — parses tags from existing markdown files in your inbox folder so you can reuse your vocabulary.
- **Background sync** — recognition and file writing happen in a coroutine after you've already navigated away. A "Syncing..." overlay shows on pages still processing.

### Editor changes
- **Left-edge sidebar** — all tools moved from a bottom toolbar to a vertical sidebar on the left. Pen picker and eraser flyouts appear to the right. Keeps the writing area unobstructed.
- **Jetpack Ink API** — replaced ~200 lines of custom stroke rendering with `androidx.ink`'s `CanvasStrokeRenderer`.
- **Fountain pen default** — with a sqrt pressure curve so light strokes are visible and heavy strokes feel natural on e-ink.

---

## UX principles

These aren't stated goals — they're patterns visible in every commit:

- **Speed is non-negotiable.** Sync is background. Navigation is instant. Tag suggestions are cached. Nothing blocks the pen.
- **Minimal chrome.** If it's not capture, tagging, or saving, it's removed. No folders, no notebooks, no import/export UI. The library is a grid. The editor is a page.
- **Pen-first interaction.** Annotations are drawn, not typed. The sidebar stays out of the way. The default pen feels good at first touch.
- **Obsidian is the system of record.** The tablet captures; Obsidian organizes. No duplicate taxonomy, no competing folder structures. Tags come from the vault and go back to the vault.

---

## Setup

### Prerequisites
- An Onyx Boox tablet (pen input requires Onyx hardware)
- An Obsidian vault accessible on the device (e.g. via Syncthing, Dropsync, or USB)

### Install
Build from source (see [CLAUDE.md](./CLAUDE.md) for full build instructions):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Configure
1. Open Settings in the app
2. Set your **vault path** to the Obsidian vault directory on the device
3. Set the **inbox folder** where captures should land (e.g. `inbox/`)
4. Start capturing

---

## How it works

1. Tap **New Capture** in the library
2. Write with the pen. Tag by selecting existing vault tags from the toolbar, or typing new ones.
3. Optionally draw annotation boxes: tap `[[` or `#` in the sidebar, then draw a rectangle over text to mark it as a wiki link or tag
4. Tap **Save & Exit**
5. The app navigates back immediately. In the background, strokes are recognized, annotations are resolved, and a markdown file is written to your vault inbox.

The resulting file looks like:

```markdown
---
created: "[[2025-01-15]]"
tags:
  - meeting-notes
  - project-alpha
---

discussed the [[API redesign]] with the team
need to revisit #authentication flow before launch
```

---

## Upstream features retained

This fork keeps the core Notable functionality that matters for capture:

- Low-latency Onyx Pen SDK input (`TouchHelper` + `RawInputCallback`)
- E-ink optimized rendering (no animations, batched refreshes, `EpdController` refresh modes)
- Undo/redo history
- Selection, copy, and lasso tools
- Multiple pen types (fountain, ballpoint, marker, pencil) and sizes
- Eraser (stroke and area modes, scribble-to-erase)
- Image insertion
- Zoom and pan

Features removed from the UI: notebooks, folders, import/export, background templates, PDF annotation.

---

## Credits

- [Ethran/notable](https://github.com/Ethran/notable) — the actively maintained fork this builds on
- [olup/notable](https://github.com/olup/notable) — the original project
- Onyx Pen SDK and MyScript HWR engine (via Boox firmware)
- [Jetpack Ink](https://developer.android.com/jetpack/androidx/releases/ink) for stroke rendering

---

## License

[MIT](./LICENSE)

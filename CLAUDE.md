# Notable - Onyx Boox Note-Taking App

## Project Overview
Fork of [Ethran/notable](https://github.com/Ethran/notable) — an alternative note-taking app for Onyx Boox e-ink devices. Written in Kotlin with Jetpack Compose. Uses the Onyx Pen SDK for low-latency stylus input.

## Prerequisites

### Required tools (install via Homebrew)
```bash
brew install openjdk@17
brew install --cask android-commandlinetools
brew install --cask android-platform-tools
```

### Shell config (~/.zshrc)
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### Android SDK setup (one-time)
```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sdkmanager --licenses
```

## Building

### First-time setup
The `IS_NEXT` property must exist in `gradle.properties`. If missing, add:
```
IS_NEXT=false
```

### Build commands
```bash
# Build debug APK
./gradlew assembleDebug

# Build + install to connected Boox device
./gradlew installDebug

# Build + install + launch in one shot
./gradlew installDebug && adb shell am start -n com.ethran.notable/.MainActivity

# Run unit tests
./gradlew test

# Clean
./gradlew clean
```

### Build output
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

### Release signing
The release build uses env vars for signing (configured in `app/build.gradle`). The keystore is at `notable-release.jks` (gitignored).

```bash
# Build signed release APK
STORE_FILE=/Users/joshuapham/Hacks/notable/notable-release.jks \
STORE_PASSWORD=notable123 \
KEY_ALIAS=notable \
KEY_PASSWORD=notable123 \
./gradlew assembleRelease

# Install release APK (must uninstall debug build first if switching signing keys)
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Important notes:**
- Switching between debug and release signing requires uninstalling first (`adb uninstall com.ethran.notable`) which wipes app data (settings, pages, etc.)
- After installing a release APK, wait ~30s for dex2oat compilation to finish before launching. The device may show "install" prompt if the app hasn't finished compiling. A reboot can help.
- Prefer `./gradlew installDebug` for development iteration — it avoids signing key conflicts

## Deploying to Onyx Boox

### Enable USB debugging on the Boox
1. Settings > Apps > App Management > enable "USB Debug Mode"
   - OR: Settings > About Tablet > tap "Build Number" 7 times > Developer Options > USB Debugging

### ADB commands
```bash
# Verify device is connected
adb devices

# Install APK manually
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View app logs (filter to this app)
adb logcat --pid=$(adb shell pidof com.ethran.notable)

# View all logs (useful when app crashes on launch)
adb logcat -d | tail -100

# Take a screenshot from the Boox
adb exec-out screencap -p > screenshot.png

# Force stop the app
adb shell am force-stop com.ethran.notable

# Uninstall
adb uninstall com.ethran.notable
```

## Project Structure

```
app/src/main/java/com/ethran/notable/
├── MainActivity.kt              # Entry point, Hilt setup, fullscreen
├── NotableApp.kt                # Application class
├── data/
│   ├── AppRepository.kt         # Main data repository
│   ├── PageDataManager.kt       # Page data operations
│   ├── datastore/               # App settings, editor settings cache
│   └── db/                      # Room database: Db.kt, Migrations.kt
│       ├── Stroke.kt            # Stroke entity (polyline-encoded points)
│       ├── Page.kt, Notebook.kt, Folder.kt, Image.kt, Kv.kt
│       └── EncodePolyline.kt    # Stroke point compression
├── editor/
│   ├── EditorControlTower.kt    # Central editor logic coordinator
│   ├── EditorView.kt            # Main editor composable
│   ├── PageView.kt              # Page rendering
│   ├── canvas/
│   │   ├── DrawCanvas.kt        # SurfaceView-based canvas
│   │   ├── OnyxInputHandler.kt  # Onyx Pen SDK integration (TouchHelper)
│   │   ├── CanvasEventBus.kt    # Event system for canvas
│   │   └── CanvasRefreshManager.kt  # E-ink refresh coordination
│   ├── drawing/                 # Stroke rendering, pen styles, backgrounds
│   ├── state/                   # EditorState, SelectionState, history (undo/redo)
│   ├── ui/                      # Toolbar, menus, selection UI
│   └── utils/                   # Geometry, eraser, pen, draw helpers
├── gestures/                    # Gesture detection and handling
├── io/                          # Import/export: PDF, XOPP, PNG, share
├── navigation/                  # Compose Navigation setup
└── ui/                          # App-level UI: settings, dialogs, themes
```

## Key Technical Details

### Onyx Pen SDK
- Uses `TouchHelper` + `RawInputCallback` for low-latency stylus input
- SDK deps: `onyxsdk-pen:1.5.1`, `onyxsdk-device:1.3.2`, `onyxsdk-base:1.8.3`
- Maven repo: `http://repo.boox.com/repository/maven-public/` (insecure HTTP, required)
- Pen input only works on Onyx hardware — cannot test in emulator
- Key files: `OnyxInputHandler.kt`, `DrawCanvas.kt`

### E-ink considerations
- No animations or transitions — every frame causes ghosting
- Use `EpdController` for refresh mode control (see `einkHelper.kt`)
- Batch UI updates; minimize unnecessary recompositions
- High contrast, no gradients

### Architecture
- **Dependency injection**: Dagger Hilt (`@AndroidEntryPoint`, `@Inject`)
- **Database**: Room with KSP code generation, schema migrations in `app/schemas/`
- **UI**: Jetpack Compose (Material, not Material3)
- **Navigation**: Compose Navigation
- **Logging**: ShipBook SDK (remote logging, needs API keys or uses defaults)
- **Firebase**: Analytics only (google-services.json is committed)

### Important properties
- `applicationId`: `com.ethran.notable`
- `minSdk`: 29 (Android 10)
- `targetSdk`: 35
- `compileSdk`: 36
- `JVM target`: 17
- `Gradle`: 9.1.0
- `Kotlin`: 2.3.10
- `AGP`: 9.0.0

## Debugging Tips

### App crashes on launch
```bash
# Get the crash stacktrace
adb logcat -d | grep -A 20 "FATAL EXCEPTION"
```

### Build fails
```bash
# Full error output
./gradlew assembleDebug --stacktrace

# Dependency issues
./gradlew dependencies --configuration debugRuntimeClasspath
```

### Testing without a Boox device
- The Android emulator can test general UI layout and navigation
- Pen/stylus features will NOT work (Onyx SDK is device-specific)
- The emulator cannot simulate e-ink refresh behavior

## Workflow for Claude Code

When making changes to this project:
1. Read the relevant source files before editing
2. Build with `./gradlew assembleDebug` to check compilation
3. If a Boox device is connected, deploy with `./gradlew installDebug`
4. Check logs with `adb logcat` after deployment
5. Run `./gradlew test` for unit tests when touching data/logic code

### Common gotchas
- The `IS_NEXT` gradle property must be set (add `IS_NEXT=false` to `gradle.properties`)
- The Onyx Maven repo uses HTTP, not HTTPS — `allowInsecureProtocol = true` is required
- Room schema changes require a migration in `Migrations.kt` and incrementing the DB version in `Db.kt`
- Compose recomposition can be expensive on e-ink — use `remember`, `derivedStateOf`, and avoid unnecessary state changes

package com.ethran.notable.editor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.AnnotationMode
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.ToolbarMenu
import com.ethran.notable.editor.ui.toolbar.presentlyUsedToolIcon
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.LibraryDestination
import compose.icons.FeatherIcons
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw
import compose.icons.feathericons.Clipboard
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("EditorSidebar")

private val SIZES_STROKES_DEFAULT = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)
private val SIZES_MARKER_DEFAULT = listOf("M" to 25f, "L" to 40f, "XL" to 60f, "XXL" to 80f)

const val SIDEBAR_WIDTH = 56
private const val BUTTON_SIZE = 46
private const val ICON_SIZE = 26

@Composable
fun EditorSidebar(
    exportEngine: ExportEngine,
    navController: NavController,
    appRepository: AppRepository,
    state: EditorState,
    controlTower: EditorControlTower,
    topPadding: Int = 0
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val zoomLevel by state.pageView.zoomLevel.collectAsState()

    // Force e-ink refresh when sidebar state changes.
    // The Onyx SDK sets a global display scheme that suppresses normal view updates,
    // so we need to explicitly tell the e-ink controller to refresh.
    fun refreshSidebar() {
        view.postInvalidate()
        try {
            val sidebarPx = convertDpToPixel(SIDEBAR_WIDTH.dp, context).toInt()
            // Use the root view's location to get absolute screen coordinates
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            EpdController.refreshScreenRegion(
                view, loc[0], loc[1], sidebarPx, view.height, UpdateMode.GC
            )
            log.i("Sidebar e-ink refresh triggered")
        } catch (e: Exception) {
            log.w("E-ink sidebar refresh failed: ${e.message}")
        }
    }

    val pickMedia = rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
        if (uri == null) {
            log.w("PickVisualMedia: uri is null")
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            try {
                val copiedFile = copyImageToDatabase(context, uri)
                log.i("Image copied to: ${copiedFile.toUri()}")
                CanvasEventBus.addImageByUri.value = copiedFile.toUri()
            } catch (e: Exception) {
                log.e("ImagePicker: copy failed: ${e.message}", e)
            }
        }
    }

    // Background selector dialog
    if (state.menuStates.isBackgroundSelectorModalOpen) {
        BackgroundSelector(
            initialPageBackgroundType = state.pageView.pageFromDb?.backgroundType ?: "native",
            initialPageBackground = state.pageView.pageFromDb?.background ?: "blank",
            initialPageNumberInPdf = state.pageView.getBackgroundPageNumber(),
            notebookId = state.pageView.pageFromDb?.notebookId,
            pageNumberInBook = state.pageView.currentPageNumber,
            onChange = { backgroundType, background ->
                val updatedPage = if (background == null)
                    state.pageView.pageFromDb!!.copy(backgroundType = backgroundType)
                else state.pageView.pageFromDb!!.copy(
                    background = background,
                    backgroundType = backgroundType
                )
                state.pageView.updatePageSettings(updatedPage)
                scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
            }
        ) {
            state.menuStates.isBackgroundSelectorModalOpen = false
        }
    }

    LaunchedEffect(state.menuStates.isBackgroundSelectorModalOpen, state.menuStates.isMenuOpen) {
        state.checkForSelectionsAndMenus()
    }

    if (!state.isToolbarOpen) {
        // Collapsed: single icon button in top-left to reopen
        Box(Modifier.padding(top = (topPadding + 4).dp, start = 4.dp)) {
            SidebarIconButton(
                iconId = presentlyUsedToolIcon(state.mode, state.pen),
                contentDescription = "open toolbar",
                onClick = { state.isToolbarOpen = true }
            )
        }
        return
    }

    // --- Expanded sidebar ---
    var isPenPickerOpen by remember { mutableStateOf(false) }
    var isEraserMenuOpen by remember { mutableStateOf(false) }

    // Pause drawing when popups are open
    LaunchedEffect(isPenPickerOpen, isEraserMenuOpen) {
        state.isDrawing = !(isPenPickerOpen || isEraserMenuOpen)
    }

    fun handleChangePen(pen: Pen) {
        if (state.mode == Mode.Draw && state.pen == pen) {
            // Already on this pen — toggle stroke picker
            isPenPickerOpen = !isPenPickerOpen
        } else {
            state.mode = Mode.Draw
            state.pen = pen
            isPenPickerOpen = false
        }
    }

    fun onChangeStrokeSetting(penName: String, setting: PenSetting) {
        val settings = state.penSettings.toMutableMap()
        settings[penName] = setting.copy()
        state.penSettings = settings
    }

    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH.dp)
            .fillMaxHeight()
            .background(Color.White)
            .noRippleClickable { log.i("Sidebar background tapped (event consumed)") }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Close sidebar
        SidebarIconButton(
            vectorIcon = FeatherIcons.EyeOff,
            contentDescription = "close toolbar",
            onClick = {
                log.i("Close toolbar tapped")
                state.isToolbarOpen = false
            }
        )

        SidebarDivider()

        // --- Drawing tools ---

        // Pen button (shows current pen, tap to open picker)
        Box {
            SidebarIconButton(
                iconId = presentlyUsedToolIcon(Mode.Draw, state.pen),
                contentDescription = "pen",
                isSelected = state.mode == Mode.Draw,
                onClick = {
                    log.i("Pen button tapped, current mode=${state.mode}")
                    if (state.mode == Mode.Draw) {
                        isPenPickerOpen = !isPenPickerOpen
                        isEraserMenuOpen = false
                    } else {
                        state.mode = Mode.Draw
                        isPenPickerOpen = false
                    }
                    refreshSidebar()
                }
            )
            if (isPenPickerOpen) {
                PenPickerFlyout(
                    state = state,
                    onSelectPen = { pen -> handleChangePen(pen) },
                    onChangeSetting = { penName, setting -> onChangeStrokeSetting(penName, setting) },
                    onClose = { isPenPickerOpen = false }
                )
            }
        }

        // Eraser
        Box {
            SidebarIconButton(
                iconId = if (state.eraser == Eraser.PEN) R.drawable.eraser else R.drawable.eraser_select,
                contentDescription = "eraser",
                isSelected = state.mode == Mode.Erase,
                onClick = {
                    if (state.mode == Mode.Erase) {
                        isEraserMenuOpen = !isEraserMenuOpen
                        isPenPickerOpen = false
                    } else {
                        state.mode = Mode.Erase
                        isEraserMenuOpen = false
                    }
                    refreshSidebar()
                }
            )
            if (isEraserMenuOpen) {
                EraserFlyout(
                    value = state.eraser,
                    onChange = { state.eraser = it },
                    toggleScribbleToErase = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            appRepository.kvProxy.setAppSettings(
                                GlobalAppSettings.current.copy(scribbleToEraseEnabled = enabled)
                            )
                        }
                    },
                    onClose = { isEraserMenuOpen = false }
                )
            }
        }

        // Lasso / Select
        SidebarIconButton(
            iconId = R.drawable.lasso,
            contentDescription = "lasso",
            isSelected = state.mode == Mode.Select,
            onClick = {
                state.mode = Mode.Select
                isPenPickerOpen = false
                isEraserMenuOpen = false
                refreshSidebar()
            }
        )

        // Line
        SidebarIconButton(
            iconId = R.drawable.line,
            contentDescription = "line",
            isSelected = state.mode == Mode.Line,
            onClick = {
                if (state.mode == Mode.Line) state.mode = Mode.Draw
                else state.mode = Mode.Line
                isPenPickerOpen = false
                isEraserMenuOpen = false
                refreshSidebar()
            }
        )

        SidebarDivider()

        // --- Actions ---

        // Undo
        SidebarIconButton(
            iconId = R.drawable.undo,
            contentDescription = "undo",
            onClick = { scope.launch { controlTower.undo() } }
        )

        // Redo
        SidebarIconButton(
            iconId = R.drawable.redo,
            contentDescription = "redo",
            onClick = { scope.launch { controlTower.redo() } }
        )

        SidebarDivider()

        // --- Annotations ---

        // Wiki link
        SidebarTextButton(
            text = "[[",
            contentDescription = "Wiki link",
            isSelected = state.annotationMode == AnnotationMode.WikiLink,
            onClick = {
                log.i("[[ button tapped, annotationMode=${state.annotationMode}")
                state.annotationMode = if (state.annotationMode == AnnotationMode.WikiLink)
                    AnnotationMode.None else AnnotationMode.WikiLink
                log.i("[[ button: annotationMode now=${state.annotationMode}, setting mode=Draw")
                // Ensure we're in draw mode so the stylus gesture gets captured
                if (state.annotationMode != AnnotationMode.None) state.mode = Mode.Draw
                isPenPickerOpen = false
                isEraserMenuOpen = false
                refreshSidebar()
            }
        )

        // Tag
        SidebarTextButton(
            text = "#",
            contentDescription = "Tag",
            isSelected = state.annotationMode == AnnotationMode.Tag,
            onClick = {
                state.annotationMode = if (state.annotationMode == AnnotationMode.Tag)
                    AnnotationMode.None else AnnotationMode.Tag
                if (state.annotationMode != AnnotationMode.None) state.mode = Mode.Draw
                isPenPickerOpen = false
                isEraserMenuOpen = false
                refreshSidebar()
            }
        )

        SidebarDivider()

        // --- Utilities ---

        // Clipboard paste (conditional)
        if (state.clipboard != null) {
            SidebarIconButton(
                vectorIcon = FeatherIcons.Clipboard,
                contentDescription = "paste",
                onClick = { controlTower.pasteFromClipboard() }
            )
        }

        // Reset zoom (conditional)
        val showResetView = state.pageView.scroll.x != 0f || zoomLevel != 1.0f
        if (showResetView) {
            SidebarIconButton(
                vectorIcon = FeatherIcons.RefreshCcw,
                contentDescription = "reset view",
                onClick = { controlTower.resetZoomAndScroll() }
            )
        }

        // Image insert
        SidebarIconButton(
            iconId = R.drawable.image,
            contentDescription = "insert image",
            onClick = {
                log.i("Launching image picker...")
                pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            }
        )

        // Home
        SidebarIconButton(
            iconId = R.drawable.home,
            contentDescription = "home",
            onClick = { navController.navigate("library") }
        )

        // Menu
        Box {
            SidebarIconButton(
                iconId = R.drawable.menu,
                contentDescription = "menu",
                onClick = {
                    state.menuStates.isMenuOpen = !state.menuStates.isMenuOpen
                }
            )
            if (state.menuStates.isMenuOpen) {
                ToolbarMenu(
                    exportEngine = exportEngine,
                    goToBugReport = { navController.navigate(BugReportDestination.route) },
                    goToLibrary = {
                        scope.launch {
                            val page = withContext(Dispatchers.IO) {
                                appRepository.pageRepository.getById(state.currentPageId)
                            }
                            val parentFolder = withContext(Dispatchers.IO) {
                                page?.getParentFolder(appRepository.bookRepository)
                            }
                            navController.navigate(LibraryDestination.createRoute(parentFolder))
                        }
                    },
                    currentPageId = state.currentPageId,
                    currentBookId = state.bookId,
                    onClose = { state.menuStates.isMenuOpen = false },
                    onBackgroundSelectorModalOpen = {
                        state.menuStates.isBackgroundSelectorModalOpen = true
                    }
                )
            }
        }
    }
}

// --- Pen Picker Flyout ---

@Composable
private fun PenPickerFlyout(
    state: EditorState,
    onSelectPen: (Pen) -> Unit,
    onChangeSetting: (String, PenSetting) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var selectedPenForSize by remember { mutableStateOf<Pen?>(null) }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(
            convertDpToPixel((SIDEBAR_WIDTH + 4).dp, context).toInt(),
            0
        ),
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Pen grid — 2 columns
            val pens = buildList {
                add(Pen.BALLPEN to R.drawable.ballpen)
                if (!GlobalAppSettings.current.monochromeMode) {
                    add(Pen.REDBALLPEN to R.drawable.ballpenred)
                    add(Pen.BLUEBALLPEN to R.drawable.ballpenblue)
                    add(Pen.GREENBALLPEN to R.drawable.ballpengreen)
                }
                if (GlobalAppSettings.current.neoTools) {
                    add(Pen.PENCIL to R.drawable.pencil)
                    add(Pen.BRUSH to R.drawable.brush)
                }
                add(Pen.FOUNTAIN to R.drawable.fountain)
                add(Pen.MARKER to R.drawable.marker)
            }

            // Show pens in rows of 4
            pens.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { (pen, iconId) ->
                        val isSelected = state.mode == Mode.Draw && state.pen == pen
                        val penSetting = state.penSettings[pen.penName]
                        val penColor = penSetting?.let { Color(it.color) }

                        SidebarIconButton(
                            iconId = iconId,
                            contentDescription = pen.penName,
                            isSelected = isSelected,
                            penColor = penColor,
                            onClick = {
                                if (isSelected) {
                                    // Toggle size picker for this pen
                                    selectedPenForSize = if (selectedPenForSize == pen) null else pen
                                } else {
                                    onSelectPen(pen)
                                    selectedPenForSize = null
                                }
                            }
                        )
                    }
                }
            }

            // Size/color picker for the selected pen
            val penForSize = selectedPenForSize ?: (if (state.mode == Mode.Draw) state.pen else null)
            if (penForSize != null) {
                val penSetting = state.penSettings[penForSize.penName] ?: return@Column
                val sizes = if (penForSize == Pen.MARKER) SIZES_MARKER_DEFAULT else SIZES_STROKES_DEFAULT

                Box(
                    Modifier
                        .size(width = 28.dp, height = 1.dp)
                        .background(Color.LightGray)
                        .align(Alignment.CenterHorizontally)
                )

                // Size buttons
                Row(
                    modifier = Modifier
                        .background(Color.White)
                        .border(1.dp, Color.Black),
                    horizontalArrangement = Arrangement.Center
                ) {
                    sizes.forEach { (label, size) ->
                        SidebarIconButton(
                            text = label,
                            contentDescription = "size $label",
                            isSelected = penSetting.strokeSize == size,
                            onClick = {
                                onChangeSetting(penForSize.penName, PenSetting(strokeSize = size, color = penSetting.color))
                            }
                        )
                    }
                }

                // Color options
                val colorOptions = if (GlobalAppSettings.current.monochromeMode) listOf(
                    Color.Black, Color.DarkGray, Color.Gray, Color.LightGray
                ) else listOf(
                    Color.Red, Color.Green, Color.Blue,
                    Color.Cyan, Color.Magenta, Color.Yellow,
                    Color.Gray, Color.DarkGray, Color.Black,
                )
                // Color grid in rows of 5
                colorOptions.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        row.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(color)
                                    .border(
                                        2.dp,
                                        if (color == Color(penSetting.color)) Color.Black else Color.Transparent
                                    )
                                    .clickable {
                                        onChangeSetting(
                                            penForSize.penName,
                                            PenSetting(
                                                strokeSize = penSetting.strokeSize,
                                                color = android.graphics.Color.argb(
                                                    (color.alpha * 255).toInt(),
                                                    (color.red * 255).toInt(),
                                                    (color.green * 255).toInt(),
                                                    (color.blue * 255).toInt()
                                                )
                                            )
                                        )
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Eraser Flyout ---

@Composable
private fun EraserFlyout(
    value: Eraser,
    onChange: (Eraser) -> Unit,
    toggleScribbleToErase: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(
            convertDpToPixel((SIDEBAR_WIDTH + 4).dp, context).toInt(),
            0
        ),
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black)
                .padding(6.dp)
        ) {
            Row(
                Modifier
                    .height(IntrinsicSize.Max)
                    .border(1.dp, Color.Black)
            ) {
                SidebarIconButton(
                    iconId = R.drawable.eraser,
                    contentDescription = "pen eraser",
                    isSelected = value == Eraser.PEN,
                    onClick = { onChange(Eraser.PEN) }
                )
                SidebarIconButton(
                    iconId = R.drawable.eraser_select,
                    contentDescription = "select eraser",
                    isSelected = value == Eraser.SELECT,
                    onClick = { onChange(Eraser.SELECT) }
                )
            }
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .height(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = stringResource(R.string.toolbar_scribble_to_erase_two_lined_short),
                    modifier = Modifier.padding(end = 6.dp),
                    style = TextStyle(color = Color.Black, fontSize = 13.sp)
                )
                val initialState = GlobalAppSettings.current.scribbleToEraseEnabled
                var isChecked by remember { mutableStateOf(initialState) }
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .border(1.dp, Color.Black)
                        .background(if (isChecked) Color.Black else Color.White)
                        .clickable {
                            isChecked = !isChecked
                            toggleScribbleToErase(isChecked)
                        }
                )
            }
        }
    }
}

// --- Reusable sidebar button components ---

@Composable
private fun SidebarIconButton(
    iconId: Int? = null,
    vectorIcon: ImageVector? = null,
    text: String? = null,
    contentDescription: String,
    isSelected: Boolean = false,
    penColor: Color? = null,
    onClick: () -> Unit
) {
    val bgColor = when {
        isSelected && penColor != null -> penColor
        isSelected -> Color.Black
        else -> Color.White
    }
    val fgColor = when {
        isSelected && penColor != null && penColor != Color.Black && penColor != Color.DarkGray -> Color.Black
        isSelected -> Color.White
        else -> Color.Black
    }
    val borderColor = if (isSelected) Color.Black else Color.Gray

    Box(
        modifier = Modifier
            .size(BUTTON_SIZE.dp)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(bgColor, RoundedCornerShape(6.dp))
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            iconId != null -> Icon(
                painterResource(iconId),
                contentDescription = contentDescription,
                tint = fgColor,
                modifier = Modifier.size(ICON_SIZE.dp)
            )
            vectorIcon != null -> Icon(
                vectorIcon,
                contentDescription = contentDescription,
                tint = fgColor,
                modifier = Modifier.size(ICON_SIZE.dp)
            )
            text != null -> Text(
                text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
        }
    }
}

@Composable
private fun SidebarTextButton(
    text: String,
    contentDescription: String,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color.Black else Color.White
    val fgColor = if (isSelected) Color.White else Color.Black
    val borderColor = if (isSelected) Color.Black else Color.Gray

    Box(
        modifier = Modifier
            .size(BUTTON_SIZE.dp)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(bgColor, RoundedCornerShape(6.dp))
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = if (text.length > 2) 12.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = fgColor
        )
    }
}

@Composable
private fun SidebarDivider() {
    Box(
        Modifier
            .size(width = 28.dp, height = 1.dp)
            .background(Color.LightGray)
    )
}

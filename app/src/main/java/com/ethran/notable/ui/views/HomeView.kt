package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.SyncState
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.BatchConverterSection
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.ShipBook


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewQuickPage: (String?) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        uiState = uiState,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewQuickPage = { onCreateNewQuickPage(uiState.folderId) },
        onCreateNewFolder = viewModel::createNewFolder,
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = viewModel::onCreateNewNotebook,
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile

    )
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit
) {
    val scrollState = rememberScrollState()
    val settings = com.ethran.notable.data.datastore.GlobalAppSettings.current
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 240.dp)
        ) {
            // Slim header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notable",
                    style = androidx.compose.material.MaterialTheme.typography.h5,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                BadgedBox(
                    badge = {
                        if (!uiState.isLatestVersion) Badge(
                            backgroundColor = Color.Black,
                            modifier = Modifier.offset((-12).dp, 10.dp)
                        )
                    }) {
                    Icon(
                        imageVector = FeatherIcons.Settings, contentDescription = "Settings",
                        Modifier
                            .padding(8.dp)
                            .noRippleClickable(onClick = onNavigateToSettings)
                    )
                }
            }

            // Page grid
            val pages = uiState.singlePages?.reversed() ?: emptyList()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Note: Using a fixed height for the grid inside a scrollable Column
                // Calculate approximate height needed: ceil(items / columns) * itemHeight
                val itemsPerRow = 2 // Approximate for GridCells.Adaptive(140.dp)
                val rows = ((pages.size + 1) + itemsPerRow - 1) / itemsPerRow
                val itemHeight = 140.dp * 4f / 3f // aspectRatio 3/4
                val gridHeight = itemHeight * rows + 16.dp * (rows - 1)

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .height(gridHeight)
                        .autoEInkAnimationOnScroll(),
                    userScrollEnabled = false // Disable grid scrolling, use parent scroll
                ) {
                    // New capture card
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .aspectRatio(3f / 4f)
                                .border(2.dp, Color.Black, RectangleShape)
                                .noRippleClickable(onClick = onCreateNewQuickPage)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.FilePlus,
                                    contentDescription = "New Capture",
                                    tint = Color.Black,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    "New Capture",
                                    style = androidx.compose.material.MaterialTheme.typography.body2,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }

                    // Existing pages
                    items(pages) { page ->
                        var isPageSelected by remember { mutableStateOf(false) }
                        val isSyncing = page.id in SyncState.syncingPageIds
                        Box {
                            PagePreview(
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = { goToPage(page.id) },
                                        onLongClick = { isPageSelected = true }
                                    )
                                    .aspectRatio(3f / 4f)
                                    .border(1.dp, Color.Gray, RectangleShape),
                                pageId = page.id
                            )
                            if (isSyncing) {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(3f / 4f)
                                        .background(Color.White.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Syncing...",
                                        style = androidx.compose.material.MaterialTheme.typography.caption,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                            if (isPageSelected) com.ethran.notable.editor.ui.PageMenu(
                                appRepository = appRepository,
                                pageId = page.id,
                                canDelete = true,
                                onClose = { isPageSelected = false }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        BatchConverterSection(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            settings = settings,
            onSettingsChange = { newSettings ->
                com.ethran.notable.data.datastore.GlobalAppSettings.update(newSettings)
                // Persist to database
                CoroutineScope(Dispatchers.IO).launch {
                    appRepository.kvProxy.setKv(
                        APP_SETTINGS_KEY,
                        newSettings,
                        AppSettings.serializer()
                    )
                }
            }
        )
    }
}

@Composable
fun FolderList(
    appRepository: AppRepository,
    folders: List<Folder>,
    onNavigateToFolder: (String) -> Unit, onCreateNewFolder: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        item {
            // Add new folder row
            Row(
                Modifier
                    .border(0.5.dp, Color.Black)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .noRippleClickable(onClick = onCreateNewFolder)
            ) {
                Icon(
                    imageVector = FeatherIcons.FolderPlus, contentDescription = "Add Folder",
                    Modifier.height(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(text = stringResource(R.string.home_add_new_folder))
            }
        }

        if (folders.isNotEmpty()) {
            items(folders) { folder ->
                var isFolderSettingsOpen by remember { mutableStateOf(false) }
                if (isFolderSettingsOpen) FolderConfigDialog(
                    appRepository.folderRepository,
                    folderId = folder.id,
                    onClose = {
                        log.i("Closing Directory Dialog")
                        isFolderSettingsOpen = false
                    })
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = { onNavigateToFolder(folder.id) },
                            onLongClick = { isFolderSettingsOpen = true })
                        .border(0.5.dp, Color.Black)
                        .padding(10.dp, 5.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder, contentDescription = "Folder",
                        Modifier.height(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = folder.title)
                }
            }
        }
    }
}

@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    books: List<Notebook>,
    isImporting: Boolean,
    onNavigateToEditor: (String, String) -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit
) {
    Text(text = stringResource(R.string.home_notebooks))
    Spacer(Modifier.height(10.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.autoEInkAnimationOnScroll()
    ) {
        item {
            NotebookImportPanel(
                onCreateNewNotebook = onCreateNewNotebook,
                onImportPdf = onImportPdf,
                onImportXopp = onImportXopp
            )
        }

        if (books.isNotEmpty()) {
            items(books.reversed()) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!isImporting) {
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    onOpen = { bookId, pageId -> onNavigateToEditor(pageId, bookId) },
                    onOpenSettings = { isSettingsOpen = true })

                if (isSettingsOpen) {
                    NotebookConfigDialog(
                        appRepository,
                        exportEngine = exportEngine,
                        bookId = book.id, onClose = { isSettingsOpen = false })
                }
            }
        }
    }
}

@Composable
fun NotebookImportPanel(
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }

    showPdfImportChoiceDialog?.let { uri ->
        PdfImportChoiceDialog(uri = uri, onCopy = { uri ->
            showPdfImportChoiceDialog = null
            onImportPdf(uri, /* copy= */ true)
        }, onObserve = {
            showPdfImportChoiceDialog = null
            onImportPdf(it, /* copy= */ false)
        }, onDismiss = { showPdfImportChoiceDialog = null })
    }


    Box(
        modifier = modifier
            .width(100.dp)
            .aspectRatio(3f / 4f)
            .border(1.dp, Color.Gray, RectangleShape),
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Create New Notebook Button (Top Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f) // Takes half the height
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable(onClick = onCreateNewNotebook)
            ) {
                Icon(
                    imageVector = FeatherIcons.FilePlus, contentDescription = "Create Notebook",
                    tint = Color.Gray, modifier = Modifier.size(40.dp)
                )
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) {
                    log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
                    return@rememberLauncherForActivityResult
                }
                try {

                    val mimeType = context.contentResolver.getType(uri)
                    log.d("Selected file mimeType: $mimeType, uri: $uri")
                    if (mimeType == "application/pdf" || uri.toString()
                            .endsWith(".pdf", ignoreCase = true)
                    ) {
                        showPdfImportChoiceDialog = uri
                    } else {
                        onImportXopp(uri)
                    }
                } catch (e: Exception) {
                    log.e("contentPicker failed: ${e.message}", e)
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Importing failed: ${e.message}"))
                }
            }
            // Import Notebook (Bottom Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable {
                        launcher.launch(
                            arrayOf(
                                "application/x-xopp",
                                "application/gzip",
                                "application/octet-stream",
                                "application/pdf"
                            )
                        )
                    }

            ) {
                Icon(
                    imageVector = FeatherIcons.Upload,
                    contentDescription = "Import Notebook",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}


@Preview(
    showBackground = true,
    name = "Library - Default State",
    widthDp = 800,
    heightDp = 1200
)
@Composable
fun LibraryContentPreview() {
    // 1. Create a dummy UI state with mock data
    val mockUiState = LibraryUiState(
        folderId = null,
        isLatestVersion = true,
        isImporting = false,
        breadcrumbFolders = listOf(
            // Optional: Add mock breadcrumbs if you want to preview nested folder state
             Folder(id = "root", title = "Home", parentFolderId = null)
        ),
        folders = listOf(
            // Adjust constructor arguments based on your exact entity definition
            Folder(id = "folder_1", title = "Work Notes", parentFolderId = null),
            Folder(id = "folder_2", title = "Personal", parentFolderId = null)
        ),
        books = listOf(
            // Needs pageIds to render the card (empty books show a warning)
            Notebook(id = "book_1", title = "Meeting Minutes", pageIds = listOf("page1", "page2")),
            Notebook(id = "book_2", title = "Journal", pageIds = listOf("page3"))
        ),
        singlePages = emptyList() // Populate with mock Page() objects if you want to see Quick Pages
    )

    // 2. Render the stateless component with empty lambdas
//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Preview(showBackground = true, name = "Library - Update Available & Importing")
@Composable
fun LibraryContentUpdatePreview() {
    val mockUiState = LibraryUiState(
        folderId = "folder_1",
        isLatestVersion = false, // Will show the red badge on the settings icon
        isImporting = true,      // Will hide the delete warning for empty books
        breadcrumbFolders = emptyList(),
        folders = emptyList(),
        books = emptyList(),
        singlePages = emptyList()
    )

//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}
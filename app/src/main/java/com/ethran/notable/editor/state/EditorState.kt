package com.ethran.notable.editor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook

enum class Mode {
    Draw, Erase, Select, Line
}

@Stable
class MenuStates {
    var isStrokeSelectionOpen by mutableStateOf(false)
    var isMenuOpen by mutableStateOf(false)
    var isBackgroundSelectorModalOpen by mutableStateOf(false)
    fun closeAll() {
        isStrokeSelectionOpen = false
        isMenuOpen = false
        isBackgroundSelectorModalOpen = false
    }

    val anyMenuOpen: Boolean
        get() = isStrokeSelectionOpen || isMenuOpen || isBackgroundSelectorModalOpen
}


class EditorState(
    val bookId: String? = null,
    val pageId: String,
    val pageView: PageView,
    val appRepository: AppRepository,
    persistedEditorSettings: EditorSettingCacheManager.EditorSettings?,
    val onPageChange: (String) -> Unit
) {
    var currentPageId by mutableStateOf(pageId)
        private set


    suspend fun getNextPage(): String? {
        return if (bookId != null) {
            val newPageId = appRepository.getNextPageIdFromBookAndPageOrCreate(
                pageId = currentPageId, notebookId = bookId
            )
            newPageId
        } else null
    }

    suspend fun getPreviousPage(): String? {
        return if (bookId != null) {
            val newPageId = appRepository.getPreviousPageIdFromBookAndPage(
                pageId = currentPageId, notebookId = bookId
            )
            newPageId
        } else null
    }


    suspend fun updateOpenedPage(newPageId: String) {
        Log.d("EditorView", "Update open page to $newPageId")
        if (bookId != null) {
            appRepository.bookRepository.setOpenPageId(bookId, newPageId)
        }
        if (newPageId != currentPageId) {
            Log.d("EditorView", "Page changed")
            onPageChange(newPageId)
            currentPageId = newPageId
        } else {
            Log.d("EditorView", "Tried to change to same page!")
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Tried to change to same page!", duration = 3000)
            )
        }
    }



    private val log = ShipBook.getLogger("EditorState")

    var mode by mutableStateOf(persistedEditorSettings?.mode ?: Mode.Draw)
    var pen by mutableStateOf(persistedEditorSettings?.pen ?: Pen.DEFAULT)
    var eraser by mutableStateOf(persistedEditorSettings?.eraser ?: Eraser.PEN)
    var isDrawing by mutableStateOf(true)

    var isInboxPage by mutableStateOf(false)
    var isInboxTagsExpanded by mutableStateOf(false)

    var isToolbarOpen by mutableStateOf(persistedEditorSettings?.isToolbarOpen ?: false)
    var penSettings by mutableStateOf(persistedEditorSettings?.penSettings ?: Pen.DEFAULT_SETTINGS)

    val selectionState = SelectionState()

    private var _clipboard by mutableStateOf(Clipboard.content)
    var clipboard
        get() = _clipboard
        set(value) {
            this._clipboard = value

            // The clipboard content must survive the EditorState, so we store a copy in
            // a singleton that lives outside of the EditorState
            Clipboard.content = value
        }

    val menuStates = MenuStates()
    fun closeAllMenus() = menuStates.closeAll()

    fun checkForSelectionsAndMenus() {
        val shouldBeDrawing = !menuStates.anyMenuOpen && !selectionState.isNonEmpty()
        if (isDrawing != shouldBeDrawing) {
            log.d("Drawing state should be: $shouldBeDrawing (menus open: ${menuStates.anyMenuOpen}, selection active: ${selectionState.isNonEmpty()})")
            isDrawing = shouldBeDrawing
        }
    }

    /**
     * Changes the current page to the one with the specified [id].
     *
     * @param id The unique identifier of the page to switch to.
     */
    suspend fun changePage(id: String) {
        log.d("Changing page to $id, from $currentPageId")
        updateOpenedPage(id)
        closeAllMenus()
        selectionState.reset()
    }
}

// if state is Move then applySelectionDisplace() will delete original strokes and images
enum class PlacementMode {
    Move,
    Paste
}

object Clipboard {
    var content: ClipboardContent? = null
}
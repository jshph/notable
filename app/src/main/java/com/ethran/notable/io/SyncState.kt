package com.ethran.notable.io

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.ethran.notable.data.AppRepository
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("SyncState")

object SyncState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val syncingPageIds = mutableStateListOf<String>()

    fun launchSync(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>,
        context: Context
    ) {
        if (pageId in syncingPageIds) return
        syncingPageIds.add(pageId)

        scope.launch {
            try {
                InboxSyncEngine.syncInboxPage(appRepository, pageId, tags, context)
                log.i("Background sync complete for page $pageId")
            } catch (e: Exception) {
                log.e("Background sync failed for page $pageId: ${e.message}", e)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Sync failed: ${e.message}",
                        duration = 4000
                    )
                )
            } finally {
                syncingPageIds.remove(pageId)
            }
        }
    }
}

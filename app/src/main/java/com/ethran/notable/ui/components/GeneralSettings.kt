package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.io.VaultTagScanner


@Composable
fun GeneralSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onClearAllPages: ((onComplete: () -> Unit) -> Unit)? = null
) {
    Column {
        // Capture settings
        InboxCaptureSettings(settings, onSettingsChange)

        Spacer(modifier = Modifier.height(8.dp))

        SelectorRow(
            label = stringResource(R.string.toolbar_position), options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(
                    R.string.toolbar_position_bottom
                )
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                onSettingsChange(settings.copy(toolbarPosition = newPosition))
            })

        SettingToggleRow(
            label = stringResource(R.string.use_onyx_neotools_may_cause_crashes),
            value = settings.neoTools,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(neoTools = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_scribble_to_erase),
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_smooth_scrolling),
            value = settings.smoothScroll,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(smoothScroll = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.continuous_zoom),
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.continuous_stroke_slider),
            value = settings.continuousStrokeSlider,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousStrokeSlider = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.monochrome_mode) + " " + stringResource(R.string.work_in_progress),
            value = settings.monochromeMode,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(monochromeMode = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.paginate_pdf),
            value = settings.paginatePdf,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(paginatePdf = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.preview_pdf_pagination),
            value = settings.visualizePdfPagination,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(visualizePdfPagination = isChecked))
            })

        if (onClearAllPages != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ClearAllPagesButton(onClearAllPages)
        }
    }
}

@Composable
private fun ClearAllPagesButton(onClearAllPages: (onComplete: () -> Unit) -> Unit) {
    var confirmState by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    if (isClearing) {
        Text(
            "Clearing...",
            style = MaterialTheme.typography.body1,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    } else if (confirmState) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Delete all pages, notebooks, and folders?",
                style = MaterialTheme.typography.body1,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .clickable {
                        isClearing = true
                        onClearAllPages {
                            isClearing = false
                            confirmState = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Yes, delete all", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .clickable { confirmState = false }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Cancel", fontSize = 14.sp)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .border(1.dp, Color.Red, RoundedCornerShape(6.dp))
                .clickable { confirmState = true }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Clear all pages", color = Color.Red, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InboxCaptureSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var pathInput by remember { mutableStateOf(settings.obsidianInboxPath) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Capture",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Handwritten captures are recognized and saved as markdown to your Obsidian vault. " +
                    "Tags are loaded from existing notes in this folder.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Vault inbox folder",
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                singleLine = true,
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onSettingsChange(settings.copy(obsidianInboxPath = pathInput))
                    VaultTagScanner.refreshCache(pathInput)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Relative to /storage/emulated/0/. Press Done to save.",
            style = MaterialTheme.typography.caption,
            color = Color.Gray
        )
    }

    SettingsDivider()
}

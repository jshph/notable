package com.ethran.notable.ui.components

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
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
import androidx.compose.ui.platform.LocalContext
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


private fun formatTreeUriLabel(uriString: String): String {
    if (uriString.isBlank()) return "Not set"
    return try {
        val uri = Uri.parse(uriString)
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        Uri.decode(treeId).ifBlank { uriString }
    } catch (_: Exception) {
        Uri.decode(uriString)
    }
}


@Composable
fun GeneralSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onClearAllPages: ((onComplete: () -> Unit) -> Unit)? = null
) {
    Column {
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
fun ObsidianSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    Column {
        SelectorRow(
            label = stringResource(R.string.recognition_language),
            options = listOf(
                "cs_CZ" to stringResource(R.string.lang_cs_cz),
                "en_US" to stringResource(R.string.lang_en_us),
                "de_DE" to stringResource(R.string.lang_de_de),
                "fr_FR" to stringResource(R.string.lang_fr_fr),
                "es_ES" to stringResource(R.string.lang_es_es),
                "it_IT" to stringResource(R.string.lang_it_it),
                "pl_PL" to stringResource(R.string.lang_pl_pl),
                "ru_RU" to stringResource(R.string.lang_ru_ru)
            ),
            value = settings.recognitionLanguage,
            onValueChange = { newLang ->
                onSettingsChange(settings.copy(recognitionLanguage = newLang))
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        InboxCaptureSettings(settings, onSettingsChange)

        Spacer(modifier = Modifier.height(8.dp))

        ObsidianTemplateSettings(settings, onSettingsChange)

        Spacer(modifier = Modifier.height(8.dp))

        BatchConverterInputSettings(settings, onSettingsChange)
    }
}

@Composable
private fun ObsidianTemplateSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val templateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                android.util.Log.e("ObsidianTemplate", "Failed to take read permission: ${e.message}")
            }
            onSettingsChange(settings.copy(obsidianTemplateUri = it.toString()))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Markdown template",
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (settings.obsidianTemplateUri.isNotEmpty()) {
                formatTreeUriLabel(settings.obsidianTemplateUri)
            } else {
                "Not set"
            },
            style = MaterialTheme.typography.body2,
            color = if (settings.obsidianTemplateUri.isNotEmpty()) Color.Black else Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { templateLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (settings.obsidianTemplateUri.isNotEmpty()) "Change Template File" else "Choose Template File")
        }

        if (settings.obsidianTemplateUri.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onSettingsChange(settings.copy(obsidianTemplateUri = "")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Template")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Template replaces default YAML. If it contains keys created/modified, they are auto-filled.",
            style = MaterialTheme.typography.caption,
            color = Color.Gray
        )
    }

    SettingsDivider()
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
    val context = LocalContext.current
    var pathInput by remember { mutableStateOf(settings.obsidianInboxPath) }

    // Folder picker launcher for SAF
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permissions
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                android.util.Log.d("InboxCaptureSettings", "Took persistable permissions: $it")
            } catch (e: Exception) {
                android.util.Log.e("InboxCaptureSettings", "Failed to take permissions: ${e.message}")
            }

            // Update settings with the new URI
            onSettingsChange(settings.copy(obsidianOutputUri = it.toString()))
            // Note: VaultTagScanner not yet updated for SAF URIs
        }
    }

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
            "Output folder (Obsidian inbox)",
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show current selection
        val displayUri = if (settings.obsidianOutputUri.isNotEmpty()) {
            formatTreeUriLabel(settings.obsidianOutputUri)
        } else if (pathInput.isNotEmpty()) {
            pathInput + " (legacy)"
        } else {
            "Not set"
        }

        Text(
            text = displayUri,
            style = MaterialTheme.typography.body2,
            color = if (settings.obsidianOutputUri.isNotEmpty()) Color.Black else Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { folderLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (settings.obsidianOutputUri.isNotEmpty()) "Change Folder" else "Choose Folder")
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Use folder picker for external SD card support. Press Done to save.",
            style = MaterialTheme.typography.caption,
            color = Color.Gray
        )
    }

    SettingsDivider()
}

@Composable
private fun BatchConverterInputSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current

    // Folder picker launcher for SAF
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permissions
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                android.util.Log.d("BatchConverterInput", "Took persistable permissions: $it")
            } catch (e: Exception) {
                android.util.Log.e("BatchConverterInput", "Failed to take permissions: ${e.message}")
            }

            // Update settings with the new URI
            onSettingsChange(settings.copy(batchConverterInputUri = it.toString()))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Batch Converter",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Select input folder containing .note files to convert. Output uses the same folder as Capture.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Input folder for batch processing",
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show current selection
        val displayUri = if (settings.batchConverterInputUri.isNotEmpty()) {
            formatTreeUriLabel(settings.batchConverterInputUri)
        } else {
            "Not set"
        }

        Text(
            text = displayUri,
            style = MaterialTheme.typography.body2,
            color = if (settings.batchConverterInputUri.isNotEmpty()) Color.Black else Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { folderLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (settings.batchConverterInputUri.isNotEmpty()) "Change Folder" else "Choose Folder")
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Use folder picker for external SD card support.",
            style = MaterialTheme.typography.caption,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // PDF Mode Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Generate searchable PDFs",
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium
            )
            OnOffSwitch(
                checked = settings.batchConverterPdfMode,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(batchConverterPdfMode = enabled))
                },
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
            )
        }
        
        Text(
            "Creates PDF with handwriting + invisible text layer, plus markdown with embedded link",
            style = MaterialTheme.typography.caption,
            color = Color.Gray
        )
        
        // PDF Output Folder (only show if PDF mode enabled)
        if (settings.batchConverterPdfMode) {
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                "PDF output folder",
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val pdfFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            it,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("BatchConverterPdf", "Failed to take permissions: ${e.message}")
                    }
                    onSettingsChange(settings.copy(batchConverterPdfUri = it.toString()))
                }
            }
            
            val pdfDisplayUri = if (settings.batchConverterPdfUri.isNotEmpty()) {
                formatTreeUriLabel(settings.batchConverterPdfUri)
            } else {
                "Not set"
            }
            
            Text(
                text = pdfDisplayUri,
                style = MaterialTheme.typography.body2,
                color = if (settings.batchConverterPdfUri.isNotEmpty()) Color.Black else Color.Gray,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { pdfFolderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (settings.batchConverterPdfUri.isNotEmpty()) "Change PDF Folder" else "Choose PDF Folder")
            }
        }
    }

    SettingsDivider()
}

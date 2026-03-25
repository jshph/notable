package com.ethran.notable.noteconverter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring input/output directories.
 */
@Composable
fun SettingsScreen(
    settings: ConverterSettings,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    
    var inputDir by remember { mutableStateOf<String?>(null) }
    var outputDir by remember { mutableStateOf<String?>(null) }
    
    // Load current settings
    LaunchedEffect(Unit) {
        launch {
            settings.inputDir.collect { dir ->
                inputDir = dir
            }
        }
        launch {
            settings.outputDir.collect { dir ->
                outputDir = dir
            }
        }
    }
    
    // Directory picker launchers
    val inputDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permissions for the URI
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                android.util.Log.d("SettingsScreen", "Took persistable permissions for input dir: $it")
            } catch (e: Exception) {
                android.util.Log.e("SettingsScreen", "Failed to take persistable permissions: ${e.message}")
            }
            
            scope.launch {
                val path = it.toString()
                settings.setInputDir(path)
                inputDir = path
            }
        }
    }
    
    val outputDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permissions for the URI
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                android.util.Log.d("SettingsScreen", "Took persistable permissions for output dir: $it")
            } catch (e: Exception) {
                android.util.Log.e("SettingsScreen", "Failed to take persistable permissions: ${e.message}")
            }
            
            scope.launch {
                val path = it.toString()
                settings.setOutputDir(path)
                outputDir = path
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Converter Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Input directory
                Text(
                    text = "Input Directory",
                    style = MaterialTheme.typography.subtitle2
                )
                Text(
                    text = inputDir?.substringAfterLast("%2F") ?: "Not set",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Button(
                    onClick = { inputDirLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose Input Directory")
                }
                
                Divider()
                
                // Output directory
                Text(
                    text = "Output Directory",
                    style = MaterialTheme.typography.subtitle2
                )
                Text(
                    text = outputDir?.substringAfterLast("%2F") ?: "Not set",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Button(
                    onClick = { outputDirLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose Output Directory")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

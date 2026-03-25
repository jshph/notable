package com.ethran.notable.noteconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.*
import dagger.hilt.android.AndroidEntryPoint

/**
 * Note Converter - Simple app to convert Onyx .note files to Obsidian markdown.
 * 
 * Features:
 * - Scan input directory for .note files
 * - Track file changes since last scan
 * - Convert to markdown using MyScript HWR
 * - Generate Obsidian YAML frontmatter with status/todo tag
 * - Export to configured output directory
 */
@AndroidEntryPoint
class NoteConverterActivity : ComponentActivity() {
    
    private lateinit var settings: ConverterSettings
    private lateinit var converter: ObsidianConverter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settings = ConverterSettings(this)
        converter = ObsidianConverter(this)
        
        setContent {
            ConverterTheme {
                Surface(color = MaterialTheme.colors.background) {
                    ConverterApp(settings, converter)
                }
            }
        }
    }
}

@Composable
fun ConverterApp(
    settings: ConverterSettings,
    converter: ObsidianConverter
) {
    var showSettings by remember { mutableStateOf(false) }
    
    ConverterMainScreen(
        settings = settings,
        converter = converter,
        onShowSettings = { showSettings = true }
    )
    
    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
fun ConverterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2),
            secondary = androidx.compose.ui.graphics.Color(0xFF4CAF50)
        ),
        content = content
    )
}

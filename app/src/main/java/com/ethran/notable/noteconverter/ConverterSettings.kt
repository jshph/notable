package com.ethran.notable.noteconverter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings for the Note Converter app.
 * Stores input/output directory paths and last scan timestamp.
 */
class ConverterSettings(private val context: Context) {
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("converter_settings")
        
        private val INPUT_DIR = stringPreferencesKey("input_dir")
        private val OUTPUT_DIR = stringPreferencesKey("output_dir")
        private val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")
    }
    
    /**
     * Input directory path (where .note files are scanned).
     */
    val inputDir: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[INPUT_DIR]
    }
    
    /**
     * Output directory path (where .md files are written).
     */
    val outputDir: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OUTPUT_DIR]
    }
    
    /**
     * Last scan timestamp (epoch millis).
     */
    val lastScanTimestamp: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_SCAN_TIMESTAMP] ?: 0L
    }
    
    /**
     * Set input directory path.
     */
    suspend fun setInputDir(path: String) {
        context.dataStore.edit { prefs ->
            prefs[INPUT_DIR] = path
        }
    }
    
    /**
     * Set output directory path.
     */
    suspend fun setOutputDir(path: String) {
        context.dataStore.edit { prefs ->
            prefs[OUTPUT_DIR] = path
        }
    }
    
    /**
     * Update last scan timestamp to current time.
     */
    suspend fun updateLastScanTimestamp() {
        context.dataStore.edit { prefs ->
            prefs[LAST_SCAN_TIMESTAMP] = System.currentTimeMillis()
        }
    }
    
    /**
     * Reset last scan timestamp to force rescan of all files.
     */
    suspend fun resetLastScanTimestamp() {
        context.dataStore.edit { prefs ->
            prefs[LAST_SCAN_TIMESTAMP] = 0L
        }
    }
    
    /**
     * Clear all settings (for testing).
     */
    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

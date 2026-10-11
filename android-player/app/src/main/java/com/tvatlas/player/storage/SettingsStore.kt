package com.tvatlas.player.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("player_settings")
class SettingsStore(private val context: Context) {
    private val diagnosticsKey = booleanPreferencesKey("diagnostics")
    val diagnostics = context.settingsDataStore.data.map { it[diagnosticsKey] ?: false }
    suspend fun diagnostics(enabled: Boolean) { context.settingsDataStore.edit { it[diagnosticsKey] = enabled } }
}

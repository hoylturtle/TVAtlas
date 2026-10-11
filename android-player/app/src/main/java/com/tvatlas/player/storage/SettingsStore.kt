package com.tvatlas.player.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.model.RouteType
import com.tvatlas.core.routing.RuleCodec
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("player_settings")
class SettingsStore(private val context: Context) {
    private val diagnosticsKey = booleanPreferencesKey("diagnostics")
    private val updateRouteKey = stringPreferencesKey("update_route")
    val diagnostics = context.settingsDataStore.data.map { it[diagnosticsKey] ?: false }
    val updateRoute = context.settingsDataStore.data.map { preferences ->
        preferences[updateRouteKey]?.let { value ->
            runCatching { RuleCodec.json.decodeFromString<RouteTarget>(value) }.getOrNull()
        }?.takeIf { it.type != RouteType.AUTO } ?: RouteTarget.DIRECT
    }
    suspend fun updateRoute(route: RouteTarget) {
        require(route.type != RouteType.AUTO)
        context.settingsDataStore.edit { it[updateRouteKey] = RuleCodec.json.encodeToString(route) }
    }
    suspend fun diagnostics(enabled: Boolean) { context.settingsDataStore.edit { it[diagnosticsKey] = enabled } }
}

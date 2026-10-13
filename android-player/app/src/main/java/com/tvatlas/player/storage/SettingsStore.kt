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
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore("player_settings")
class SettingsStore(private val context: Context) {
    private val optionsKey = stringPreferencesKey("user_options")
    val options = context.settingsDataStore.data.map { p -> decodeOptions(p[optionsKey]) }
    private fun decodeOptions(value: String?) = value?.let { runCatching { RuleCodec.json.decodeFromString<UserOptions>(it) }.getOrNull() } ?: UserOptions()
    suspend fun editOptions(edit: (UserOptions) -> UserOptions) { context.settingsDataStore.edit { p ->
        val next = edit(decodeOptions(p[optionsKey]))
        require(next.refreshHours in 1..168 && next.limitMiB in 0..1048576)
        p[optionsKey] = RuleCodec.json.encodeToString(next)
    } }
    suspend fun snapshot(): SavedPreferences {
        val p = context.settingsDataStore.data.first()
        fun route(key: androidx.datastore.preferences.core.Preferences.Key<String>) = p[key]?.let { RuleCodec.json.decodeFromString<RouteTarget>(it) } ?: RouteTarget.AUTO
        return SavedPreferences(decodeOptions(p[optionsKey]), p[diagnosticsKey] ?: false, route(updateRouteKey), route(subscriptionRouteKey))
    }
    suspend fun restore(value: SavedPreferences) { context.settingsDataStore.edit { p ->
        p[optionsKey] = RuleCodec.json.encodeToString(value.options); p[diagnosticsKey] = value.diagnostics
        p[updateRouteKey] = RuleCodec.json.encodeToString(value.updateRoute); p[subscriptionRouteKey] = RuleCodec.json.encodeToString(value.subscriptionRoute)
    } }
    private val diagnosticsKey = booleanPreferencesKey("diagnostics")
    private val updateRouteKey = stringPreferencesKey("update_route")
    private val subscriptionRouteKey = stringPreferencesKey("subscription_route")
    val subscriptionRoute = context.settingsDataStore.data.map { preferences ->
        preferences[subscriptionRouteKey]?.let { runCatching { RuleCodec.json.decodeFromString<RouteTarget>(it) }.getOrNull() } ?: RouteTarget.AUTO
    }
    suspend fun subscriptionRoute(route: RouteTarget) {
        context.settingsDataStore.edit { it[subscriptionRouteKey] = RuleCodec.json.encodeToString(route) }
    }
    val diagnostics = context.settingsDataStore.data.map { it[diagnosticsKey] ?: false }
    val updateRoute = context.settingsDataStore.data.map { preferences ->
        preferences[updateRouteKey]?.let { value ->
            runCatching { RuleCodec.json.decodeFromString<RouteTarget>(value) }.getOrNull()
        } ?: RouteTarget.AUTO
    }
    suspend fun updateRoute(route: RouteTarget) {
        context.settingsDataStore.edit { it[updateRouteKey] = RuleCodec.json.encodeToString(route) }
    }
    suspend fun diagnostics(enabled: Boolean) { context.settingsDataStore.edit { it[diagnosticsKey] = enabled } }
}

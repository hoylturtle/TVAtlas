package com.tvatlas.player.vod

import android.content.Context
import com.tvatlas.core.model.stableId
import com.tvatlas.core.vod.VodSkipRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable internal data class VodSkipSettings(val enabled: Boolean = false, val ranges: List<VodSkipRange> = emptyList())
/** Exact media address only: never apply a cut to other providers, episodes or versions. */
internal class VodSkipStore(context: Context) {
    private val preferences = context.getSharedPreferences("vod-manual-skips", Context.MODE_PRIVATE)
    fun read(url: String): VodSkipSettings = runCatching {
        val value = preferences.getString(stableId(url), null) ?: return@runCatching VodSkipSettings()
        Json.decodeFromString<VodSkipSettings>(value).let { it.copy(ranges = it.ranges.filter { r -> r.startMs >= 0 && r.endMs > r.startMs }.take(32)) }
    }.getOrDefault(VodSkipSettings())
    fun save(url: String, settings: VodSkipSettings) {
        require(settings.ranges.size <= 32)
        val edit = preferences.edit()
        if (preferences.all.size >= 200 && !preferences.contains(stableId(url))) preferences.all.keys.firstOrNull()?.let(edit::remove)
        edit.putString(stableId(url), Json.encodeToString(settings)).apply()
    }
}

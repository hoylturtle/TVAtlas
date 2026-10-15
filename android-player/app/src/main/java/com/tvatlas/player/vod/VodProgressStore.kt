package com.tvatlas.player.vod

import android.content.Context
import com.tvatlas.core.vod.VodBookmark
import com.tvatlas.player.storage.CredentialVault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface VodProgressStorage {
    fun load(key: String): VodBookmark?
    fun save(key: String, bookmark: VodBookmark)
}

/** Separate encrypted resume records. Does not store source URLs, titles or account credentials. */
class VodProgressStore(context: Context) : VodProgressStorage {
    private val vault = CredentialVault(context, "vod_progress", "tvatlas.vod-progress.v1")
    private fun read(): Map<String, VodBookmark> = vault.secret("positions")?.let { Json.decodeFromString(it) } ?: emptyMap()
    @Synchronized override fun load(key: String) = read()[key]
    @Synchronized override fun save(key: String, bookmark: VodBookmark) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        require(bookmark.positionMs >= 0 && bookmark.durationMs >= 0)
        val next = linkedMapOf(key to bookmark)
        read().filterKeys { it != key }.entries.take(199).forEach { next[it.key] = it.value }
        vault.putSecret("positions", Json.encodeToString(next))
    }
}

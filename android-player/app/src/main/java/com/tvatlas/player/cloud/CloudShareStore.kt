package com.tvatlas.player.cloud

import android.content.Context
import com.tvatlas.core.cloud.CloudShare
import com.tvatlas.player.storage.CredentialVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface CloudShareStorage {
    val items: kotlinx.coroutines.flow.StateFlow<List<CloudShare>>
    val damaged: Boolean
    fun save(share: CloudShare)
    fun remove(key: String)
}

/** Separate encrypted vault, excluded from proxy backup/export and logs. */
class CloudShareStore(context: Context) : CloudShareStorage {
    private val vault = CredentialVault(context, "cloud_shares", "tvatlas.cloud-shares.v1")
    private var loadFailed = false
    private val _items = MutableStateFlow(read())
    override val items = _items.asStateFlow()
    override val damaged: Boolean get() = loadFailed
    private fun read(): List<CloudShare> = try {
        vault.secret("library")?.let { Json.decodeFromString<List<CloudShare>>(it) }.orEmpty()
    } catch (_: Exception) { loadFailed = true; emptyList() }
    @Synchronized override fun save(share: CloudShare) {
        require(!loadFailed) { "原有加密分享记录无法读取，已保留原数据；请先处理本机存储问题" }
        val next = listOf(share) + _items.value.filterNot { it.key == share.key }
        require(next.size <= 200) { "最多保存 200 个分享，请先移除旧记录" }
        vault.putSecret("library", Json.encodeToString(next)); _items.value = next; loadFailed = false
    }
    @Synchronized override fun remove(key: String) {
        require(!loadFailed) { "原有加密分享记录无法读取，已保留原数据" }
        val next = _items.value.filterNot { it.key == key }
        vault.putSecret("library", Json.encodeToString(next)); _items.value = next
    }
}

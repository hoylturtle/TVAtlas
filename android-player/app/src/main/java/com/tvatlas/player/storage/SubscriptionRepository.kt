package com.tvatlas.player.storage

import androidx.room.withTransaction
import com.tvatlas.core.model.httpUri
import com.tvatlas.core.model.stableId
import com.tvatlas.core.subscription.*
import com.tvatlas.player.proxy.MihomoRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

class SubscriptionRepository(
    private val db: PlayerDatabase, private val client: OkHttpClient,
    private val vault: CredentialVault, private val core: MihomoRuntime,
    private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val download: (suspend (String) -> DownloadedSubscription)? = null,
) {
    val subscriptions = db.dao().subscriptions()
    suspend fun add(name: String, url: String): Int {
        if (name.isBlank()) throw SubscriptionException("请输入订阅名称")
        return update(stableId(url.trim()), name.trim(), url.trim())
    }
    suspend fun refresh(id: String): Int {
        val row = db.dao().subscription(id) ?: throw SubscriptionException("订阅不存在")
        val url = try { withContext(Dispatchers.IO) { vault.secret("subscription:$id:url") } }
            catch (error: Exception) { log?.error("SUBSCRIPTION_CREDENTIALS", error); throw SubscriptionException("本机订阅地址无法解密，请重新添加订阅；已保留旧节点") }
            ?: throw SubscriptionException("订阅地址缺失，请重新添加订阅")
        return update(id, row.name, url)
    }
    private suspend fun update(id: String, name: String, url: String): Int = withContext(Dispatchers.IO) {
        if (httpUri(url) == null) throw SubscriptionException("请输入合法的 HTTP(S) 订阅地址")
        val downloaded = download?.invoke(url) ?: SubscriptionDownloader({ client }, log)
            .downloadWithMetadata(url, listOf(com.tvatlas.core.model.RouteTarget.DIRECT))
        val parsed = ClashSubscription.parse(downloaded.text, id)
        val previousSubscription = db.dao().subscription(id)
        val old = db.dao().subscriptionNodes(id).associateBy { it.id }
        if (db.dao().coreNodes().count { it.subscriptionId != id } + parsed.nodes.size > ClashSubscription.MAX_NODES)
            throw SubscriptionException("最多启用 ${ClashSubscription.MAX_NODES} 个订阅节点，请先移除其他订阅")
        try { core.validate(parsed.nodes.map { it.config }) } catch (_: IOException) { throw SubscriptionException("订阅节点未通过内核校验，已保留旧节点") }
        val keys = parsed.nodes.map { "node:${it.profile.id}" } + "subscription:$id:url"
        val previous = keys.associateWith { vault.secret(it) }
        try {
            parsed.nodes.forEach { vault.putSecret("node:${it.profile.id}", it.config.toString()) }
            vault.putSecret("subscription:$id:url", url)
            db.withTransaction {
                db.dao().deleteProxies(old.keys.toList() - parsed.nodes.map { it.profile.id }.toSet())
                db.dao().saveProxies(parsed.nodes.map { n ->
                    val p = n.profile
                    ProxyRow(p.id, p.name, p.type.name, p.host, p.port, old[p.id]?.enabled ?: true, id)
                })
                val quota = downloaded.quota
                db.dao().saveSubscription(SubscriptionRow(id, name, System.currentTimeMillis(), parsed.nodes.size,
                    quota?.uploadBytes ?: previousSubscription?.uploadBytes,
                    quota?.downloadBytes ?: previousSubscription?.downloadBytes,
                    quota?.totalBytes ?: previousSubscription?.totalBytes,
                    if (quota != null) quota.expireAt else previousSubscription?.expireAt,
                    quota?.let { System.currentTimeMillis() } ?: previousSubscription?.quotaUpdatedAt))
            }
        } catch (error: Exception) { previous.forEach { (key, value) -> vault.putSecret(key, value) }; throw error }
        old.keys.filter { oldId -> parsed.nodes.none { it.profile.id == oldId } }.forEach { vault.putSecret("node:$it", null) }
        core.close()
        log?.event("INFO", "SUBSCRIPTION", "saved nodes=${parsed.nodes.size} skipped=${parsed.skipped}")
        parsed.skipped
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val nodes = db.dao().subscriptionNodes(id)
        db.withTransaction {
            db.dao().deleteProxies(nodes.map { it.id })
            db.dao().deleteSubscription(id)
        }
        nodes.forEach { vault.putSecret("node:${it.id}", null) }
        vault.putSecret("subscription:$id:url", null)
        core.close()
    }
}

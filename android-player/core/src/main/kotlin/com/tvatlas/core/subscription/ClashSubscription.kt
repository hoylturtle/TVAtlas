package com.tvatlas.core.subscription

import com.tvatlas.core.model.*
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class SubscriptionException(message: String) : IllegalArgumentException(message)
data class SubscriptionNode(val profile: ProxyProfile, val config: JsonObject, val protocol: String)
data class ParsedSubscription(val nodes: List<SubscriptionNode>, val skipped: Int)

object ClashSubscription {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_NODES = 200
    private val protocols = setOf("ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic", "anytls", "http", "socks5", "snell")
    private val unsafeKeys = setOf("certificate-path", "private-key-path", "private-key-file", "ca-file", "ca-path", "private-key", "certificate", "client-fingerprint-path")
    fun parse(text: String, subscriptionId: String): ParsedSubscription {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val options = LoaderOptions().apply {
                maxAliasesForCollections = 20
                nestingDepthLimit = 32
                codePointLimit = MAX_BYTES
                isAllowDuplicateKeys = false
            }
            val document = Yaml(SafeConstructor(options)).load<Any>(text.removePrefix("\uFEFF")) as? Map<*, *>
                ?: throw SubscriptionException("请输入 Clash/Mihomo YAML 节点订阅")
            val proxies = document["proxies"] as? List<*> ?: throw SubscriptionException("订阅需要包含 proxies 节点列表；不支持仅含 proxy-providers 的配置")
            if (proxies.size > MAX_NODES) throw SubscriptionException("每个订阅最多导入 $MAX_NODES 个节点")
            val names = mutableSetOf<String>()
            var skipped = 0
            val nodes = proxies.mapNotNull { value ->
                val raw = value as? Map<*, *> ?: throw SubscriptionException("节点格式无效")
                val type = (raw["type"] as? String)?.lowercase().orEmpty()
                if (type !in protocols) { skipped++; return@mapNotNull null }
                val name = (raw["name"] as? String)?.trim().orEmpty()
                val server = (raw["server"] as? String)?.trim().orEmpty()
                val port = (raw["port"] as? Number)?.toInt() ?: (raw["port"] as? String)?.toIntOrNull() ?: 0
                if (name.isBlank() || name.length > 256 || !names.add(name)) throw SubscriptionException("节点名称为空、重复或过长")
                if (server.isBlank() || server.any { it.isWhitespace() || it in "/@?#" } || port !in 1..65535) throw SubscriptionException("节点主机或端口无效")
                val config = element(raw, 0) as JsonObject
                val id = "sub_${subscriptionId.take(16)}_${stableId(name).take(24)}"
                SubscriptionNode(ProxyProfile(id, name, ProxyType.MIHOMO, server, port, subscriptionId = subscriptionId), config, type)
            }
            if (nodes.isEmpty()) throw SubscriptionException("订阅没有支持的节点，请使用 Clash/Mihomo YAML 格式")
            val ids = nodes.associate { it.profile.name to it.profile.id }
            return ParsedSubscription(nodes.map { node ->
                val config = node.config.toMutableMap()
                config["name"] = JsonPrimitive(node.profile.id)
                config["dialer-proxy"]?.jsonPrimitive?.content?.let { name ->
                    config["dialer-proxy"] = JsonPrimitive(ids[name] ?: throw SubscriptionException("节点 dialer-proxy 引用了未导入的节点"))
                }
                node.copy(config = JsonObject(config))
            }, skipped)
        } catch (e: SubscriptionException) { throw e }
        catch (_: Exception) { throw SubscriptionException("订阅 YAML 格式或节点配置无效（详细内容已隐藏）") }
    }
    private fun element(value: Any?, depth: Int): JsonElement {
        require(depth <= 32)
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { element(it, depth + 1) })
            is Map<*, *> -> JsonObject(value.entries.associate { (key, v) ->
                val name = key as? String ?: throw SubscriptionException("节点字段名无效")
                if (name.lowercase() in unsafeKeys) throw SubscriptionException("订阅不接受本地证书或私钥文件配置")
                name to element(v, depth + 1)
            })
            else -> throw SubscriptionException("节点包含不支持的数据类型")
        }
    }
}

object MihomoConfig {
    fun build(nodes: List<JsonObject>, ports: Map<String, Int>, username: String, password: String): String {
        val ids = nodes.map { it.getValue("name").jsonPrimitive.content }
        require(ids.distinct().size == ids.size && ports.keys.all { it in ids })
        require(ports.values.distinct().size == ports.size && ports.values.all { it in 1..65535 })
        val config = buildJsonObject {
            put("allow-lan", false); put("bind-address", "127.0.0.1"); put("mode", "rule")
            put("log-level", "silent"); put("ipv6", true); put("geodata-auto-update", false)
            put("dns", buildJsonObject { put("enable", false) })
            put("profile", buildJsonObject { put("store-selected", false); put("store-fake-ip", false) })
            put("proxies", JsonArray(nodes))
            put("listeners", JsonArray(ports.map { (id, port) -> buildJsonObject {
                put("name", "in_$id"); put("type", "socks"); put("listen", "127.0.0.1")
                put("port", port); put("proxy", id); put("udp", false)
                put("users", buildJsonArray { add(buildJsonObject { put("username", username); put("password", password) }) })
            } }))
            put("rules", buildJsonArray { add("MATCH,DIRECT") })
        }
        return config.toString()
    }
}

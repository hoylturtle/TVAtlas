package com.tvatlas.core.routing

import com.tvatlas.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class Match(
    val channel: String? = null, val channelRegex: String? = null,
    val group: String? = null, val url: String? = null, val urlContains: String? = null,
    val domain: String? = null, val domainSuffix: String? = null, val playlistId: String? = null,
) {
    fun rank(): Int = when {
        url != null -> 2
        domain != null -> 3
        domainSuffix != null || urlContains != null -> 4
        channel != null -> 5
        channelRegex != null -> 6
        group != null -> 7
        playlistId != null -> 8
        else -> 9
    }
    fun matches(context: StreamContext): Boolean {
        val c = context.channel
        val s = context.stream
        val host = httpUri(s.url)?.host?.lowercase().orEmpty()
        return (channel == null || channel == c.name) &&
            (channelRegex == null || Regex(channelRegex).containsMatchIn(c.name)) &&
            (group == null || group == c.group) && (url == null || url == s.url) &&
            (urlContains == null || s.url.contains(urlContains)) &&
            (domain == null || host == domain.lowercase()) &&
            (domainSuffix == null || host == domainSuffix.lowercase() || host.endsWith(".${domainSuffix.lowercase()}")) &&
            (playlistId == null || playlistId == s.sourcePlaylistId)
    }
    fun isRequestRule() = url != null || domain != null || domainSuffix != null || urlContains != null
}
@Serializable data class Rule(val id: String, val match: Match, val route: RouteTarget, val priority: Int? = null)
@Serializable data class RuleConfig(
    val schemaVersion: Int = 1, val name: String = "TVAtlas Routes",
    val proxies: List<ProxyProfile> = emptyList(), val rules: List<Rule> = emptyList(),
    val defaultRoute: RouteTarget = RouteTarget.DIRECT,
)

object RuleCodec {
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    const val MAX_BYTES = 1_048_576
    fun parse(text: String, existingProxies: List<ProxyProfile> = emptyList()): RuleConfig {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "规则文件超过 1 MB" }
        val config = json.decodeFromString<RuleConfig>(text.removePrefix("\uFEFF"))
        validate(config, existingProxies)
        return config
    }
    fun validate(config: RuleConfig, existingProxies: List<ProxyProfile> = emptyList()) {
        require(config.schemaVersion == 1) { "只支持 schemaVersion 1" }
        require(config.proxies.size <= 100 && config.rules.size <= 1000) { "代理或规则数量过多" }
        require(config.proxies.map { it.id }.distinct().size == config.proxies.size) { "代理 ID 重复" }
        require(config.rules.map { it.id }.distinct().size == config.rules.size) { "规则 ID 重复" }
        config.proxies.forEach(::validateProxy)
        require(config.proxies.none { it.type == ProxyType.MIHOMO }) { "订阅节点须通过订阅导入；规则仅引用节点 ID" }
        val ids = (existingProxies + config.proxies).map { it.id }.toSet()
        fun checkRoute(route: RouteTarget) {
            when (route.type) {
                RouteType.DIRECT -> require(route.profile == null && route.`try`.isEmpty()) { "DIRECT 不接受 profile/try" }
                RouteType.PROXY -> require(route.profile in ids && route.`try`.isEmpty()) { "PROXY 引用了未知代理" }
                RouteType.AUTO -> {
                    require(route.profile == null) { "AUTO 不接受 profile" }
                    require(route.`try`.all { it == "DIRECT" || it in ids }) { "AUTO 引用了未知代理" }
                    require(route.`try`.distinct().size == route.`try`.size) { "AUTO try 重复" }
                }
            }
        }
        checkRoute(config.defaultRoute)
        config.rules.forEach { rule ->
            require(rule.id.isNotBlank()) { "规则 ID 不能为空" }
            require(rule.match.rank() != 9) { "规则 ${rule.id} 缺少 match" }
            with(rule.match) {
                listOf(channel, channelRegex, group, url, urlContains, domain, domainSuffix, playlistId)
                    .filterNotNull().forEach { require(it.isNotBlank() && it.length <= 2048) { "匹配字段为空或过长" } }
                channelRegex?.let { require(it.length <= 256) { "正则过长" }; Regex(it) }
                url?.let { require(httpUri(it) != null) { "url 必须为 HTTP(S) 且不能含凭证" } }
                listOfNotNull(domain, domainSuffix).forEach {
                    require(httpUri("https://$it")?.host?.equals(it, true) == true) { "域名格式错误" }
                }
            }
            checkRoute(rule.route)
        }
    }
    fun validateProxy(p: ProxyProfile) {
        require(p.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && p.id != "DIRECT") { "代理 ID 格式错误" }
        require(p.name.isNotBlank() && p.port in 1..65535) { "代理名称或端口错误" }
        require(p.host.isNotBlank() && p.host.none { it.isWhitespace() || it in "/@?#" }) { "代理主机格式错误" }
    }
    // Credential fields are deliberately not part of the portable schema.
    fun export(config: RuleConfig): String = json.encodeToString(config)
}

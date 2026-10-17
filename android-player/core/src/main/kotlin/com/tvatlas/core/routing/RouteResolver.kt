package com.tvatlas.core.routing

import com.tvatlas.core.model.*

interface RouteResolver { fun resolve(context: StreamContext): RoutePlan }

class DefaultRouteResolver(private val config: RuleConfig, private val profiles: List<ProxyProfile>,
    private val health: Map<String, NodeHealth> = emptyMap(), private val now: Long = System.currentTimeMillis()) : RouteResolver {
    private val rules = config.rules.withIndex().sortedWith(compareBy(
        { it.value.match.rank() }, { it.value.priority ?: Int.MAX_VALUE }, { it.index },
    )).map { it.value }
    private fun expand(route: RouteTarget): List<RouteTarget> = when (route.type) {
        RouteType.DIRECT -> listOf(RouteTarget.DIRECT)
        RouteType.PROXY -> profiles.filter { it.enabled && it.id == route.profile }.map { RouteTarget.proxy(it.id) }
        RouteType.AUTO -> (route.`try`.ifEmpty { listOf("DIRECT") + profiles.filter { it.enabled }.map { it.id } })
            .mapNotNull { id -> if (id == "DIRECT") RouteTarget.DIRECT
                else profiles.firstOrNull { it.id == id && it.enabled }?.let { RouteTarget.proxy(id) } }.distinct()
    }
    private fun preferredProxy(route: RouteTarget, channel: Channel): List<RouteTarget> {
        val primary = profiles.firstOrNull { it.id == route.profile }
        val region = primary?.let { playbackRegion(it.name) } ?: channel.playbackRegion()?.takeUnless { it == PlaybackRegion.MAINLAND }
        val backups = region?.let { orderedRegionNodes(profiles, it, health, now).map { p -> RouteTarget.proxy(p.id) } }.orEmpty()
        return (expand(route) + backups).distinct()
    }
    /** Google device provisioning has no channel region entitlement or user license headers. */
    fun provisioningTargets(context: StreamContext, primary: RouteTarget): List<RouteTarget> {
        val region = primary.profile?.let { id -> profiles.firstOrNull { it.id == id }?.let { playbackRegion(it.name) } }
            ?: context.channel.playbackRegion()?.takeUnless { it == PlaybackRegion.MAINLAND }
        val backup = region?.let { orderedRegionNodes(profiles, it, health, now) }.orEmpty()
            .map { RouteTarget.proxy(it.id) }.firstOrNull { it != primary }
        return listOfNotNull(primary, RouteTarget.DIRECT, backup).distinct()
    }
    override fun resolve(context: StreamContext): RoutePlan {
        val rule = rules.firstOrNull { it.match.matches(context) }
        val manual = context.stream.manualRoute ?: context.channel.manualRoute
        // Manual AUTO asks the rule engine for its ordered strategy; forced DIRECT/PROXY remain authoritative.
        val route = manual?.takeUnless { it.type == RouteType.AUTO } ?: rule?.route ?: config.defaultRoute
        val region = context.channel.playbackRegion().takeIf { rule == null && (manual == null || manual.type == RouteType.AUTO) }
        val regional = region?.let { orderedRegionNodes(profiles, it, health, now).map { p -> p.id } }.orEmpty()
        // Public international services can use direct access when no matching node exists.
        // Existing HK/TW/JP/KR entitlement routing remains unchanged.
        val publicRegion = region in setOf(PlaybackRegion.SINGAPORE, PlaybackRegion.UNITED_STATES,
            PlaybackRegion.UNITED_KINGDOM, PlaybackRegion.THAILAND)
        if (region != null && region != PlaybackRegion.MAINLAND && regional.isEmpty() && !publicRegion) return RoutePlan(emptyList())
        val selected = when {
            region == PlaybackRegion.MAINLAND || (publicRegion && regional.isEmpty()) -> RouteTarget.DIRECT
            region != null -> RouteTarget(RouteType.AUTO, `try` = regional)
            manual?.type == RouteType.AUTO && route.type == RouteType.DIRECT && rule == null -> RouteTarget.AUTO
            else -> route
        }
        val reason = when {
            context.stream.manualRoute?.type != null && context.stream.manualRoute.type != RouteType.AUTO -> "线路手动设置"
            context.stream.manualRoute == null && context.channel.manualRoute?.type != null && context.channel.manualRoute.type != RouteType.AUTO -> "频道手动设置"
            rule != null -> rule.id
            region != null -> "地区自动路由：${region.label}"
            manual?.type == RouteType.AUTO -> "手动 AUTO"
            else -> "defaultRoute"
        }
        val targets = if (manual?.type == RouteType.PROXY) preferredProxy(selected, context.channel) else expand(selected)
        return RoutePlan(targets.map { target -> RouteAttempt(context.stream.id, context.stream.url, target,
            if (manual?.type == RouteType.PROXY && target.profile != manual.profile) "$reason · 同地区保底" else reason) })
    }
    fun channelPlan(channel: Channel, history: SuccessfulRoute?, onlyStreamId: String? = null): RoutePlan {
        val attempts = channel.streams.filter { onlyStreamId == null || it.id == onlyStreamId }
            .flatMap { resolve(StreamContext(channel, it)).attempts }.distinctBy { it.streamId to it.target }
        val preferred = history?.let { h -> attempts.firstOrNull { it.streamId == h.streamId && it.target == h.target } }
        if (attempts.any { it.matchedRule.startsWith("地区自动路由：") || it.matchedRule.contains("手动设置") }) {
            // Retain preferred source without letting old successful nodes outrank current latency results.
            val streamId = preferred?.streamId
            return RoutePlan(if (streamId != null) attempts.filter { it.streamId == streamId } + attempts.filter { it.streamId != streamId } else attempts)
        }
        return RoutePlan(if (preferred != null) listOf(preferred) + attempts.filterNot { it == preferred } else attempts)
    }
    fun requestTarget(context: StreamContext, requestUrl: String, sessionTarget: RouteTarget): RouteTarget {
        // Forced manual routes also cover HLS children and redirects.
        val manual = context.stream.manualRoute ?: context.channel.manualRoute
        if (manual != null && manual.type != RouteType.AUTO) return sessionTarget
        val request = context.copy(stream = context.stream.copy(url = requestUrl))
        val rule = rules.firstOrNull { it.match.isRequestRule() && it.match.matches(request) } ?: return sessionTarget
        // AUTO at a child URL must not restart the parent's failover cycle.
        return if (rule.route.type == RouteType.AUTO) sessionTarget else rule.route
    }
    /** Fixed request rules are preferred nodes; failures use same-region backups, never restart AUTO. */
    fun requestTargets(context: StreamContext, requestUrl: String, sessionTarget: RouteTarget): List<RouteTarget> {
        val manual = context.stream.manualRoute ?: context.channel.manualRoute
        if (manual != null && manual.type != RouteType.AUTO) return listOf(sessionTarget)
        val request = context.copy(stream = context.stream.copy(url = requestUrl))
        val rule = rules.firstOrNull { it.match.isRequestRule() && it.match.matches(request) }
        return if (rule?.route?.type == RouteType.PROXY) preferredProxy(rule.route, context.channel)
            else listOf(requestTarget(context, requestUrl, sessionTarget))
    }
}

package com.tvatlas.core.routing

import com.tvatlas.core.model.*

interface RouteResolver { fun resolve(context: StreamContext): RoutePlan }

class DefaultRouteResolver(private val config: RuleConfig, private val profiles: List<ProxyProfile>) : RouteResolver {
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
    override fun resolve(context: StreamContext): RoutePlan {
        val rule = rules.firstOrNull { it.match.matches(context) }
        val manual = context.stream.manualRoute ?: context.channel.manualRoute
        // Manual AUTO asks the rule engine for its ordered strategy; forced DIRECT/PROXY remain authoritative.
        val route = manual?.takeUnless { it.type == RouteType.AUTO } ?: rule?.route ?: config.defaultRoute
        val selected = if (manual?.type == RouteType.AUTO && route.type == RouteType.DIRECT && rule == null)
            RouteTarget.AUTO else route
        val reason = when {
            context.stream.manualRoute?.type != null && context.stream.manualRoute.type != RouteType.AUTO -> "线路手动设置"
            context.stream.manualRoute == null && context.channel.manualRoute?.type != null && context.channel.manualRoute.type != RouteType.AUTO -> "频道手动设置"
            rule != null -> rule.id
            manual?.type == RouteType.AUTO -> "手动 AUTO"
            else -> "defaultRoute"
        }
        return RoutePlan(expand(selected).map { RouteAttempt(context.stream.id, context.stream.url, it, reason) })
    }
    fun channelPlan(channel: Channel, history: SuccessfulRoute?, onlyStreamId: String? = null): RoutePlan {
        val attempts = channel.streams.filter { onlyStreamId == null || it.id == onlyStreamId }
            .flatMap { resolve(StreamContext(channel, it)).attempts }.distinctBy { it.streamId to it.target }
        val preferred = history?.let { h -> attempts.firstOrNull { it.streamId == h.streamId && it.target == h.target } }
        return RoutePlan(if (preferred != null) listOf(preferred) + attempts.filterNot { it == preferred } else attempts)
    }
    fun requestTarget(context: StreamContext, requestUrl: String, sessionTarget: RouteTarget): RouteTarget {
        // Forced manual routes also cover HLS children and redirects.
        val manual = context.stream.manualRoute ?: context.channel.manualRoute
        if (manual != null && manual.type != RouteType.AUTO) return manual
        val request = context.copy(stream = context.stream.copy(url = requestUrl))
        val rule = rules.firstOrNull { it.match.isRequestRule() && it.match.matches(request) } ?: return sessionTarget
        // AUTO at a child URL must not restart the parent's failover cycle.
        return if (rule.route.type == RouteType.AUTO) sessionTarget else rule.route
    }
}

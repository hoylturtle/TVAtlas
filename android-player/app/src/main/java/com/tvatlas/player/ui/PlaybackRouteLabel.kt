package com.tvatlas.player.ui

import com.tvatlas.core.model.ProxyProfile
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.model.RouteType
import com.tvatlas.player.storage.SubscriptionRow

internal fun playbackRouteLabel(target: RouteTarget?, profiles: List<ProxyProfile>, subscriptions: List<SubscriptionRow>): String = when (target?.type) {
    RouteType.DIRECT -> "直连"
    RouteType.AUTO -> "自动选择代理"
    RouteType.PROXY -> profiles.firstOrNull { it.id == target.profile }?.let { node ->
        val subscription = subscriptions.firstOrNull { it.id == node.subscriptionId }?.name
        "${subscription ?: if (node.subscriptionId == null) "手动代理" else "订阅代理"} · ${node.name}"
    } ?: "节点不可用"
    null -> ""
}

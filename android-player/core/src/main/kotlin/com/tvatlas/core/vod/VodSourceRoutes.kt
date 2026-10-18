package com.tvatlas.core.vod

import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.model.RouteType

/** Source choices are independent. New sources always start DIRECT, never AUTO fallback. */
class VodSourceRoutes {
    private val routes = java.util.concurrent.ConcurrentHashMap<String, RouteTarget>()
    fun get(source: String): RouteTarget = routes[source] ?: RouteTarget.DIRECT
    fun set(source: String, target: RouteTarget) {
        require(target.type != RouteType.AUTO) { "点播测试需明确选择直连或节点" }
        routes[source] = target
    }
}

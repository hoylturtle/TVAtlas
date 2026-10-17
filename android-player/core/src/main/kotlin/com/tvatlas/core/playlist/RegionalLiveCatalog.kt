package com.tvatlas.core.playlist

import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.browseRegion

/** Small bundled repairs override only matching overseas channels in the built-in subscription. */
object RegionalLiveCatalog {
    private val regions = setOf("澳门", "台湾", "日本", "韩国", "新加坡", "美国", "英国", "泰国")
    fun merge(base: List<Channel>, repairs: List<Channel>): List<Channel> {
        val valid = repairs.filter { it.browseRegion() in regions && it.streams.isNotEmpty() }
        val names = valid.map { it.browseRegion() to it.name }.toSet()
        val ids = valid.mapNotNull { it.tvgId?.let { id -> it.browseRegion() to id } }.toSet()
        return base.filterNot { channel ->
            channel.browseRegion() in regions && ((channel.browseRegion() to channel.name) in names ||
                channel.tvgId?.let { (channel.browseRegion() to it) in ids } == true)
        } + valid
    }
}

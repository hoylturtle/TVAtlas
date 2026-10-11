package com.tvatlas.core.model

private val hongKongLabel = Regex("(?i)(?<![a-z])(?:hk|hkg|hong[ _-]*kong)(?![a-z])")

fun isHongKongLabel(value: String): Boolean =
    value.contains("香港") || value.contains("🇭🇰") || hongKongLabel.containsMatchIn(value)

fun Channel.isHongKong(): Boolean = isHongKongLabel(group) || tvgId?.endsWith(".hk", true) == true

/** Stable sort keeps the playlist order within each region. */
fun List<Channel>.hongKongFirst(): List<Channel> = sortedBy { if (it.isHongKong()) 0 else 1 }

package com.tvatlas.player.transfer
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal fun lanAddress(context: Context): Inet4Address? {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.allNetworks.asSequence().filter {
        val caps = manager.getNetworkCapabilities(it)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
        .mapNotNull { it.address as? Inet4Address }.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
}

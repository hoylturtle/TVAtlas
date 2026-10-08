package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import com.tvatlas.player.storage.CredentialVault
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class ProxyClientPool(private val vault: CredentialVault) {
    private var profiles = emptyList<ProxyProfile>()
    private val clients = mutableMapOf<String, OkHttpClient>()
    val direct: OkHttpClient = builder().proxy(Proxy.NO_PROXY).build()
    private fun builder() = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)

    @Synchronized fun update(profiles: List<ProxyProfile>, credentialsChanged: Boolean = false) {
        if (this.profiles == profiles && !credentialsChanged) return
        this.profiles = profiles
        clients.values.forEach { it.connectionPool.evictAll() }; clients.clear()
    }
    @Synchronized fun client(target: RouteTarget): OkHttpClient {
        if (target.type == RouteType.DIRECT) return direct
        if (target.type != RouteType.PROXY) throw IOException("Route must be concrete")
        val p = profiles.firstOrNull { it.id == target.profile && it.enabled } ?: throw IOException("Proxy unavailable")
        return clients.getOrPut(p.id) {
            val credentials = try { vault.get(p.id) } catch (_: Exception) { throw IOException("Proxy credentials unavailable") }
            builder().apply {
                when (p.type) {
                    ProxyType.HTTP -> {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(p.host, p.port)))
                        if (credentials != null) proxyAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) null else response.request.newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(credentials.username, credentials.password)).build()
                        }
                    }
                    ProxyType.SOCKS5 -> {
                        proxy(Proxy.NO_PROXY)
                        socketFactory(Socks5SocketFactory(p, credentials))
                        // Preserve the requested hostname for the SOCKS handshake; the proxy resolves it remotely.
                        dns(Dns { hostname -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 1))) })
                    }
                }
            }.build()
        }
    }
    @Synchronized fun close() {
        (clients.values + direct).forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
        clients.clear()
    }
}

package com.tvatlas.player.proxy

import android.content.Context
import com.tvatlas.core.model.*
import com.tvatlas.core.subscription.MihomoConfig
import com.tvatlas.player.storage.CredentialVault
import com.tvatlas.player.storage.Credentials
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

data class CoreEndpoint(val port: Int, val credentials: Credentials)

/** Isolated application-layer core. No VPN, TUN, controller API or LAN listeners are enabled. */
class MihomoRuntime(private val context: Context, private val vault: CredentialVault) {
    private var profiles = emptyList<ProxyProfile>()
    private var process: Process? = null
    private var endpoints = emptyMap<String, CoreEndpoint>()
    private val executable get() = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
    private val home = File(context.noBackupFilesDir, "mihomo").apply { mkdirs() }

    @Synchronized fun update(value: List<ProxyProfile>) {
        val selected = value.filter { it.type == ProxyType.MIHOMO && it.enabled }.sortedBy { it.id }
        if (selected != profiles) { close(); profiles = selected }
    }
    private fun alive(p: Process): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    private fun launch(config: File, check: Boolean): Process {
        if (!executable.canExecute()) throw IOException("订阅内核不可用，请安装包含 Mihomo 的完整 APK")
        val args = mutableListOf(executable.absolutePath, "-d", home.absolutePath, "-f", config.absolutePath)
        if (check) args.add("-t")
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        Thread({ runCatching { p.inputStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { /* Never log core output: it can contain node credentials. */ } } } }, "mihomo-output").apply { isDaemon = true; start() }
        return p
    }
    fun validate(nodes: List<JsonObject>) {
        val config = File(home, "validate-${UUID.randomUUID()}.json")
        var p: Process? = null
        try {
            config.writeText(MihomoConfig.build(nodes, emptyMap(), "", ""))
            p = launch(config, true)
            val deadline = System.nanoTime() + 15_000_000_000L
            while (alive(p) && System.nanoTime() < deadline) Thread.sleep(25)
            if (alive(p) || p.exitValue() != 0) throw IOException("订阅节点无法通过内核校验")
        } finally { p?.destroy(); config.delete() }
    }
    @Synchronized fun endpoint(id: String): CoreEndpoint {
        if (profiles.none { it.id == id }) throw IOException("订阅节点不可用")
        if (process?.let(::alive) == true) return endpoints[id] ?: throw IOException("订阅节点未启动")
        close()
        val nodes = profiles.map { p ->
            val secret = vault.secret("node:${p.id}") ?: throw IOException("订阅节点密钥缺失")
            Json.parseToJsonElement(secret).jsonObject
        }
        val sockets = profiles.map { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        val ports = profiles.mapIndexed { i, p -> p.id to sockets[i].localPort }.toMap()
        val credentials = Credentials("tvatlas", UUID.randomUUID().toString())
        val config = File(home, "runtime-${UUID.randomUUID()}.json")
        try {
            config.writeText(MihomoConfig.build(nodes, ports, credentials.username, credentials.password))
            sockets.forEach { it.close() }
            val p = launch(config, false)
            process = p
            val deadline = System.nanoTime() + 8_000_000_000L
            var ready = false
            while (alive(p) && System.nanoTime() < deadline) {
                ready = ports.values.all { port -> runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess }
                if (ready) break
                Thread.sleep(50)
            }
            if (!ready) { close(); throw IOException("订阅内核启动失败") }
            endpoints = ports.mapValues { CoreEndpoint(it.value, credentials) }
            return endpoints.getValue(id)
        } finally { sockets.forEach { if (!it.isClosed) it.close() }; config.delete() }
    }
    @Synchronized fun close() { process?.destroy(); process = null; endpoints = emptyMap() }
}

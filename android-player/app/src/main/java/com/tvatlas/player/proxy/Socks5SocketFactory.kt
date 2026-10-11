package com.tvatlas.player.proxy

import com.tvatlas.core.model.ProxyProfile
import com.tvatlas.player.storage.Credentials
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Per-profile SOCKS5 authentication and remote DNS, without process-global Authenticator. */
class Socks5SocketFactory(private val profile: ProxyProfile, private val credentials: Credentials?) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val destination = endpoint as? InetSocketAddress ?: throw IOException("SOCKS destination invalid")
            super.connect(InetSocketAddress(profile.host, profile.port), timeout)
            val previousTimeout = soTimeout
            soTimeout = timeout.takeIf { it > 0 } ?: 8000
            try {
                val input = DataInputStream(getInputStream())
                val output = getOutputStream()
                output.write(if (credentials == null) byteArrayOf(5, 1, 0) else byteArrayOf(5, 1, 2)); output.flush()
                if (input.readUnsignedByte() != 5) throw IOException("SOCKS version invalid")
                when (input.readUnsignedByte()) {
                    0 -> if (credentials != null) throw IOException("SOCKS authentication required")
                    2 -> {
                        val c = credentials ?: throw IOException("SOCKS credentials required")
                        val user = c.username.toByteArray(Charsets.UTF_8)
                        val pass = c.password.toByteArray(Charsets.UTF_8)
                        if (user.size !in 1..255 || pass.size !in 1..255) throw IOException("SOCKS credentials invalid")
                        output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
                        output.write(pass.size); output.write(pass); output.flush()
                        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication failed")
                    }
                    else -> throw IOException("SOCKS method unavailable")
                }
                val host = destination.hostName.toByteArray(Charsets.UTF_8)
                if (host.size !in 1..255) throw IOException("SOCKS hostname invalid")
                output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte())); output.write(host)
                output.write(destination.port ushr 8); output.write(destination.port and 255); output.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("SOCKS connection rejected")
                input.readUnsignedByte()
                val length = when (input.readUnsignedByte()) { 1 -> 4; 3 -> input.readUnsignedByte(); 4 -> 16; else -> throw IOException("SOCKS address invalid") }
                val address = ByteArray(length + 2); input.readFully(address)
            } catch (error: IOException) { close(); throw error }
            finally { if (!isClosed) soTimeout = previousTimeout }
        }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket().apply {
        bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, localHost, localPort)
}

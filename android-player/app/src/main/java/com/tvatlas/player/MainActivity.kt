package com.tvatlas.player

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

data class Channel(val name: String, val group: String, val urls: MutableList<String>)
enum class Route { DIRECT, PROXY, AUTO }

object M3uParser {
    fun parse(text: String): List<Channel> {
        val channels = linkedMapOf<String, Channel>()
        var name = ""
        var group = ""
        val groupRegex = Regex("""group-title="([^"]*)"""")
        text.removePrefix("\uFEFF").lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(",").trim()
                    group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
                }
                line.startsWith("http://") || line.startsWith("https://") -> {
                    if (name.isNotBlank()) {
                        val channel = channels.getOrPut(name) { Channel(name, group, mutableListOf()) }
                        if (line !in channel.urls) channel.urls.add(line)
                    }
                    name = ""
                }
            }
        }
        return channels.values.toList()
    }
}

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tvatlas", MODE_PRIVATE) }
    private var channels = listOf<Channel>()
    private var player: ExoPlayer? = null
    private lateinit var video: PlayerView
    private lateinit var channelList: ListView
    private lateinit var status: TextView
    private lateinit var urlInput: EditText
    private lateinit var proxyHost: EditText
    private lateinit var proxyPort: EditText
    private lateinit var proxyType: Spinner
    private lateinit var routePicker: Spinner
    private var currentChannel: Channel? = null
    private var lineIndex = 0
    private var currentRoute = Route.DIRECT
    private var attemptProxy = false
    private var lastAttempt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urlInput = EditText(this).apply {
            hint = "M3U 订阅地址"
            setSingleLine()
            setText(prefs.getString("playlist", "https://raw.githubusercontent.com/hoylturtle/TVAtlas/main/tvatlas.m3u"))
        }
        root.addView(urlInput)
        val load = Button(this).apply { text = "加载订阅"; setOnClickListener { loadPlaylist() } }
        root.addView(load)
        val proxyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        proxyHost = EditText(this).apply {
            hint = "代理地址"
            setSingleLine()
            setText(prefs.getString("host", ""))
            layoutParams = LinearLayout.LayoutParams(0, -2, 2f)
        }
        proxyPort = EditText(this).apply {
            hint = "端口"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("port", ""))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        proxyType = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("HTTP", "SOCKS"))
            setSelection(prefs.getInt("proxy_type", 0))
        }
        proxyRow.addView(proxyHost)
        proxyRow.addView(proxyPort)
        proxyRow.addView(proxyType)
        root.addView(proxyRow)
        val save = Button(this).apply { text = "保存代理配置"; setOnClickListener { saveProxy() } }
        root.addView(save)
        routePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Route.entries.map { it.name })
        }
        root.addView(routePicker)
        status = TextView(this).apply { text = "选择频道后可设置 DIRECT / PROXY / AUTO" }
        root.addView(status)
        video = PlayerView(this).apply {
            useController = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 3f)
        }
        root.addView(video)
        channelList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 2f)
            setOnItemClickListener { _, _, position, _ ->
                currentChannel = channels[position]
                routePicker.setSelection(Route.valueOf(prefs.getString("route_${channels[position].name}", "AUTO")!!).ordinal)
                playChannel(channels[position])
            }
        }
        root.addView(channelList)
        val apply = Button(this).apply {
            text = "保存当前频道路由并重播"
            setOnClickListener {
                currentChannel?.let {
                    prefs.edit().putString("route_${it.name}", Route.entries[routePicker.selectedItemPosition].name).apply()
                    playChannel(it)
                }
            }
        }
        root.addView(apply)
        setContentView(root)
        loadPlaylist()
    }

    private fun saveProxy() {
        prefs.edit().putString("host", proxyHost.text.toString().trim())
            .putString("port", proxyPort.text.toString().trim())
            .putInt("proxy_type", proxyType.selectedItemPosition).apply()
        Toast.makeText(this, "代理配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun loadPlaylist() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            status.text = "请输入 HTTP(S) 订阅地址"
            return
        }
        prefs.edit().putString("playlist", url).apply()
        status.text = "正在加载..."
        executor.execute {
            try {
                val text = URL(url).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                }.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = M3uParser.parse(text)
                runOnUiThread {
                    channels = parsed
                    channelList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, parsed.map { "${it.group}  ${it.name} (${it.urls.size}线)" })
                    status.text = "已加载 ${parsed.size} 个频道"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "加载失败: ${e.message}" }
            }
        }
    }

    private fun playChannel(channel: Channel) {
        player?.release()
        player = null
        lineIndex = 0
        attemptProxy = false
        val configured = Route.valueOf(prefs.getString("route_${channel.name}", "AUTO")!!)
        currentRoute = if (configured == Route.AUTO) {
            Route.valueOf(prefs.getString("last_route_${channel.name}", "DIRECT")!!)
        } else configured
        playAttempt(channel, configured)
    }

    private fun playAttempt(channel: Channel, configured: Route) {
        if (lineIndex >= channel.urls.size) {
            status.text = "${channel.name}: 所有线路均失败"
            return
        }
        val route = currentRoute
        val host = proxyHost.text.toString().trim()
        val port = proxyPort.text.toString().toIntOrNull()
        if (route == Route.PROXY && (host.isBlank() || port == null || port !in 1..65535)) {
            status.text = "代理未配置，请填写地址和端口"
            return
        }
        val client = OkHttpClient.Builder().apply {
            connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            if (route == Route.PROXY) {
                val type = if (proxyType.selectedItemPosition == 1) Proxy.Type.SOCKS else Proxy.Type.HTTP
                proxy(Proxy(type, InetSocketAddress(host, port!!)))
            } else proxy(Proxy.NO_PROXY)
        }.build()
        val factory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(client))
        player?.release()
        val instance = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        player = instance
        video.player = instance
        val url = channel.urls[lineIndex]
        lastAttempt = "${channel.name} ${lineIndex + 1}/${channel.urls.size} ${route.name}"
        status.text = "正在播放: $lastAttempt"
        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (instance !== player) return
                if (state == Player.STATE_READY) {
                    status.text = "播放成功: $lastAttempt"
                    if (configured == Route.AUTO) {
                        prefs.edit().putString("last_route_${channel.name}", route.name).apply()
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (instance !== player) return
                if (configured == Route.AUTO && !attemptProxy && route == Route.DIRECT && host.isNotBlank()) {
                    attemptProxy = true
                    currentRoute = Route.PROXY
                } else {
                    attemptProxy = false
                    lineIndex++
                    currentRoute = if (configured == Route.AUTO) Route.DIRECT else configured
                }
                video.post { playAttempt(channel, configured) }
            }
        })
        instance.setMediaItem(MediaItem.fromUri(url))
        instance.prepare()
        instance.playWhenReady = true
    }

    override fun onDestroy() {
        player?.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}

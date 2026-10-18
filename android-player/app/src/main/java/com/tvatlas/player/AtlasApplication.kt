package com.tvatlas.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder

/** App-private, bounded persistent logo cache; never included in configuration backups. */
class AtlasApplication : Application(), ImageLoaderFactory {
    lateinit var debugLog: com.tvatlas.player.debug.DebugLog
        private set
    internal val channelBrowseCache by lazy { com.tvatlas.player.browse.ChannelBrowseCache(java.io.File(noBackupFilesDir, "channel-browse.json")) }
    lateinit var startupDiagnostics: com.tvatlas.player.debug.StartupDiagnostics
        private set
    override fun onCreate() {
        super.onCreate()
        debugLog = com.tvatlas.player.debug.DebugLog(java.io.File(noBackupFilesDir, "debug/player.log"),
            "TVAtlas ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        debugLog.event("INFO", "STARTUP", "application created elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()}")
        startupDiagnostics = com.tvatlas.player.debug.StartupDiagnostics(debugLog)
    }
    override fun newImageLoader(): ImageLoader = createArtworkImageLoader(this)
}

internal fun createArtworkImageLoader(context: android.content.Context, directory: java.io.File = java.io.File(context.noBackupFilesDir, "channel-artwork")): ImageLoader = ImageLoader.Builder(context)
        .components { add(SvgDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(directory)
            .maxSizeBytes(128L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()


package com.tvatlas.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder

/** App-private, bounded persistent logo cache; never included in configuration backups. */
class AtlasApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = createArtworkImageLoader(this)
}

internal fun createArtworkImageLoader(context: android.content.Context, directory: java.io.File = java.io.File(context.noBackupFilesDir, "channel-artwork")): ImageLoader = ImageLoader.Builder(context)
        .components { add(SvgDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(directory)
            .maxSizeBytes(128L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()


package com.tvatlas.player.ui

import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArtworkCacheTest {
    @Test fun downloadedLogoSurvivesNewLoaderWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "logo-test-${System.nanoTime()}")
        val server = MockWebServer()
        server.start()
        val url = server.url("/logo.png").toString()
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setHeader("Cache-Control", "no-store")
            .setBody(okio.Buffer().write(bytes)))
        fun loader() = com.tvatlas.player.createArtworkImageLoader(context, directory)
        var current = loader()
        try {
            val request = ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build()
            assertTrue(current.execute(request) is SuccessResult)
            assertEquals(1, server.requestCount)
            current.shutdown()
            server.shutdown()
            current = loader()
            val result = current.execute(request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(result is SuccessResult)
            assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
        } finally {
            current.shutdown()
            runCatching { server.shutdown() }
            directory.deleteRecursively()
        }
    }
}

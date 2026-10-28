package com.tvatlas.player.vod

import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.storage.CredentialVault
import com.tvatlas.player.vod.stremio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StremioAddonStoreTest {
    @Test fun encryptedConfigurationPreservesDisabledAndRemovedStateAndRejectsBadUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stremio-addon-test-${System.nanoTime()}"
        val vault = CredentialVault(context, name, "tvatlas.stremio.test.v1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        MockWebServer().use { server ->
            try {
                server.enqueue(MockResponse().setBody("""{"id":"test","name":"Test","types":["movie"],"resources":["stream"],"catalogs":[]}"""))
                val url = server.url("/private-key/manifest.json").toString()
                val first = StremioAddonStore(vault, scope)
                withTimeout(5000) { first.state.first { !it.loading } }
                assertEquals(1, first.state.value.entries.size)
                first.add(url, OkHttpClient())
                val added = first.state.value.entries.single { it.address == url }
                assertFalse(added.toString().contains("private-key"))
                first.enable(added.source, false)
                val reopened = StremioAddonStore(vault, scope)
                withTimeout(5000) { reopened.state.first { !it.loading } }
                assertNull(reopened.find(added.source))
                assertFalse(context.getSharedPreferences(name, 0).all.toString().contains("private-key"))
                server.enqueue(MockResponse().setBody("private-key<html>login</html>"))
                reopened.add(url, OkHttpClient())
                assertNotNull(reopened.state.value.error)
                assertFalse(reopened.state.value.error!!.contains("private-key"))
                assertEquals(2, reopened.state.value.entries.size)
                reopened.state.value.entries.toList().forEach { reopened.remove(it.source) }
                val empty = StremioAddonStore(vault, scope)
                withTimeout(5000) { empty.state.first { !it.loading } }
                assertTrue(empty.state.value.entries.isEmpty())
            } finally { scope.cancel(); context.getSharedPreferences(name, 0).edit().clear().commit() }
        }
    }
}

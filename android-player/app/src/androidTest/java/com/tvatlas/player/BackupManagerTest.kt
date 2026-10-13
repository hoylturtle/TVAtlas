package com.tvatlas.player

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupManagerTest {
    @Test fun encryptedRestorePreservesSourcesCredentialsFavoritesAndHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("backup-fixture-$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        val vault = CredentialVault(isolated); val settings = SettingsStore(context); val old = settings.snapshot()
        try {
            val dao = db.dao()
            dao.savePlaylist(PlaylistRow("p", "Original", "https://example.com/list.m3u"))
            dao.saveChannel(ChannelRow("c", "Channel", "香港", null, "C.hk"))
            dao.saveStreams(listOf(StreamRow("s", "c", "https://example.com/live.m3u8", "p")))
            dao.saveProxies(listOf(ProxyRow("fixture", "香港", "HTTP", "example.com", 8080, true)))
            dao.saveHistory(HistoryRow("c", "s", "{\"type\":\"DIRECT\"}", 1234))
            vault.put("fixture", Credentials("user", "private-password"))
            settings.editOptions { it.copy(favorites = setOf("c")) }
            val manager = BackupManager(db, vault, settings)
            val archive = manager.export("test-password".toCharArray())
            assertFalse(archive.toString(Charsets.ISO_8859_1).contains("private-password"))
            dao.savePlaylist(PlaylistRow("p", "Changed", "https://example.com/list.m3u")); settings.editOptions { it.copy(favorites = emptySet()) }
            vault.put("fixture", Credentials("changed", "changed"))
            manager.restore(archive, "test-password".toCharArray())
            assertEquals("Original", dao.playlist("p")!!.name)
            assertEquals("private-password", vault.get("fixture")!!.password)
            assertEquals(setOf("c"), settings.snapshot().options.favorites)
            assertEquals(1234L, dao.history("c")!!.at)
            try { manager.restore(archive, "wrong-password".toCharArray()); fail("wrong accepted") } catch (_: java.security.GeneralSecurityException) { }
            assertEquals("Original", dao.playlist("p")!!.name)
            assertEquals("private-password", vault.get("fixture")!!.password)
        } finally { settings.restore(old); vault.replace(emptyMap()); db.close() }
    }
}

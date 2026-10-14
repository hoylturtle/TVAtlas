package com.tvatlas.player

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.*
import com.tvatlas.player.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RuleFilesTest {
    @Test fun onlyOneFileActiveAndBuiltInFallbackSurvivesSwitching() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        try {
            val repository = PlayerRepository(db, OkHttpClient())
            val a = RuleConfig(name = "A", proxies = listOf(ProxyProfile("file-node", "文件节点", ProxyType.HTTP, "a.example", 80)), rules = listOf(Rule("a", Match(group = "台湾"), RouteTarget.DIRECT)))
            val b = RuleConfig(name = "B", proxies = listOf(ProxyProfile("file-node", "文件节点", ProxyType.HTTP, "b.example", 80)), rules = listOf(Rule("b", Match(group = "日本"), RouteTarget.DIRECT)))
            repository.importRules(RuleCodec.export(a), emptyList(), "A.json")
            repository.importRules(RuleCodec.export(b), emptyList(), "B.json")
            var files = repository.ruleFiles.first()
            assertEquals(2, files.size)
            assertEquals("B.json", files.single { it.active }.name)
            repository.selectRuleFile(files.first { it.name == "A.json" }.id)
            files = repository.ruleFiles.first()
            assertEquals(1, files.count { it.active })
            assertEquals("A.json", files.single { it.active }.name)
            assertEquals("a.example", repository.library.first().profiles.single { it.id == "file-node" }.host)
            val stream = Stream("s", "https://example.com/live", "p")
            val node = ProxyProfile("jp", "日本", ProxyType.HTTP, "host", 443)
            val state = repository.library.first()
            val plan = DefaultRouteResolver(state.rules, listOf(node)).channelPlan(Channel("c", "test", "日本", streams = listOf(stream)), null)
            assertEquals("jp", plan.attempts.single().target.profile)
            repository.selectRuleFile(null)
            assertTrue(repository.ruleFiles.first().none { it.active })
            assertTrue(repository.library.first().rules.rules.isEmpty())
            assertEquals(2, repository.ruleFiles.first().size)
        } finally { db.close() }
    }
    @Test fun migrationPreservesLegacyRuleAndChannelState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rule-migration-${System.nanoTime()}.db"
        val config = RuleCodec.export(RuleConfig(name = "legacy", rules = listOf(Rule("legacy", Match(group = "台湾"), RouteTarget.DIRECT))))
        var db = Room.databaseBuilder(context, PlayerDatabase::class.java, name).build()
        try {
            db.dao().saveRules(RulesRow(json = config))
            db.dao().saveChannel(ChannelRow("saved", "频道", "香港", null, null, "saved-route"))
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use {
                it.execSQL("DROP TABLE rule_files")
                // Recreate the actual v2 column layout, not a v5 database with only its version changed.
                it.execSQL("ALTER TABLE streams DROP COLUMN headers")
                listOf("uploadBytes", "downloadBytes", "totalBytes", "expireAt", "quotaUpdatedAt").forEach { column ->
                    it.execSQL("ALTER TABLE subscriptions DROP COLUMN $column")
                }
                it.execSQL("PRAGMA user_version=2")
            }
            db = Room.databaseBuilder(context, PlayerDatabase::class.java, name).addMigrations(PlayerDatabase.MIGRATION_2_3, PlayerDatabase.MIGRATION_3_4, PlayerDatabase.MIGRATION_4_5).build()
            assertEquals(config, db.dao().ruleFiles().first().single().json)
            assertTrue(db.dao().ruleFiles().first().single().active)
            assertEquals("saved-route", db.dao().channel("saved")!!.manualRoute)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

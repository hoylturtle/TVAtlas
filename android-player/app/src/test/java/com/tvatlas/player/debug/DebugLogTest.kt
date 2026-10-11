package com.tvatlas.player.debug

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DebugLogTest {
    private fun file() = File(Files.createTempDirectory("tvatlas-log-test").toFile(), "player.log")
    @Test fun removesUrlsJwtHeadersAndQueryCredentials() {
        val text = "https://user:pass@example.com/private?sig=signature-secret token=plain-secret\n" +
            "Authorization: Bearer header-secret\nCookie: auth=cookie-secret; other=second-secret\n" +
            "abcdefghijk.abcdefghijkl.abcdefghijk\nsig=query-secret"
        val result = DebugLog.sanitize(text)
        for (secret in listOf("user:pass", "signature-secret", "plain-secret", "header-secret", "cookie-secret", "second-secret", "abcdefghijk", "query-secret"))
            assertFalse(secret, result.contains(secret))
        assertFalse(result.contains('\n'))
    }
    @Test fun writesRedactedHistoryAndRestoresAfterRestart() {
        val file = file()
        val log = DebugLog(file, "fixture")
        log.event("INFO", "HTTP", "status=403 https://example.com?token=private")
        assertTrue(log.awaitWrites())
        log.close()
        val restored = DebugLog(file, "fixture")
        assertTrue(restored.awaitWrites())
        assertTrue(restored.snapshot().contains("status=403"))
        assertFalse(file.readText().contains("token=private"))
        restored.close()
    }
    @Test fun boundsMemoryAndDiskEvenForUnicodeFlood() {
        val file = file()
        val log = DebugLog(file, "fixture")
        repeat(700) { log.event("WARN", "MEDIA", "event=$it " + "港".repeat(1500)) }
        assertTrue(log.awaitWrites())
        assertTrue(log.lines.value.size <= DebugLog.MAX_LINES)
        assertTrue(file.length() <= DebugLog.MAX_BYTES)
        assertTrue(log.snapshot().contains("event=699"))
        assertFalse(log.snapshot().contains("event=0 "))
        log.close()
    }
    @Test fun clearRemovesSavedHistoryIncludingDuringStartup() {
        val file = file()
        file.writeText("old-private-history")
        val log = DebugLog(file, "fixture")
        log.clear()
        assertTrue(log.awaitWrites())
        assertTrue(log.lines.value.isEmpty())
        assertEquals("", file.readText())
        log.close()
    }
    @Test fun exceptionsDoNotLogMessagesAndResponseBodies() {
        val file = file()
        val log = DebugLog(file, "fixture")
        log.error("HTTP", java.io.IOException("raw-response-secret token=private"))
        assertTrue(log.snapshot().contains("IOException"))
        assertFalse(log.snapshot().contains("raw-response-secret"))
        log.close()
    }
    @Test fun concurrentWritesStayBoundedAndPersistLatestEvents() {
        val file = file()
        val log = DebugLog(file, "fixture")
        val threads = (0..3).map { id -> Thread { repeat(100) { log.event("INFO", "WORKER", "worker=$id event=$it") } }.apply { start() } }
        threads.forEach { it.join() }
        log.awaitWrites()
        for (id in 0..3) assertTrue(file.readText().contains("worker=$id event=99"))
        assertEquals(log.lines.value.joinToString("\n", postfix = "\n"), file.readText())
        log.close()
    }
}

package com.tvatlas.core.backup
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    @Test fun roundTripUsesFreshSaltAndIvWithoutPlaintext() {
        val clear = "subscription-private-token and proxy-password".toByteArray()
        val first = BackupCipher.encrypt(clear, "test-password".toCharArray())
        val second = BackupCipher.encrypt(clear, "test-password".toCharArray())
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("proxy-password"))
        assertArrayEquals(clear, BackupCipher.decrypt(first, "test-password".toCharArray()))
    }
    @Test fun incorrectPasswordAndTamperingAreRejected() {
        val archive = BackupCipher.encrypt("configuration".toByteArray(), "test-password".toCharArray())
        try { BackupCipher.decrypt(archive, "wrong-password".toCharArray()); fail("wrong password accepted") } catch (_: java.security.GeneralSecurityException) { }
        archive[archive.lastIndex] = (archive.last().toInt() xor 1).toByte()
        try { BackupCipher.decrypt(archive, "test-password".toCharArray()); fail("tamper accepted") } catch (_: java.security.GeneralSecurityException) { }
    }
    @Test fun invalidArchivesAndWeakPasswordsAreRejected() {
        try { BackupCipher.encrypt(byteArrayOf(1), "short".toCharArray()); fail("weak accepted") } catch (_: IllegalArgumentException) { }
        try { BackupCipher.decrypt(ByteArray(52), "test-password".toCharArray()); fail("bad header accepted") } catch (_: IllegalArgumentException) { }
    }
}

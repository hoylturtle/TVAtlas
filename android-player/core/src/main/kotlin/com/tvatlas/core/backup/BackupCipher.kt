package com.tvatlas.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, bounded AES-GCM archive; password is never persisted by the app. */
object BackupCipher {
    const val MAX_BYTES = 24 * 1024 * 1024
    private val magic = "TVATLAS1".toByteArray(Charsets.US_ASCII)
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(clear: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "备份密码至少 8 个字符" }; require(clear.size <= MAX_BYTES)
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); updateAAD(magic) }
        return magic + salt + iv + cipher.doFinal(clear)
    }
    fun decrypt(archive: ByteArray, password: CharArray): ByteArray {
        require(archive.size in 52..(MAX_BYTES + 52) && archive.copyOfRange(0, 8).contentEquals(magic)) { "不是有效的 TVAtlas 备份" }
        val salt = archive.copyOfRange(8, 24); val iv = archive.copyOfRange(24, 36)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); updateAAD(magic) }
            .doFinal(archive.copyOfRange(36, archive.size))
    }
}

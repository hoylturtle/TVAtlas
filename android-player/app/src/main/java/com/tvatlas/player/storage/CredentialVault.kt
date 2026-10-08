package com.tvatlas.player.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer

data class Credentials(val username: String, val password: String)

/** Both username and password remain in non-backed-up app storage, encrypted by a device-local key. */
class CredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("proxy_credentials", Context.MODE_PRIVATE)
    private val alias = "tvatlas.proxy.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(id: String, credentials: Credentials?) {
        if (credentials == null) { preferences.edit().remove(id).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray()) }
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        val clear = ByteBuffer.allocate(4 + username.size + password.size).putInt(username.size).put(username).put(password).array()
        val encrypted = cipher.doFinal(clear)
        check(preferences.edit().putString(id, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun get(id: String): Credentials? {
        val stored = preferences.getString(id, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(id.toByteArray())
        }
        val data = ByteBuffer.wrap(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        val username = ByteArray(data.int).also { data.get(it) }.toString(Charsets.UTF_8)
        val password = ByteArray(data.remaining()).also { data.get(it) }.toString(Charsets.UTF_8)
        return Credentials(username, password)
    }
}

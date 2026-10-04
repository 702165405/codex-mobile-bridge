package io.github.codexmobilebridge.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted app state (sessions, drafts, pending operations); never store login passwords. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val secret by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bridge-state", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("bridge-state", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null } // Lost/restored Keystore state requires logging in again.
    }
    @Synchronized fun put(key: String, value: String?) {
        if(value == null) { prefs.edit().remove(key).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString(key,encoded).commit()) { "无法保存本地状态 / Could not save local state" }
    }
    fun connections(): List<Connection> = get("connections")?.let { runCatching { json.decodeFromString<List<Connection>>(it) }.getOrNull() } ?: emptyList()
    fun saveConnections(rows: List<Connection>) = put("connections",json.encodeToString(rows))
    fun draft(key: String) = get("draft:$key")?.let { runCatching { json.decodeFromString<Draft>(it) }.getOrNull() } ?: Draft()
    fun saveDraft(key: String, draft: Draft) = put("draft:$key",json.encodeToString(draft))
    fun removeConnection(id: String) {
        prefs.all.keys.filter { it.contains(id) }.forEach { put(it,null) }
        saveConnections(connections().filter { it.id != id })
    }
}

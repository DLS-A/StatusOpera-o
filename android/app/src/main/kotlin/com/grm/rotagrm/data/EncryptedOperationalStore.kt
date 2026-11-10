package com.grm.rotagrm.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.grm.rotagrm.settings.alertStorageId
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SessionIdentity(val userId: String, val sessionId: String) {
    val sessionTag: String get() = alertStorageId(sessionId)
}

/** Encrypted, atomic, internal-only files; the encryption key never leaves Keystore. */
class EncryptedOperationalStore(context: Context) {
    private val directory = File(context.filesDir, "operational_private")

    fun identity(): SessionIdentity? = synchronized(lock) {
        runCatching {
            val json = JSONObject(read("identity") ?: return@synchronized null)
            SessionIdentity(json.getString("userId"), json.getString("sessionId"))
        }.getOrNull()
    }

    fun setIdentity(identity: SessionIdentity) = synchronized(lock) {
        write("identity", JSONObject().put("userId", identity.userId).put("sessionId", identity.sessionId).toString())
    }

    fun snapshot(userId: String): String? = synchronized(lock) { read("snapshot_${alertStorageId(userId)}") }

    fun saveSnapshot(identity: SessionIdentity, json: String) = synchronized(lock) {
        check(identity() == identity) { "A sessão foi alterada." }
        write("snapshot_${alertStorageId(identity.userId)}", json)
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-z0-9_]+")))
        directory.mkdirs()
        return AtomicFile(File(directory, "$name.bin"))
    }

    private fun read(name: String): String? = runCatching {
        val payload = file(name).readFully()
        require(payload.size in 28..(10 * 1024 * 1024 + 28))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun write(name: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 10 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(bytes)
        val atomic = file(name)
        val stream = atomic.startWrite()
        try { stream.write(payload); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        private val lock = Any()
        private const val KEY = "rota-grm-operational-cache"
    }
}
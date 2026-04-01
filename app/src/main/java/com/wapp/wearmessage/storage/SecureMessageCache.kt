package com.wapp.wearmessage.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureMessageCache(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val secretKey by lazy { getOrCreateSecretKey() }
    @Volatile
    private var lastSavedDigest: String? = prefs.getString(PREF_KEY_CACHE_DIGEST, null)

    fun save(snapshot: CachedSyncSnapshot) {
        val plainPayload = snapshot.toJson().toString().toByteArray(Charsets.UTF_8)
        val digest = plainPayload.sha256B64()
        if (digest == lastSavedDigest) {
            return
        }
        val encryptedPayload = encrypt(plainPayload) ?: return
        prefs.edit()
            .putString(
                PREF_KEY_CACHE_BLOB,
                Base64.encodeToString(encryptedPayload, Base64.NO_WRAP),
            )
            .putString(PREF_KEY_CACHE_DIGEST, digest)
            .apply()
        lastSavedDigest = digest
    }

    fun load(): CachedSyncSnapshot? {
        val encodedPayload = prefs.getString(PREF_KEY_CACHE_BLOB, null) ?: return null
        val encryptedPayload =
            runCatching { Base64.decode(encodedPayload, Base64.NO_WRAP) }
                .getOrNull()
                ?: return clearAndNull()
        val plainPayload = decrypt(encryptedPayload) ?: return clearAndNull()
        val json =
            runCatching { JSONObject(String(plainPayload, Charsets.UTF_8)) }
                .getOrNull()
                ?: return clearAndNull()
        lastSavedDigest = prefs.getString(PREF_KEY_CACHE_DIGEST, null)
        return CachedSyncSnapshot.fromJson(json) ?: clearAndNull()
    }

    private fun clearAndNull(): CachedSyncSnapshot? {
        prefs.edit()
            .remove(PREF_KEY_CACHE_BLOB)
            .remove(PREF_KEY_CACHE_DIGEST)
            .apply()
        lastSavedDigest = null
        return null
    }

    private fun encrypt(plain: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: return null
            val encrypted = cipher.doFinal(plain)
            ByteBuffer.allocate(1 + iv.size + encrypted.size)
                .put(iv.size.toByte())
                .put(iv)
                .put(encrypted)
                .array()
        }.getOrNull()

    private fun decrypt(encryptedPayload: ByteArray): ByteArray? =
        runCatching {
            if (encryptedPayload.isEmpty()) return null
            val payloadBuffer = ByteBuffer.wrap(encryptedPayload)
            val ivLength = payloadBuffer.get().toInt() and 0xFF
            if (ivLength <= 0 || encryptedPayload.size <= 1 + ivLength) return null
            val iv = ByteArray(ivLength)
            payloadBuffer.get(iv)
            val encrypted = ByteArray(payloadBuffer.remaining())
            payloadBuffer.get(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.doFinal(encrypted)
        }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "wessage_secure_message_cache"
        private const val PREF_KEY_CACHE_BLOB = "cache_blob_v1"
        private const val PREF_KEY_CACHE_DIGEST = "cache_blob_digest_v1"
        private const val KEY_ALIAS = "wessage.message.cache.key.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_SIZE_BITS = 128
    }
}

data class CachedSyncSnapshot(
    val conversations: List<CachedConversation>,
    val messages: List<CachedMessage>,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("savedAtEpochMillis", System.currentTimeMillis())
            .put(
                "conversations",
                JSONArray().apply {
                    conversations.forEach { conversation ->
                        put(
                            JSONObject()
                                .put("id", conversation.id)
                                .put("title", conversation.title)
                                .put("participants", JSONArray(conversation.participants))
                                .put("lastMessage", conversation.lastMessage)
                                .put("lastUpdatedAtEpochMillis", conversation.lastUpdatedAtEpochMillis)
                                .put("unreadCount", conversation.unreadCount)
                                .put("muted", conversation.muted)
                        )
                    }
                },
            )
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(
                            JSONObject()
                                .put("id", message.id)
                                .put("conversationId", message.conversationId)
                                .put("senderId", message.senderId)
                                .put("senderName", message.senderName)
                                .put("body", message.body)
                                .put("timestamp", message.timestamp)
                                .put("status", message.status)
                                .put("localVersion", message.localVersion)
                                .put("outgoing", message.outgoing)
                        )
                    }
                },
            )

    companion object {
        private const val SCHEMA_VERSION = 1

        fun fromJson(json: JSONObject): CachedSyncSnapshot? {
            if (json.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
            val conversationsJson = json.optJSONArray("conversations") ?: return null
            val messagesJson = json.optJSONArray("messages") ?: return null
            val conversations =
                buildList {
                    for (index in 0 until conversationsJson.length()) {
                        val item = conversationsJson.optJSONObject(index) ?: continue
                        add(
                            CachedConversation(
                                id = item.optString("id"),
                                title = item.optString("title"),
                                participants =
                                    item.optJSONArray("participants")
                                        ?.toStringList()
                                        .orEmpty(),
                                lastMessage = item.optString("lastMessage"),
                                lastUpdatedAtEpochMillis = item.optLong("lastUpdatedAtEpochMillis"),
                                unreadCount = item.optInt("unreadCount"),
                                muted = item.optBoolean("muted", false),
                            )
                        )
                    }
                }
            val messages =
                buildList {
                    for (index in 0 until messagesJson.length()) {
                        val item = messagesJson.optJSONObject(index) ?: continue
                        add(
                            CachedMessage(
                                id = item.optString("id"),
                                conversationId = item.optString("conversationId"),
                                senderId = item.optString("senderId"),
                                senderName = item.optString("senderName"),
                                body = item.optString("body"),
                                timestamp = item.optString("timestamp"),
                                status = item.optString("status"),
                                localVersion = item.optInt("localVersion", 0),
                                outgoing = item.optBoolean("outgoing", false),
                            )
                        )
                    }
                }
            return CachedSyncSnapshot(
                conversations = conversations,
                messages = messages,
            )
        }
    }
}

data class CachedConversation(
    val id: String,
    val title: String,
    val participants: List<String>,
    val lastMessage: String,
    val lastUpdatedAtEpochMillis: Long,
    val unreadCount: Int,
    val muted: Boolean,
)

data class CachedMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val timestamp: String,
    val status: String,
    val localVersion: Int,
    val outgoing: Boolean,
)

private fun JSONArray.toStringList(): List<String> =
    buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }

private fun ByteArray.sha256B64(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return Base64.encodeToString(digest, Base64.NO_WRAP)
}

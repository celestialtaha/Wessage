package com.wapp.wearmessage.sync.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureSyncCodec(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val localKeys: KeyPair by lazy { getOrCreateLocalKeyPair() }
    private val deviceId: String by lazy { getOrCreateDeviceId() }

    fun createKeyExchangePayload(): ByteArray =
        JSONObject()
            .put("schemaVersion", 1)
            .put("deviceId", deviceId)
            .put("publicKey", localKeys.public.encoded.toB64())
            .put("timestamp", System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun handleKeyExchangePayload(
        sourceNodeId: String,
        payload: ByteArray,
    ): Boolean =
        runCatching {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val publicKeyB64 = json.getString("publicKey")
            val senderDeviceId = json.optString("deviceId")
            storePeerPublicKey(sourceNodeId, publicKeyB64, senderDeviceId)
            true
        }.getOrElse { false }

    fun hasPeerKey(nodeId: String): Boolean =
        !getPeerPublicKey(nodeId).isNullOrBlank()

    fun encrypt(
        nodeId: String,
        path: String,
        plainPayload: ByteArray,
    ): ByteArray? {
        val peerPublicB64 = getPeerPublicKey(nodeId) ?: return null
        val peerPublic = peerPublicB64.toPublicKey() ?: return null
        val secret = deriveSessionKey(localKeys.private.encoded, peerPublic)
        val counter = nextCounter(nodeId)
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val timestamp = System.currentTimeMillis()

        val cipher = Cipher.getInstance(AES_MODE)
        val aad = buildAad(path = path, counter = counter, sender = deviceId, timestamp = timestamp)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plainPayload)

        return JSONObject()
            .put("version", ENVELOPE_VERSION)
            .put("kid", KID)
            .put("senderDeviceId", deviceId)
            .put("senderPublicKey", localKeys.public.encoded.toB64())
            .put("counter", counter)
            .put("timestamp", timestamp)
            .put("nonce", nonce.toB64())
            .put("ciphertext", ciphertext.toB64())
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decrypt(
        sourceNodeId: String,
        path: String,
        envelopePayload: ByteArray,
    ): ByteArray? =
        runCatching {
            val envelope = JSONObject(envelopePayload.toString(Charsets.UTF_8))
            val senderDeviceId = envelope.getString("senderDeviceId")
            val senderPublicB64 = envelope.getString("senderPublicKey")
            val counter = envelope.getLong("counter")
            val timestamp = envelope.getLong("timestamp")
            val nonce = envelope.getString("nonce").fromB64()
            val ciphertext = envelope.getString("ciphertext").fromB64()

            val storedPeer = getPeerPublicKey(sourceNodeId)
            if (storedPeer != null && storedPeer != senderPublicB64) {
                return null
            }
            if (storedPeer == null) {
                storePeerPublicKey(sourceNodeId, senderPublicB64, senderDeviceId)
            }

            val lastCounter = getLastReceivedCounter(senderDeviceId)
            if (counter <= lastCounter) {
                return null
            }

            val senderPublic = senderPublicB64.toPublicKey() ?: return null
            val secret = deriveSessionKey(localKeys.private.encoded, senderPublic)
            val aad = buildAad(path = path, counter = counter, sender = senderDeviceId, timestamp = timestamp)

            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ciphertext)
            setLastReceivedCounter(senderDeviceId, counter)
            plain
        }.getOrNull()

    private fun getOrCreateLocalKeyPair(): KeyPair {
        val storedPrivate = prefs.getString(KEY_PRIVATE, null)
        val storedPublic = prefs.getString(KEY_PUBLIC, null)
        if (!storedPrivate.isNullOrBlank() && !storedPublic.isNullOrBlank()) {
            val keyFactory = KeyFactory.getInstance(KEY_ALGO)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(storedPrivate.fromB64()))
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(storedPublic.fromB64()))
            return KeyPair(publicKey, privateKey)
        }

        val generator = KeyPairGenerator.getInstance(KEY_ALGO)
        generator.initialize(ECGenParameterSpec(CURVE_NAME))
        val generated = generator.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, generated.private.encoded.toB64())
            .putString(KEY_PUBLIC, generated.public.encoded.toB64())
            .apply()
        return generated
    }

    private fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun getPeerPublicKey(nodeId: String): String? =
        prefs.getString(peerPublicKeyPref(nodeId), null)

    private fun storePeerPublicKey(
        nodeId: String,
        publicKeyB64: String,
        peerDeviceId: String,
    ) {
        prefs.edit()
            .putString(peerPublicKeyPref(nodeId), publicKeyB64)
            .putString(peerDeviceIdPref(nodeId), peerDeviceId)
            .apply()
    }

    private fun nextCounter(nodeId: String): Long {
        val key = sentCounterPref(nodeId)
        val next = prefs.getLong(key, 0L) + 1L
        prefs.edit().putLong(key, next).apply()
        return next
    }

    private fun getLastReceivedCounter(senderDeviceId: String): Long =
        prefs.getLong(receivedCounterPref(senderDeviceId), 0L)

    private fun setLastReceivedCounter(
        senderDeviceId: String,
        counter: Long,
    ) {
        prefs.edit().putLong(receivedCounterPref(senderDeviceId), counter).apply()
    }

    private fun deriveSessionKey(
        localPrivatePkcs8: ByteArray,
        remotePublicKey: PublicKey,
    ): SecretKeySpec {
        val keyFactory = KeyFactory.getInstance(KEY_ALGO)
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(localPrivatePkcs8))
        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGO)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(remotePublicKey, true)
        val shared = keyAgreement.generateSecret()
        val prk = hmac(SALT, shared)
        val okm = hkdfExpand(prk = prk, info = INFO, size = 32)
        return SecretKeySpec(okm, "AES")
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(key, HMAC_ALGO))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(
        prk: ByteArray,
        info: ByteArray,
        size: Int,
    ): ByteArray {
        var result = ByteArray(0)
        var previous = ByteArray(0)
        var blockIndex = 1
        while (result.size < size) {
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(SecretKeySpec(prk, HMAC_ALGO))
            mac.update(previous)
            mac.update(info)
            mac.update(blockIndex.toByte())
            previous = mac.doFinal()
            result += previous
            blockIndex++
        }
        return result.copyOf(size)
    }

    private fun buildAad(
        path: String,
        counter: Long,
        sender: String,
        timestamp: Long,
    ): ByteArray =
        "$ENVELOPE_VERSION|$KID|$path|$sender|$counter|$timestamp".toByteArray(Charsets.UTF_8)

    private fun String.toPublicKey(): PublicKey? =
        runCatching {
            val bytes = fromB64()
            KeyFactory.getInstance(KEY_ALGO).generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()

    private fun ByteArray.toB64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromB64(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)

    private fun peerPublicKeyPref(nodeId: String) = "peer_pub_${nodeId.safeKey()}"
    private fun peerDeviceIdPref(nodeId: String) = "peer_device_${nodeId.safeKey()}"
    private fun sentCounterPref(nodeId: String) = "sent_counter_${nodeId.safeKey()}"
    private fun receivedCounterPref(deviceId: String) = "rx_counter_${deviceId.safeKey()}"
    private fun String.safeKey(): String = replace("[^a-zA-Z0-9_]".toRegex(), "_")

    private companion object {
        private const val PREFS_NAME = "wessage_secure_sync"
        private const val KEY_PRIVATE = "local_private"
        private const val KEY_PUBLIC = "local_public"
        private const val KEY_DEVICE_ID = "local_device_id"
        private const val KEY_ALGO = "EC"
        private const val ECDH_ALGO = "ECDH"
        private const val CURVE_NAME = "secp256r1"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val HMAC_ALGO = "HmacSHA256"
        private const val GCM_TAG_BITS = 128
        private const val NONCE_SIZE = 12
        private const val ENVELOPE_VERSION = 1
        private const val KID = "ec-p256-v1"
        private val SALT = "wessage-sync-salt-v1".toByteArray(Charsets.UTF_8)
        private val INFO = "wessage-sync-aes256-gcm-v1".toByteArray(Charsets.UTF_8)
    }
}

package com.wapp.wearmessage.sync

import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wapp.wearmessage.sync.contract.SyncJsonCodec
import com.wapp.wearmessage.sync.contract.SyncPaths
import com.wapp.wearmessage.sync.contract.BootstrapRequest
import com.wapp.wearmessage.sync.security.DecryptResult
import com.wapp.wearmessage.sync.security.SecureSyncCodec
import org.json.JSONObject

class WearSyncListenerService : WearableListenerService() {
    private val secureCodec by lazy { SecureSyncCodec(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val path = item.uri.path ?: return@forEach
            val sourceNodeId = item.uri.host.orEmpty()
            val payload = DataMapItem.fromDataItem(item).dataMap.getByteArray(PAYLOAD_KEY) ?: return@forEach
            when (path) {
                SyncPaths.CONVERSATIONS -> {
                    val plainPayload =
                        when (val result =
                            secureCodec.decryptEnvelope(
                                sourceNodeId = sourceNodeId,
                                path = path,
                                envelopePayload = payload,
                            )
                        ) {
                            is DecryptResult.Success -> result.payload
                            DecryptResult.Stale -> {
                                Log.d(TAG, "Ignoring stale conversations payload path=$path")
                                return@forEach
                            }
                            DecryptResult.Failed -> {
                                handleEncryptedPayloadFailure(
                                    sourceNodeId = sourceNodeId,
                                    path = path,
                                    payload = payload,
                                )
                                return@forEach
                            }
                        }
                    val delta = SyncJsonCodec.decodeConversationDeltaBatch(plainPayload)
                    if (delta != null) {
                        WearSyncBus.emit(SyncInboundEvent.Conversations(delta))
                        Log.d(TAG, "Conversations delta received: ${delta.conversations.size}")
                    } else {
                        Log.w(TAG, "Failed decoding decrypted conversations payload bytes=${plainPayload.size}")
                    }
                }
                else -> {
                    if (!path.startsWith("${SyncPaths.MESSAGE_CHUNKS}/")) return@forEach
                    val plainPayload =
                        when (val result =
                            secureCodec.decryptEnvelope(
                                sourceNodeId = sourceNodeId,
                                path = path,
                                envelopePayload = payload,
                            )
                        ) {
                            is DecryptResult.Success -> result.payload
                            DecryptResult.Stale -> {
                                Log.d(TAG, "Ignoring stale messages payload path=$path")
                                return@forEach
                            }
                            DecryptResult.Failed -> {
                                handleEncryptedPayloadFailure(
                                    sourceNodeId = sourceNodeId,
                                    path = path,
                                    payload = payload,
                                )
                                return@forEach
                            }
                        }
                    val delta = SyncJsonCodec.decodeMessageDeltaBatch(plainPayload)
                    if (delta != null) {
                        WearSyncBus.emit(SyncInboundEvent.Messages(delta))
                        Log.d(TAG, "Messages delta received: ${delta.messages.size}")
                    } else {
                        Log.w(TAG, "Failed decoding decrypted messages payload bytes=${plainPayload.size}")
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            SyncPaths.KEY_EXCHANGE_REQUEST -> {
                if (secureCodec.handleKeyExchangePayload(messageEvent.sourceNodeId, messageEvent.data)) {
                    sendKeyExchangeResponse(messageEvent.sourceNodeId)
                }
            }
            SyncPaths.KEY_EXCHANGE_RESPONSE -> {
                val accepted = secureCodec.handleKeyExchangePayload(messageEvent.sourceNodeId, messageEvent.data)
                Log.d(TAG, "Key exchange response handled=$accepted source=${messageEvent.sourceNodeId}")
                if (accepted) {
                    requestBootstrap(messageEvent.sourceNodeId)
                }
            }
            SyncPaths.ACK -> {
                val plainPayload =
                    when (val result =
                        secureCodec.decryptEnvelope(
                            sourceNodeId = messageEvent.sourceNodeId,
                            path = messageEvent.path,
                            envelopePayload = messageEvent.data,
                        )
                    ) {
                        is DecryptResult.Success -> result.payload
                        DecryptResult.Stale -> {
                            Log.d(TAG, "Ignoring stale ack payload")
                            return
                        }
                        DecryptResult.Failed -> {
                            handleEncryptedPayloadFailure(
                                sourceNodeId = messageEvent.sourceNodeId,
                                path = messageEvent.path,
                                payload = messageEvent.data,
                            )
                            return
                        }
                    }
                val ack = SyncJsonCodec.decodeMutationAck(plainPayload)
                ack?.let { WearSyncBus.emit(SyncInboundEvent.Ack(it)) }
                Log.d(
                    TAG,
                    "Ack received mutation=${ack?.clientMutationId} accepted=${ack?.accepted}",
                )
            }
        }
    }

    private fun sendKeyExchangeResponse(nodeId: String) {
        messageClient
            .sendMessage(
                nodeId,
                SyncPaths.KEY_EXCHANGE_RESPONSE,
                secureCodec.createKeyExchangePayload(),
            ).addOnFailureListener { error ->
                Log.w(TAG, "Failed sending key exchange response", error)
            }
    }

    private fun requestBootstrap(nodeId: String) {
        val payload = SyncJsonCodec.encodeBootstrapRequest(BootstrapRequest())
        messageClient
            .sendMessage(nodeId, SyncPaths.BOOTSTRAP_REQUEST, payload)
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed requesting bootstrap after key exchange", error)
            }
    }

    private fun handleEncryptedPayloadFailure(
        sourceNodeId: String,
        path: String,
        payload: ByteArray,
    ) {
        val encryptedLikely = payload.looksLikeEncryptedEnvelope()
        Log.w(
            TAG,
            "Failed decrypting sync payload path=$path encryptedLikely=$encryptedLikely payloadBytes=${payload.size}",
        )
        messageClient
            .sendMessage(
                sourceNodeId,
                SyncPaths.KEY_EXCHANGE_REQUEST,
                secureCodec.createKeyExchangePayload(),
            ).addOnFailureListener { error ->
                Log.w(TAG, "Failed requesting key exchange recovery", error)
            }
    }

    private fun ByteArray.looksLikeEncryptedEnvelope(): Boolean =
        runCatching {
            val json = JSONObject(toString(Charsets.UTF_8))
            json.has("senderPublicKey") && json.has("nonce") && json.has("ciphertext")
        }.getOrDefault(false)

    private companion object {
        private const val TAG = "WearSyncListener"
        private const val PAYLOAD_KEY = "payload"
    }
}

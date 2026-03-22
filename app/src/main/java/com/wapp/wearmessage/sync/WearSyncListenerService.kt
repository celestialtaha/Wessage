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
import com.wapp.wearmessage.sync.security.SecureSyncCodec

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
            val plainPayload = secureCodec.decrypt(sourceNodeId = sourceNodeId, path = path, envelopePayload = payload) ?: payload
            when (path) {
                SyncPaths.CONVERSATIONS -> {
                    val delta = SyncJsonCodec.decodeConversationDeltaBatch(plainPayload)
                    delta?.let { WearSyncBus.emit(SyncInboundEvent.Conversations(it)) }
                    Log.d(TAG, "Conversations delta received: ${delta?.conversations?.size ?: 0}")
                }
                SyncPaths.MESSAGES -> {
                    val delta = SyncJsonCodec.decodeMessageDeltaBatch(plainPayload)
                    delta?.let { WearSyncBus.emit(SyncInboundEvent.Messages(it)) }
                    Log.d(TAG, "Messages delta received: ${delta?.messages?.size ?: 0}")
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
            }
            SyncPaths.ACK -> {
                val plainPayload =
                    secureCodec.decrypt(
                        sourceNodeId = messageEvent.sourceNodeId,
                        path = messageEvent.path,
                        envelopePayload = messageEvent.data,
                    ) ?: messageEvent.data
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

    private companion object {
        private const val TAG = "WearSyncListener"
        private const val PAYLOAD_KEY = "payload"
    }
}

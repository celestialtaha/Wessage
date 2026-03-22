package com.wapp.wearmessage.sync

import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.wapp.wearmessage.sync.contract.SyncJsonCodec
import com.wapp.wearmessage.sync.contract.SyncPaths

class WearSyncListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val path = item.uri.path ?: return@forEach
            val payload = DataMapItem.fromDataItem(item).dataMap.getByteArray(PAYLOAD_KEY) ?: return@forEach
            when (path) {
                SyncPaths.CONVERSATIONS -> {
                    val delta = SyncJsonCodec.decodeConversationDeltaBatch(payload)
                    delta?.let { WearSyncBus.emit(SyncInboundEvent.Conversations(it)) }
                    Log.d(TAG, "Conversations delta received: ${delta?.conversations?.size ?: 0}")
                }
                SyncPaths.MESSAGES -> {
                    val delta = SyncJsonCodec.decodeMessageDeltaBatch(payload)
                    delta?.let { WearSyncBus.emit(SyncInboundEvent.Messages(it)) }
                    Log.d(TAG, "Messages delta received: ${delta?.messages?.size ?: 0}")
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != SyncPaths.ACK) return
        val ack = SyncJsonCodec.decodeMutationAck(messageEvent.data)
        ack?.let { WearSyncBus.emit(SyncInboundEvent.Ack(it)) }
        Log.d(
            TAG,
            "Ack received mutation=${ack?.clientMutationId} accepted=${ack?.accepted}",
        )
    }

    private companion object {
        private const val TAG = "WearSyncListener"
        private const val PAYLOAD_KEY = "payload"
    }
}

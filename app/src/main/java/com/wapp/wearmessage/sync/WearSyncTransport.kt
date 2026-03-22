package com.wapp.wearmessage.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wapp.wearmessage.sync.contract.SyncJsonCodec
import com.wapp.wearmessage.sync.contract.SyncPaths
import com.wapp.wearmessage.sync.contract.WatchMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WearSyncTransport(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }

    suspend fun requestBootstrapSync(): Boolean =
        withContext(Dispatchers.IO) {
            sendToConnectedNodes(
                path = SyncPaths.BOOTSTRAP_REQUEST,
                payload = ByteArray(0),
            )
        }

    suspend fun sendWatchMutation(mutation: WatchMutation): Boolean =
        withContext(Dispatchers.IO) {
            sendToConnectedNodes(
                path = SyncPaths.MUTATION,
                payload = SyncJsonCodec.encodeWatchMutation(mutation),
            )
        }

    private fun sendToConnectedNodes(
        path: String,
        payload: ByteArray,
    ): Boolean =
        runCatching {
            val nodes = Tasks.await(nodeClient.connectedNodes)
            var sent = false
            nodes.forEach { node ->
                Tasks.await(messageClient.sendMessage(node.id, path, payload))
                sent = true
            }
            sent
        }.onFailure { error ->
            Log.w(TAG, "Failed to send path=$path to phone", error)
        }.getOrDefault(false)

    private companion object {
        private const val TAG = "WearSyncTransport"
    }
}

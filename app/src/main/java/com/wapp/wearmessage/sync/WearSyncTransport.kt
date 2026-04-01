package com.wapp.wearmessage.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wapp.wearmessage.sync.contract.SyncJsonCodec
import com.wapp.wearmessage.sync.contract.SyncPaths
import com.wapp.wearmessage.sync.contract.WatchMutation
import com.wapp.wearmessage.sync.contract.BootstrapRequest
import com.wapp.wearmessage.sync.security.SecureSyncCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WearSyncTransport(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val secureCodec by lazy { SecureSyncCodec(appContext) }

    suspend fun requestBootstrapSync(
        limit: Int = DEFAULT_BOOTSTRAP_LIMIT,
        offset: Int = 0,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(nodeClient.connectedNodes)
                val bootstrapPayload =
                    SyncJsonCodec.encodeBootstrapRequest(
                        BootstrapRequest(
                            limit = limit.coerceIn(1, MAX_BOOTSTRAP_LIMIT),
                            offset = offset.coerceAtLeast(0),
                        )
                    )
                var sent = false
                nodes.forEach { node ->
                    if (!secureCodec.hasPeerKey(node.id)) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                SyncPaths.KEY_EXCHANGE_REQUEST,
                                secureCodec.createKeyExchangePayload(),
                            )
                        )
                    }
                    Tasks.await(
                        messageClient.sendMessage(
                            node.id,
                            SyncPaths.BOOTSTRAP_REQUEST,
                            bootstrapPayload,
                        )
                    )
                    sent = true
                }
                sent
            }.onFailure { error ->
                Log.w(TAG, "Failed requesting bootstrap sync", error)
            }.getOrDefault(false)
        }

    suspend fun sendWatchMutation(mutation: WatchMutation): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(nodeClient.connectedNodes)
                val plainPayload = SyncJsonCodec.encodeWatchMutation(mutation)
                var sent = false
                nodes.forEach { node ->
                    if (!secureCodec.hasPeerKey(node.id)) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                SyncPaths.KEY_EXCHANGE_REQUEST,
                                secureCodec.createKeyExchangePayload(),
                            )
                        )
                        return@forEach
                    }
                    val encrypted =
                        secureCodec.encrypt(
                            nodeId = node.id,
                            path = SyncPaths.MUTATION,
                            plainPayload = plainPayload,
                        )
                    if (encrypted == null) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                SyncPaths.KEY_EXCHANGE_REQUEST,
                                secureCodec.createKeyExchangePayload(),
                            )
                        )
                        return@forEach
                    } else {
                        Tasks.await(messageClient.sendMessage(node.id, SyncPaths.MUTATION, encrypted))
                        sent = true
                    }
                }
                sent
            }.onFailure { error ->
                Log.w(TAG, "Failed sending mutation", error)
            }.getOrDefault(false)
        }

    private companion object {
        private const val TAG = "WearSyncTransport"
        private const val DEFAULT_BOOTSTRAP_LIMIT = 25
        private const val MAX_BOOTSTRAP_LIMIT = 300
    }
}

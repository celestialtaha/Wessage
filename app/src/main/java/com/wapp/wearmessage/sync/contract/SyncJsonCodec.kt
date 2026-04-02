package com.wapp.wearmessage.sync.contract

import org.json.JSONArray
import org.json.JSONObject

object SyncJsonCodec {
    fun encodeBootstrapRequest(request: BootstrapRequest): ByteArray =
        JSONObject()
            .put("schemaVersion", request.schemaVersion)
            .put("limit", request.limit)
            .put("offset", request.offset)
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun encodeWatchMutation(mutation: WatchMutation): ByteArray =
        JSONObject()
            .put("schemaVersion", mutation.schemaVersion)
            .put("clientMutationId", mutation.clientMutationId)
            .put("type", mutation.type.name)
            .put("conversationId", mutation.conversationId)
            .put("messageBody", mutation.messageBody)
            .put("createdAtEpochMillis", mutation.createdAtEpochMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun decodeMutationAck(bytes: ByteArray): MutationAck? =
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            MutationAck(
                schemaVersion = json.optInt("schemaVersion", SYNC_SCHEMA_VERSION),
                clientMutationId = json.getString("clientMutationId"),
                accepted = json.getBoolean("accepted"),
                serverVersion = json.getLong("serverVersion"),
                errorCode = json.optString("errorCode").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

    fun decodeConversationDeltaBatch(bytes: ByteArray): ConversationDeltaBatch? =
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val conversations =
                json.optJSONArray("conversations").toSyncConversations()
            val deleted =
                json.optJSONArray("deletedConversationIds").toStringList()

            ConversationDeltaBatch(
                schemaVersion = json.optInt("schemaVersion", SYNC_SCHEMA_VERSION),
                cursor = json.getLong("cursor"),
                generatedAtEpochMillis = json.getLong("generatedAtEpochMillis"),
                conversations = conversations,
                deletedConversationIds = deleted,
            )
        }.getOrNull()

    fun decodeMessageDeltaBatch(bytes: ByteArray): MessageDeltaBatch? =
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val messages = json.optJSONArray("messages").toSyncMessages()
            val deleted = json.optJSONArray("deletedMessageIds").toStringList()
            MessageDeltaBatch(
                schemaVersion = json.optInt("schemaVersion", SYNC_SCHEMA_VERSION),
                cursor = json.getLong("cursor"),
                generatedAtEpochMillis = json.getLong("generatedAtEpochMillis"),
                messages = messages,
                deletedMessageIds = deleted,
            )
        }.getOrNull()

    private fun JSONArray?.toSyncConversations(): List<SyncConversation> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            SyncConversation(
                id = item.getString("id"),
                participants = item.optJSONArray("participants").toStringList(),
                lastMessage = item.optString("lastMessage"),
                lastUpdatedAtEpochMillis = item.optLong("lastUpdatedAtEpochMillis"),
                unreadCount = item.optInt("unreadCount"),
                muted = item.optBoolean("muted"),
            )
        }
    }

    private fun JSONArray?.toSyncMessages(): List<SyncMessage> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val statusName = item.optString("status", SyncMessageStatus.SENT.name)
            SyncMessage(
                id = item.getString("id"),
                conversationId = item.getString("conversationId"),
                senderId = item.optString("senderId"),
                body = item.optString("body"),
                timestampEpochMillis = item.optLong("timestampEpochMillis"),
                status = statusName.toSyncStatus(),
                localVersion = item.optInt("localVersion", 1),
                outgoing = if (item.has("outgoing")) item.optBoolean("outgoing") else null,
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun String.toSyncStatus(): SyncMessageStatus =
        runCatching { SyncMessageStatus.valueOf(this) }.getOrElse { SyncMessageStatus.SENT }
}

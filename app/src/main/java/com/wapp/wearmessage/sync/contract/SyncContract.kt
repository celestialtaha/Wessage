package com.wapp.wearmessage.sync.contract

const val SYNC_SCHEMA_VERSION: Int = 1

object SyncPaths {
    const val ROOT = "/wessage/sync/v1"
    const val CONVERSATIONS = "$ROOT/conversations"
    const val MESSAGES = "$ROOT/messages"
    const val MUTATION = "$ROOT/mutation"
    const val ACK = "$ROOT/ack"
    const val BOOTSTRAP_REQUEST = "$ROOT/bootstrap_request"
    const val KEY_EXCHANGE_REQUEST = "$ROOT/key_exchange/request"
    const val KEY_EXCHANGE_RESPONSE = "$ROOT/key_exchange/response"
}

enum class SyncMessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
    READ,
}

data class SyncConversation(
    val id: String,
    val participants: List<String>,
    val lastMessage: String,
    val lastUpdatedAtEpochMillis: Long,
    val unreadCount: Int,
    val muted: Boolean,
)

data class SyncMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val timestampEpochMillis: Long,
    val status: SyncMessageStatus,
    val localVersion: Int,
    val outgoing: Boolean? = null,
)

data class ConversationDeltaBatch(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val cursor: Long,
    val generatedAtEpochMillis: Long,
    val conversations: List<SyncConversation>,
    val deletedConversationIds: List<String> = emptyList(),
)

data class MessageDeltaBatch(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val cursor: Long,
    val generatedAtEpochMillis: Long,
    val messages: List<SyncMessage>,
    val deletedMessageIds: List<String> = emptyList(),
)

data class BootstrapRequest(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val limit: Int = 25,
    val offset: Int = 0,
)

enum class WatchMutationType {
    REPLY,
    MARK_READ,
    ARCHIVE,
    MUTE,
    UNMUTE,
}

data class WatchMutation(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val clientMutationId: String,
    val type: WatchMutationType,
    val conversationId: String,
    val messageBody: String? = null,
    val createdAtEpochMillis: Long,
)

data class MutationAck(
    val schemaVersion: Int = SYNC_SCHEMA_VERSION,
    val clientMutationId: String,
    val accepted: Boolean,
    val serverVersion: Long,
    val errorCode: String? = null,
)

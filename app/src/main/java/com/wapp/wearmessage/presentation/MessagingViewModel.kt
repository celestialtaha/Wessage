package com.wapp.wearmessage.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wapp.wearmessage.sync.SyncInboundEvent
import com.wapp.wearmessage.sync.WearSyncBus
import com.wapp.wearmessage.sync.contract.SyncMessageStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class MessagingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WearMessagingUiState())
    val uiState: StateFlow<WearMessagingUiState> = _uiState.asStateFlow()

    init {
        bootstrapData()
        observeIncomingSync()
    }

    fun openConversations() {
        _uiState.update {
            it.copy(
                currentScreen = WearScreen.Conversations,
                selectedConversationId = null,
            )
        }
    }

    fun openContacts() {
        _uiState.update { it.copy(currentScreen = WearScreen.Contacts) }
    }

    fun openSettings() {
        _uiState.update { it.copy(currentScreen = WearScreen.Settings) }
    }

    fun openConversation(conversationId: String) {
        _uiState.update {
            it.copy(
                currentScreen = WearScreen.Thread(conversationId),
                selectedConversationId = conversationId,
            )
        }
        if (_uiState.value.settings.markReadOnOpen) {
            markConversationRead(conversationId)
        }
    }

    fun navigateBack() {
        _uiState.update { state ->
            when (state.currentScreen) {
                WearScreen.Conversations -> state
                else ->
                    state.copy(
                        currentScreen = WearScreen.Conversations,
                        selectedConversationId = null,
                    )
            }
        }
    }

    fun queueQuickReply(conversationId: String, quickReply: String) {
        val now = System.currentTimeMillis()
        val mutationId = "watch-${System.currentTimeMillis()}"
        val optimisticMessage =
            Message(
                id = mutationId,
                conversationId = conversationId,
                senderId = "self",
                senderName = "You",
                body = quickReply,
                timestamp = "Now",
                status = MessageStatus.Pending,
                localVersion = 1,
                outgoing = true,
            )

        _uiState.update { state ->
            state.copy(
                pendingMutations = state.pendingMutations + 1,
                syncStatus = SyncStatus.OfflineQueue,
                messagesByConversation =
                    state.messagesByConversation +
                        (conversationId to
                            (state.messagesByConversation[conversationId].orEmpty() + optimisticMessage)),
                conversationsState = state.conversationsState.withUpdatedConversation(conversationId) {
                    copy(
                        lastMessage = quickReply,
                        lastUpdatedAt = now.toRelativeTimestampLabel(),
                        lastUpdatedAtEpochMillis = now,
                    )
                },
            )
        }

        viewModelScope.launch {
            delay(850)
            _uiState.update { state ->
                val pendingAfterAck = (state.pendingMutations - 1).coerceAtLeast(0)
                val updatedMessages =
                    state.messagesByConversation[conversationId]
                        .orEmpty()
                        .map { message ->
                            if (message.id == mutationId) {
                                message.copy(
                                    status = MessageStatus.Sent,
                                    localVersion = message.localVersion + 1,
                                )
                            } else {
                                message
                            }
                        }
                state.copy(
                    pendingMutations = pendingAfterAck,
                    syncStatus = if (pendingAfterAck == 0) SyncStatus.Idle else SyncStatus.Syncing,
                    messagesByConversation =
                        state.messagesByConversation + (conversationId to updatedMessages),
                )
            }
        }
    }

    fun markConversationRead(conversationId: String) {
        _uiState.update { state ->
            state.copy(
                conversationsState = state.conversationsState.withUpdatedConversation(conversationId) {
                    copy(unreadCount = 0)
                },
                messagesByConversation =
                    state.messagesByConversation + (conversationId to
                        state.messagesByConversation[conversationId].orEmpty().map { message ->
                            if (!message.outgoing && message.status != MessageStatus.Read) {
                                message.copy(
                                    status = MessageStatus.Read,
                                    localVersion = message.localVersion + 1,
                                )
                            } else {
                                message
                            }
                        }),
            )
        }
    }

    fun archiveConversation(conversationId: String) {
        _uiState.update { state ->
            val updatedConversations =
                state.conversationsState.withConversationList { conversations ->
                    conversations.filterNot { it.id == conversationId }
                }
            state.copy(
                currentScreen = WearScreen.Conversations,
                selectedConversationId = null,
                conversationsState = updatedConversations,
            )
        }
    }

    fun toggleConversationMute(conversationId: String) {
        _uiState.update { state ->
            state.copy(
                conversationsState = state.conversationsState.withUpdatedConversation(conversationId) {
                    copy(muted = !muted)
                }
            )
        }
    }

    fun toggleHaptics(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(hapticsEnabled = enabled)) }
    }

    fun toggleMarkReadOnOpen(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(markReadOnOpen = enabled)) }
    }

    fun toggleGlassBoost(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(glassBoostEnabled = enabled)) }
    }

    fun setSyncProfile(syncProfile: SyncProfile) {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(syncProfile = syncProfile),
                syncStatus =
                    if (syncProfile == SyncProfile.BatterySaver) {
                        SyncStatus.Idle
                    } else {
                        state.syncStatus
                    },
            )
        }
    }

    private fun bootstrapData() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    conversationsState = ConversationsUiState.Loading,
                    syncStatus = SyncStatus.Syncing,
                )
            }
            delay(450)
            val now = System.currentTimeMillis()
            val seedConversations =
                listOf(
                    Conversation(
                        id = "team",
                        title = "Product Team",
                        participants = listOf("Nia", "Zayd", "You"),
                        lastMessage = "Ship window moved to 17:30.",
                        lastUpdatedAt = (now - 2 * 60_000L).toRelativeTimestampLabel(now),
                        lastUpdatedAtEpochMillis = now - 2 * 60_000L,
                        unreadCount = 3,
                    ),
                    Conversation(
                        id = "sam",
                        title = "Samir",
                        participants = listOf("Samir", "You"),
                        lastMessage = "Lunch near Alexanderplatz?",
                        lastUpdatedAt = (now - 9 * 60_000L).toRelativeTimestampLabel(now),
                        lastUpdatedAtEpochMillis = now - 9 * 60_000L,
                        unreadCount = 1,
                    ),
                    Conversation(
                        id = "ops",
                        title = "Ops Alerts",
                        participants = listOf("Pager", "You"),
                        lastMessage = "API latency normalized.",
                        lastUpdatedAt = (now - 24 * 60_000L).toRelativeTimestampLabel(now),
                        lastUpdatedAtEpochMillis = now - 24 * 60_000L,
                        unreadCount = 0,
                        muted = true,
                    ),
                )
            val seedMessages =
                mapOf(
                    "team" to
                        listOf(
                            Message(
                                id = "m-team-1",
                                conversationId = "team",
                                senderId = "nia",
                                senderName = "Nia",
                                body = "We merged the watch quick reply flow.",
                                timestamp = "10:11",
                                status = MessageStatus.Read,
                                localVersion = 1,
                                outgoing = false,
                            ),
                            Message(
                                id = "m-team-2",
                                conversationId = "team",
                                senderId = "zayd",
                                senderName = "Zayd",
                                body = "Ship window moved to 17:30.",
                                timestamp = "10:14",
                                status = MessageStatus.Delivered,
                                localVersion = 1,
                                outgoing = false,
                            ),
                        ),
                    "sam" to
                        listOf(
                            Message(
                                id = "m-sam-1",
                                conversationId = "sam",
                                senderId = "sam",
                                senderName = "Samir",
                                body = "Lunch near Alexanderplatz?",
                                timestamp = "10:03",
                                status = MessageStatus.Delivered,
                                localVersion = 1,
                                outgoing = false,
                            )
                        ),
                    "ops" to
                        listOf(
                            Message(
                                id = "m-ops-1",
                                conversationId = "ops",
                                senderId = "opsbot",
                                senderName = "Ops Bot",
                                body = "API latency normalized.",
                                timestamp = "09:40",
                                status = MessageStatus.Read,
                                localVersion = 1,
                                outgoing = false,
                            )
                        ),
                )
            _uiState.update {
                it.copy(
                    conversationsState = ConversationsUiState.Success(seedConversations),
                    messagesByConversation = seedMessages,
                    syncStatus = SyncStatus.Idle,
                )
            }
        }
    }

    private fun observeIncomingSync() {
        viewModelScope.launch {
            WearSyncBus.events.collect { event ->
                when (event) {
                    is SyncInboundEvent.Conversations -> applyConversationDelta(event.payload)
                    is SyncInboundEvent.Messages -> applyMessageDelta(event.payload)
                    is SyncInboundEvent.Ack -> applyMutationAck(event.payload)
                }
            }
        }
    }

    private fun applyConversationDelta(batch: com.wapp.wearmessage.sync.contract.ConversationDeltaBatch) {
        _uiState.update { state ->
            val mergedById =
                mutableMapOf<String, Conversation>().apply {
                    putAll(
                        (state.conversationsState as? ConversationsUiState.Success)
                            ?.conversations
                            .orEmpty()
                            .associateBy { it.id }
                    )
                }

            batch.deletedConversationIds.forEach { deletedId ->
                mergedById.remove(deletedId)
            }

            batch.conversations.forEach { sync ->
                mergedById[sync.id] =
                    Conversation(
                        id = sync.id,
                        title = sync.participants.firstOrNull().orEmpty().ifBlank { "Conversation ${sync.id}" },
                        participants = sync.participants,
                        lastMessage = sync.lastMessage,
                        lastUpdatedAt = sync.lastUpdatedAtEpochMillis.toRelativeTimestampLabel(),
                        lastUpdatedAtEpochMillis = sync.lastUpdatedAtEpochMillis,
                        unreadCount = sync.unreadCount,
                        muted = sync.muted,
                    )
            }

            val conversations =
                mergedById.values
                    .sortedByDescending { it.lastUpdatedAtEpochMillis }

            state.copy(
                conversationsState =
                    if (conversations.isEmpty()) {
                        ConversationsUiState.Empty
                    } else {
                        ConversationsUiState.Success(conversations)
                    },
                syncStatus = SyncStatus.Idle,
            )
        }
    }

    private fun applyMessageDelta(batch: com.wapp.wearmessage.sync.contract.MessageDeltaBatch) {
        _uiState.update { state ->
            val groupedMessages = batch.messages.groupBy { it.conversationId }
            val mappedByConversation =
                groupedMessages.mapValues { (_, syncMessages) ->
                    syncMessages
                        .sortedBy { it.timestampEpochMillis }
                        .filterNot { sync -> batch.deletedMessageIds.contains(sync.id) }
                        .map { sync ->
                            Message(
                                id = sync.id,
                                conversationId = sync.conversationId,
                                senderId = sync.senderId,
                                senderName = sync.senderId.ifBlank { "Unknown" },
                                body = sync.body,
                                timestamp = sync.timestampEpochMillis.toClockLabel(),
                                status = sync.status.toMessageStatus(),
                                localVersion = sync.localVersion,
                                outgoing = sync.senderId == "self",
                            )
                        }
                }

            state.copy(
                messagesByConversation = state.messagesByConversation + mappedByConversation,
                syncStatus = SyncStatus.Idle,
            )
        }
    }

    private fun applyMutationAck(ack: com.wapp.wearmessage.sync.contract.MutationAck) {
        _uiState.update { state ->
            val pendingAfterAck = (state.pendingMutations - 1).coerceAtLeast(0)
            state.copy(
                pendingMutations = pendingAfterAck,
                syncStatus =
                    if (!ack.accepted) {
                        SyncStatus.OfflineQueue
                    } else if (pendingAfterAck == 0) {
                        SyncStatus.Idle
                    } else {
                        SyncStatus.Syncing
                    },
            )
        }
    }
}

private fun ConversationsUiState.withConversationList(
    transform: (List<Conversation>) -> List<Conversation>,
): ConversationsUiState =
    when (this) {
        is ConversationsUiState.Success -> {
            val updated = transform(conversations)
            if (updated.isEmpty()) {
                ConversationsUiState.Empty
            } else {
                ConversationsUiState.Success(updated)
            }
        }
        else -> this
    }

private fun ConversationsUiState.withUpdatedConversation(
    conversationId: String,
    transform: Conversation.() -> Conversation,
): ConversationsUiState =
    withConversationList { conversations ->
        conversations.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.transform()
            } else {
                conversation
            }
        }
    }

private fun SyncMessageStatus.toMessageStatus(): MessageStatus =
    when (this) {
        SyncMessageStatus.PENDING -> MessageStatus.Pending
        SyncMessageStatus.SENT -> MessageStatus.Sent
        SyncMessageStatus.DELIVERED -> MessageStatus.Delivered
        SyncMessageStatus.FAILED -> MessageStatus.Failed
        SyncMessageStatus.READ -> MessageStatus.Read
    }

private fun Long.toRelativeTimestampLabel(now: Long = System.currentTimeMillis()): String {
    val deltaMillis = (now - this).coerceAtLeast(0L)
    val deltaMinutes = (deltaMillis / 60_000L).toInt()
    val deltaHours = (deltaMillis / 3_600_000L).toInt()
    val deltaDays = (deltaMillis / 86_400_000L).toInt()
    return when {
        deltaMinutes < 1 -> "now"
        deltaMinutes < 60 -> "${deltaMinutes}m"
        deltaHours < 24 -> "${deltaHours}h"
        deltaDays < 7 -> "${deltaDays}d"
        deltaDays < 30 -> "${(deltaDays / 7)}w"
        deltaDays < 365 -> "${(deltaDays / 30)}mo"
        else -> "${(deltaDays / 365)}y"
    }
}

private fun Long.toClockLabel(): String {
    val minutes = (this / 60_000L) % (24 * 60)
    val hour = (minutes / 60).toInt()
    val minute = (minutes % 60).toInt().absoluteValue
    return "%02d:%02d".format(hour, minute)
}

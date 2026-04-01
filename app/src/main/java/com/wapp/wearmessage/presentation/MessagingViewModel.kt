package com.wapp.wearmessage.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wapp.wearmessage.storage.CachedConversation
import com.wapp.wearmessage.storage.CachedMessage
import com.wapp.wearmessage.storage.CachedSyncSnapshot
import com.wapp.wearmessage.storage.SecureMessageCache
import com.wapp.wearmessage.sync.SyncInboundEvent
import com.wapp.wearmessage.sync.WearSyncBus
import com.wapp.wearmessage.sync.contract.SyncMessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

class MessagingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(WearMessagingUiState())
    val uiState: StateFlow<WearMessagingUiState> = _uiState.asStateFlow()
    private val secureCache = SecureMessageCache(application.applicationContext)
    private var hasReceivedSyncPayload = false

    init {
        bootstrapData()
        observeIncomingSync()
        observeCachePersistence()
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
            val restoredFromCache = restoreCachedData()
            _uiState.update {
                if (restoredFromCache) {
                    it.copy(syncStatus = SyncStatus.Syncing)
                } else {
                    it.copy(
                        conversationsState = ConversationsUiState.Loading,
                        syncStatus = SyncStatus.Syncing,
                        messagesByConversation = emptyMap(),
                    )
                }
            }
            delay(2_000)
            if (!hasReceivedSyncPayload) {
                _uiState.update { state ->
                    if (state.conversationsState is ConversationsUiState.Loading) {
                        state.copy(
                            conversationsState = ConversationsUiState.Empty,
                            syncStatus = SyncStatus.Idle,
                        )
                    } else {
                        state.copy(
                            syncStatus =
                                if (state.pendingMutations > 0) {
                                    SyncStatus.OfflineQueue
                                } else {
                                    SyncStatus.Idle
                                }
                        )
                    }
                }
            }
        }
    }

    private suspend fun restoreCachedData(): Boolean {
        val snapshot =
            withContext(Dispatchers.IO) {
                secureCache.load()
            } ?: return false
        _uiState.update {
            val conversations =
                snapshot.conversations
                    .map { cached ->
                        Conversation(
                            id = cached.id,
                            title = cached.title,
                            participants = cached.participants,
                            lastMessage = cached.lastMessage,
                            lastUpdatedAt = cached.lastUpdatedAtEpochMillis.toRelativeTimestampLabel(),
                            lastUpdatedAtEpochMillis = cached.lastUpdatedAtEpochMillis,
                            unreadCount = cached.unreadCount,
                            muted = cached.muted,
                        )
                    }
                    .sortedByDescending { conversation -> conversation.lastUpdatedAtEpochMillis }
            val messagesByConversation =
                snapshot.messages
                    .groupBy { cached -> cached.conversationId }
                    .mapValues { (_, cachedMessages) ->
                        cachedMessages.map { cached ->
                            Message(
                                id = cached.id,
                                conversationId = cached.conversationId,
                                senderId = cached.senderId,
                                senderName = cached.senderName,
                                body = cached.body,
                                timestamp = cached.timestamp,
                                status = cached.status.toMessageStatusSafe(),
                                localVersion = cached.localVersion,
                                outgoing = cached.outgoing,
                            )
                        }
                    }

            it.copy(
                conversationsState =
                    if (conversations.isEmpty()) {
                        ConversationsUiState.Empty
                    } else {
                        ConversationsUiState.Success(conversations)
                    },
                messagesByConversation = messagesByConversation,
                syncStatus = SyncStatus.Idle,
            )
        }
        return snapshot.conversations.isNotEmpty() || snapshot.messages.isNotEmpty()
    }

    private fun observeCachePersistence() {
        viewModelScope.launch {
            uiState
                .map { state -> state.toCachedSyncSnapshotOrNull() }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot != null) {
                        withContext(Dispatchers.IO) {
                            secureCache.save(snapshot)
                        }
                    }
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
        hasReceivedSyncPayload = true
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
        hasReceivedSyncPayload = true
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

private fun String.toMessageStatusSafe(): MessageStatus =
    runCatching { MessageStatus.valueOf(this) }
        .getOrDefault(MessageStatus.Sent)

private fun WearMessagingUiState.toCachedSyncSnapshotOrNull(): CachedSyncSnapshot? {
    if (conversationsState is ConversationsUiState.Loading) {
        return null
    }
    val conversations =
        (conversationsState as? ConversationsUiState.Success)
            ?.conversations
            .orEmpty()
            .sortedByDescending { conversation -> conversation.lastUpdatedAtEpochMillis }
            .map { conversation ->
                CachedConversation(
                    id = conversation.id,
                    title = conversation.title,
                    participants = conversation.participants,
                    lastMessage = conversation.lastMessage,
                    lastUpdatedAtEpochMillis = conversation.lastUpdatedAtEpochMillis,
                    unreadCount = conversation.unreadCount,
                    muted = conversation.muted,
                )
            }
    val messages =
        messagesByConversation
            .values
            .flatten()
            .sortedWith(
                compareBy<Message>({ it.conversationId }, { it.id })
            )
            .map { message ->
                CachedMessage(
                    id = message.id,
                    conversationId = message.conversationId,
                    senderId = message.senderId,
                    senderName = message.senderName,
                    body = message.body,
                    timestamp = message.timestamp,
                    status = message.status.name,
                    localVersion = message.localVersion,
                    outgoing = message.outgoing,
                )
            }

    return CachedSyncSnapshot(
        conversations = conversations,
        messages = messages,
        savedAtEpochMillis = System.currentTimeMillis(),
    )
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

package com.wapp.wearmessage.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MessagingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WearMessagingUiState())
    val uiState: StateFlow<WearMessagingUiState> = _uiState.asStateFlow()

    init {
        bootstrapData()
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
                    copy(lastMessage = quickReply, lastUpdatedAt = "Now")
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
            val seedConversations =
                listOf(
                    Conversation(
                        id = "team",
                        title = "Product Team",
                        participants = listOf("Nia", "Zayd", "You"),
                        lastMessage = "Ship window moved to 17:30.",
                        lastUpdatedAt = "2m",
                        unreadCount = 3,
                    ),
                    Conversation(
                        id = "sam",
                        title = "Samir",
                        participants = listOf("Samir", "You"),
                        lastMessage = "Lunch near Alexanderplatz?",
                        lastUpdatedAt = "9m",
                        unreadCount = 1,
                    ),
                    Conversation(
                        id = "ops",
                        title = "Ops Alerts",
                        participants = listOf("Pager", "You"),
                        lastMessage = "API latency normalized.",
                        lastUpdatedAt = "24m",
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

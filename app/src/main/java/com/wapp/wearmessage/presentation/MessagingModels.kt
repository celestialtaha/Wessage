package com.wapp.wearmessage.presentation

data class Conversation(
    val id: String,
    val title: String,
    val participants: List<String>,
    val lastMessage: String,
    val lastUpdatedAt: String,
    val lastUpdatedAtEpochMillis: Long,
    val unreadCount: Int,
    val muted: Boolean = false,
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val timestamp: String,
    val status: MessageStatus,
    val localVersion: Int,
    val outgoing: Boolean,
)

enum class MessageStatus {
    Pending,
    Sent,
    Delivered,
    Failed,
    Read,
}

sealed interface WearScreen {
    data object Conversations : WearScreen
    data object Contacts : WearScreen
    data object Settings : WearScreen
    data class Thread(val conversationId: String) : WearScreen
}

sealed interface ConversationsUiState {
    data object Loading : ConversationsUiState
    data object Empty : ConversationsUiState
    data class Error(val message: String) : ConversationsUiState
    data class Success(val conversations: List<Conversation>) : ConversationsUiState
}

enum class SyncStatus {
    Idle,
    Syncing,
    OfflineQueue,
}

enum class SyncProfile {
    Balanced,
    BatterySaver,
}

data class SettingsUiState(
    val hapticsEnabled: Boolean = true,
    val markReadOnOpen: Boolean = true,
    val glassBoostEnabled: Boolean = false,
    val syncProfile: SyncProfile = SyncProfile.Balanced,
)

data class WearMessagingUiState(
    val currentScreen: WearScreen = WearScreen.Conversations,
    val conversationsState: ConversationsUiState = ConversationsUiState.Loading,
    val messagesByConversation: Map<String, List<Message>> = emptyMap(),
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val pendingMutations: Int = 0,
    val selectedConversationId: String? = null,
    val settings: SettingsUiState = SettingsUiState(),
)

package com.wapp.wearmessage.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wapp.wearmessage.storage.CachedConversation
import com.wapp.wearmessage.storage.CachedMessage
import com.wapp.wearmessage.storage.CachedPendingMutation
import com.wapp.wearmessage.storage.CachedSyncSnapshot
import com.wapp.wearmessage.storage.SecureMessageCache
import com.wapp.wearmessage.sync.SyncInboundEvent
import com.wapp.wearmessage.sync.WearSyncBus
import com.wapp.wearmessage.sync.WearSyncTransport
import com.wapp.wearmessage.sync.contract.SyncMessageStatus
import com.wapp.wearmessage.sync.contract.WatchMutation
import com.wapp.wearmessage.sync.contract.WatchMutationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap
import kotlin.math.absoluteValue

private val contactNameByNormalizedNumber = ConcurrentHashMap<String, String>()

class MessagingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(WearMessagingUiState())
    val uiState: StateFlow<WearMessagingUiState> = _uiState.asStateFlow()
    private val secureCache = SecureMessageCache(application.applicationContext)
    private val settingsStore = SettingsStore(application.applicationContext)
    private val syncTransport = WearSyncTransport(application.applicationContext)
    private var hasReceivedSyncPayload = false
    private var persistCacheJob: Job? = null
    private var syncTimeoutJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var syncRequestTicket: Long = 0L
    private val pendingMutationIds = mutableSetOf<String>()
    private val pendingMutationConversationById = mutableMapOf<String, String>()
    private val pendingMutationById = mutableMapOf<String, PendingMutationRecord>()
    private val mutationAckTimeoutJobs = mutableMapOf<String, Job>()
    private var latestConversationBatchCursor: Long = Long.MIN_VALUE
    private var latestMessageBatchCursor: Long = Long.MIN_VALUE

    init {
        restoreSettings()
        warmContactNameCache()
        bootstrapData()
        observeIncomingSync()
        observeCachePersistence()
        observeMutationOutbox()
    }

    fun openConversations() {
        _uiState.update {
            it.copy(
                currentScreen = WearScreen.Conversations,
                selectedConversationId = null,
            )
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(currentScreen = WearScreen.Settings) }
    }

    fun openCompose() {
        _uiState.update { it.copy(currentScreen = WearScreen.Compose) }
    }

    fun openOrCreateConversationForContact(
        displayName: String,
        phoneNumber: String?,
    ) {
        val sanitizedName = displayName.trim().ifBlank { "New conversation" }
        val normalizedPhone = phoneNumber?.normalizedPhoneNumber()?.ifBlank { null }
        val existingConversationId =
            (_uiState.value.conversationsState as? ConversationsUiState.Success)
                ?.conversations
                ?.firstOrNull { conversation ->
                    conversation.matchesRecipient(
                        displayName = sanitizedName,
                        normalizedPhone = normalizedPhone,
                    )
                }
                ?.id

        if (existingConversationId != null) {
            openConversation(existingConversationId)
            return
        }

        val now = System.currentTimeMillis()
        val draftConversationId = buildDraftConversationId(sanitizedName, normalizedPhone)
        _uiState.update { state ->
            val existingConversations =
                (state.conversationsState as? ConversationsUiState.Success)
                    ?.conversations
                    .orEmpty()
            val participants =
                buildList {
                    add(sanitizedName)
                    if (normalizedPhone != null) {
                        add(normalizedPhone)
                    }
                }
            val draftConversation =
                Conversation(
                    id = draftConversationId,
                    title = sanitizedName,
                    participants = participants,
                    lastMessage = "New draft",
                    lastUpdatedAt = now.toRelativeTimestampLabel(now = now),
                    lastUpdatedAtEpochMillis = now,
                    unreadCount = 0,
                )
            val updatedConversations =
                (existingConversations.filterNot { it.id == draftConversationId } + draftConversation)
                    .sortedByDescending { conversation -> conversation.lastUpdatedAtEpochMillis }
                    .take(MAX_CONVERSATIONS_IN_MEMORY)
            state.copy(
                currentScreen = WearScreen.Thread(draftConversationId),
                selectedConversationId = draftConversationId,
                conversationsState = ConversationsUiState.Success(updatedConversations),
                messagesByConversation =
                    state.messagesByConversation + (draftConversationId to state.messagesByConversation[draftConversationId].orEmpty()),
            )
        }
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

    fun sendQuickReply(
        conversationId: String,
        quickReply: String,
    ) {
        val mutationId = newMutationId()
        val createdAtEpochMillis = System.currentTimeMillis()
        val recipientAddresses = recipientAddressesForConversation(conversationId)
        queueQuickReply(
            conversationId = conversationId,
            quickReply = quickReply,
            clientMutationId = mutationId,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        enqueueMutationForDelivery(
            WatchMutation(
                clientMutationId = mutationId,
                type = WatchMutationType.REPLY,
                conversationId = conversationId,
                messageBody = quickReply,
                recipientAddresses = recipientAddresses,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        )
    }

    private fun queueQuickReply(
        conversationId: String,
        quickReply: String,
        clientMutationId: String,
        createdAtEpochMillis: Long,
    ) {
        val optimisticMessage =
            Message(
                id = clientMutationId,
                conversationId = conversationId,
                senderId = "self",
                senderName = "You",
                body = quickReply,
                timestamp = "Now",
                timestampEpochMillis = createdAtEpochMillis,
                status = MessageStatus.Pending,
                localVersion = 1,
                outgoing = true,
            )

        _uiState.update { state ->
            state.copy(
                syncStatus = SyncStatus.Syncing,
                messagesByConversation =
                    state.messagesByConversation +
                        (conversationId to
                            (state.messagesByConversation[conversationId].orEmpty() + optimisticMessage)),
                conversationsState = state.conversationsState.withUpdatedConversation(conversationId) {
                    copy(
                        lastMessage = quickReply,
                        lastUpdatedAt = createdAtEpochMillis.toRelativeTimestampLabel(),
                        lastUpdatedAtEpochMillis = createdAtEpochMillis,
                    )
                },
            )
        }
    }

    fun markConversationRead(conversationId: String) {
        var shouldSendMutation = false
        _uiState.update { state ->
            val conversationHasUnread =
                (state.conversationsState as? ConversationsUiState.Success)
                    ?.conversations
                    ?.firstOrNull { conversation -> conversation.id == conversationId }
                    ?.unreadCount
                    ?.let { unread -> unread > 0 } == true
            val messagesContainUnread =
                state.messagesByConversation[conversationId]
                    .orEmpty()
                    .any { message -> !message.outgoing && message.status != MessageStatus.Read }
            shouldSendMutation = conversationHasUnread || messagesContainUnread
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
        if (shouldSendMutation) {
            queueAndSendMutation(
                type = WatchMutationType.MARK_READ,
                conversationId = conversationId,
            )
        }
    }

    fun archiveConversation(conversationId: String) {
        var hadConversation = false
        _uiState.update { state ->
            val existingConversations =
                (state.conversationsState as? ConversationsUiState.Success)
                    ?.conversations
                    .orEmpty()
            hadConversation = existingConversations.any { conversation -> conversation.id == conversationId }
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
        if (hadConversation) {
            queueAndSendMutation(
                type = WatchMutationType.ARCHIVE,
                conversationId = conversationId,
            )
        }
    }

    fun toggleConversationMute(conversationId: String) {
        var targetMutedState: Boolean? = null
        _uiState.update { state ->
            state.copy(
                conversationsState = state.conversationsState.withUpdatedConversation(conversationId) {
                    val toggled = !muted
                    targetMutedState = toggled
                    copy(muted = toggled)
                }
            )
        }
        val mutationType =
            when (targetMutedState) {
                true -> WatchMutationType.MUTE
                false -> WatchMutationType.UNMUTE
                null -> null
            }
        if (mutationType != null) {
            queueAndSendMutation(
                type = mutationType,
                conversationId = conversationId,
            )
        }
    }

    private fun enqueueMutationForDelivery(mutation: WatchMutation) {
        pendingMutationIds.add(mutation.clientMutationId)
        pendingMutationConversationById[mutation.clientMutationId] = mutation.conversationId
        pendingMutationById.putIfAbsent(
            mutation.clientMutationId,
            PendingMutationRecord(
                mutation = mutation,
                attemptCount = 0,
                nextRetryAtEpochMillis = System.currentTimeMillis(),
                awaitingAck = false,
            ),
        )
        _uiState.update { state ->
            state.copy(
                pendingMutations = pendingMutationIds.size,
                syncStatus = SyncStatus.Syncing,
            )
        }
        viewModelScope.launch {
            dispatchMutationIfDue(mutation.clientMutationId, force = true)
        }
    }

    private suspend fun dispatchMutationIfDue(
        clientMutationId: String,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val record =
            synchronized(pendingMutationById) {
                pendingMutationById[clientMutationId]
            } ?: return
        if (record.awaitingAck) return
        if (!force && now < record.nextRetryAtEpochMillis) return

        val sent = syncTransport.sendWatchMutation(record.mutation)
        if (sent) {
            synchronized(pendingMutationById) {
                pendingMutationById[clientMutationId] = record.copy(awaitingAck = true)
            }
            scheduleMutationAckTimeout(clientMutationId)
            _uiState.update { state ->
                state.copy(
                    pendingMutations = pendingMutationIds.size,
                    syncStatus = SyncStatus.Syncing,
                )
            }
        } else {
            markMutationForRetry(clientMutationId, nextAttemptCount = record.attemptCount + 1)
        }
    }

    private fun scheduleMutationAckTimeout(clientMutationId: String) {
        mutationAckTimeoutJobs.remove(clientMutationId)?.cancel()
        mutationAckTimeoutJobs[clientMutationId] =
            viewModelScope.launch {
                delay(MUTATION_ACK_TIMEOUT_MS)
                val record =
                    synchronized(pendingMutationById) {
                        pendingMutationById[clientMutationId]
                    } ?: return@launch
                if (!record.awaitingAck) return@launch
                markMutationForRetry(clientMutationId, nextAttemptCount = record.attemptCount + 1)
            }
    }

    private fun markMutationForRetry(
        clientMutationId: String,
        nextAttemptCount: Int,
    ) {
        mutationAckTimeoutJobs.remove(clientMutationId)?.cancel()
        synchronized(pendingMutationById) {
            val existing = pendingMutationById[clientMutationId] ?: return
            pendingMutationById[clientMutationId] =
                existing.copy(
                    attemptCount = nextAttemptCount,
                    nextRetryAtEpochMillis = System.currentTimeMillis() + retryBackoffMillis(nextAttemptCount),
                    awaitingAck = false,
                )
        }
        _uiState.update { state ->
            state.copy(
                pendingMutations = pendingMutationIds.size,
                syncStatus = SyncStatus.OfflineQueue,
            )
        }
    }

    private fun observeMutationOutbox() {
        outboxRetryJob?.cancel()
        outboxRetryJob =
            viewModelScope.launch {
                while (isActive) {
                    val dueMutationIds =
                        synchronized(pendingMutationById) {
                            val now = System.currentTimeMillis()
                            pendingMutationById.values
                                .asSequence()
                                .filter { record -> !record.awaitingAck && record.nextRetryAtEpochMillis <= now }
                                .sortedBy { record -> record.nextRetryAtEpochMillis }
                                .map { record -> record.mutation.clientMutationId }
                                .toList()
                        }
                    dueMutationIds.forEach { clientMutationId ->
                        dispatchMutationIfDue(clientMutationId, force = true)
                    }
                    delay(MUTATION_OUTBOX_TICK_MS)
                }
            }
    }

    private fun queueAndSendMutation(
        type: WatchMutationType,
        conversationId: String,
        messageBody: String? = null,
    ) {
        enqueueMutationForDelivery(
            WatchMutation(
                clientMutationId = newMutationId(),
                type = type,
                conversationId = conversationId,
                messageBody = messageBody,
                recipientAddresses = recipientAddressesForConversation(conversationId),
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun recipientAddressesForConversation(conversationId: String): List<String> =
        (_uiState.value.conversationsState as? ConversationsUiState.Success)
            ?.conversations
            ?.firstOrNull { conversation -> conversation.id == conversationId }
            ?.participants
            .orEmpty()
            .map { participant -> participant.normalizedPhoneNumber() }
            .filter { participant -> participant.isNotBlank() && participant.any(Char::isDigit) }
            .distinct()

    fun toggleHaptics(enabled: Boolean) {
        updateSettings { it.copy(hapticsEnabled = enabled) }
    }

    fun toggleMarkReadOnOpen(enabled: Boolean) {
        updateSettings { it.copy(markReadOnOpen = enabled) }
    }

    fun toggleGlassBoost(enabled: Boolean) {
        updateSettings { it.copy(glassBoostEnabled = enabled) }
    }

    fun setSyncProfile(syncProfile: SyncProfile) {
        var updatedSettings: SettingsUiState? = null
        _uiState.update { state ->
            val newSettings = state.settings.copy(syncProfile = syncProfile)
            updatedSettings = newSettings
            state.copy(
                settings = newSettings,
                syncStatus =
                    if (syncProfile == SyncProfile.BatterySaver) {
                        SyncStatus.Idle
                    } else {
                        state.syncStatus
                    },
            )
        }
        updatedSettings?.let(::persistSettingsAsync)
    }

    fun markSyncRequested() {
        syncRequestTicket += 1
        val ticket = syncRequestTicket
        _uiState.update { state -> state.copy(syncStatus = SyncStatus.Syncing) }
        scheduleSyncTimeout(ticket)
    }

    fun markSyncRequestFailed() {
        syncTimeoutJob?.cancel()
        _uiState.update { state -> state.copy(syncStatus = SyncStatus.OfflineQueue) }
    }

    fun onContactsPermissionChanged(granted: Boolean) {
        if (granted) {
            warmContactNameCache()
        }
    }

    private fun bootstrapData() {
        viewModelScope.launch {
            val restoredFromCache = restoreCachedData()
            _uiState.update {
                if (restoredFromCache) {
                    it.copy(
                        syncStatus =
                            if (it.pendingMutations > 0) {
                                SyncStatus.OfflineQueue
                            } else {
                                SyncStatus.Syncing
                            }
                    )
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
                            syncStatus =
                                if (state.syncStatus == SyncStatus.OfflineQueue) {
                                    SyncStatus.OfflineQueue
                                } else if (state.syncStatus == SyncStatus.Syncing) {
                                    SyncStatus.Syncing
                                } else {
                                    SyncStatus.Idle
                                },
                        )
                    } else {
                        state.copy(
                            syncStatus =
                                if (state.syncStatus == SyncStatus.OfflineQueue) {
                                    SyncStatus.OfflineQueue
                                } else if (state.pendingMutations > 0) {
                                    SyncStatus.OfflineQueue
                                } else if (state.syncStatus == SyncStatus.Syncing) {
                                    SyncStatus.Syncing
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
        val restoredPendingById =
            snapshot.pendingMutations
                .mapNotNull { cached -> cached.toPendingMutationRecordOrNull() }
                .associateBy { record -> record.mutation.clientMutationId }
        synchronized(pendingMutationById) {
            pendingMutationById.clear()
            pendingMutationById.putAll(restoredPendingById)
        }
        pendingMutationIds.clear()
        pendingMutationIds.addAll(restoredPendingById.keys)
        pendingMutationConversationById.clear()
        restoredPendingById.values.forEach { record ->
            pendingMutationConversationById[record.mutation.clientMutationId] = record.mutation.conversationId
        }
        restoredPendingById.values
            .filter { record -> record.awaitingAck }
            .forEach { record ->
                scheduleMutationAckTimeout(record.mutation.clientMutationId)
            }
        _uiState.update {
            val conversations =
                snapshot.conversations
                    .map { cached ->
                        Conversation(
                            id = cached.id,
                            title =
                                resolveConversationTitle(
                                    participants = cached.participants,
                                    fallbackTitle = cached.title,
                                    fallbackConversationId = cached.id,
                                ),
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
                                timestampEpochMillis = cached.timestampEpochMillis,
                                status = cached.status.toMessageStatusSafe(),
                                localVersion = cached.localVersion,
                                outgoing = cached.outgoing,
                            )
                        }
                            .sortedWith(compareBy<Message>({ it.timestampEpochMillis }, { it.id }))
                    }
            val restoredPendingCount = pendingMutationIds.size

            it.copy(
                conversationsState =
                    if (conversations.isEmpty()) {
                        ConversationsUiState.Empty
                    } else {
                        ConversationsUiState.Success(conversations)
                    },
                messagesByConversation = messagesByConversation,
                pendingMutations = restoredPendingCount,
                syncStatus = if (restoredPendingCount > 0) SyncStatus.OfflineQueue else SyncStatus.Idle,
            )
        }
        return snapshot.conversations.isNotEmpty() ||
            snapshot.messages.isNotEmpty() ||
            snapshot.pendingMutations.isNotEmpty()
    }

    private fun restoreSettings() {
        val persistedSettings = settingsStore.load()
        _uiState.update { state -> state.copy(settings = persistedSettings) }
    }

    private fun updateSettings(
        transform: (SettingsUiState) -> SettingsUiState,
    ) {
        var updatedSettings: SettingsUiState? = null
        _uiState.update { state ->
            val newSettings = transform(state.settings)
            if (newSettings != state.settings) {
                updatedSettings = newSettings
            }
            state.copy(settings = newSettings)
        }
        updatedSettings?.let(::persistSettingsAsync)
    }

    private fun persistSettingsAsync(settings: SettingsUiState) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.save(settings)
        }
    }

    private fun observeCachePersistence() {
        viewModelScope.launch {
            uiState
                .collect { state ->
                    persistCacheJob?.cancel()
                    persistCacheJob =
                        viewModelScope.launch {
                            delay(CACHE_PERSIST_DEBOUNCE_MS)
                            val snapshot =
                                withContext(Dispatchers.Default) {
                                    state.toCachedSyncSnapshotOrNull(
                                        pendingMutations =
                                            synchronized(pendingMutationById) {
                                                pendingMutationById.values
                                                    .map { record ->
                                                        CachedPendingMutation(
                                                            clientMutationId = record.mutation.clientMutationId,
                                                            type = record.mutation.type.name,
                                                            conversationId = record.mutation.conversationId,
                                                            messageBody = record.mutation.messageBody,
                                                            recipientAddresses = record.mutation.recipientAddresses,
                                                            createdAtEpochMillis = record.mutation.createdAtEpochMillis,
                                                            attemptCount = record.attemptCount,
                                                            nextRetryAtEpochMillis = record.nextRetryAtEpochMillis,
                                                            awaitingAck = record.awaitingAck,
                                                        )
                                                    }
                                            }
                                    )
                                } ?: return@launch
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    secureCache.save(snapshot)
                                }.onFailure { error ->
                                    Log.w(TAG, "Failed to persist encrypted cache snapshot", error)
                                }
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

    private fun warmContactNameCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedNames = loadContactNameMap()
            if (resolvedNames.isEmpty()) {
                return@launch
            }
            contactNameByNormalizedNumber.putAll(resolvedNames)
            withContext(Dispatchers.Default) {
                refreshParticipantLabelsFromCache()
            }
        }
    }

    private fun loadContactNameMap(): Map<String, String> {
        val context = getApplication<Application>().applicationContext
        val hasContactsPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPermission) {
            return emptyMap()
        }

        val projection =
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            )
        val mappedNames = mutableMapOf<String, String>()
        context.contentResolver
            .query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                if (numberIndex == -1 || nameIndex == -1) {
                    return emptyMap()
                }
                while (cursor.moveToNext()) {
                    val normalizedNumber = cursor.getString(numberIndex)?.normalizedPhoneNumber().orEmpty()
                    val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                    if (normalizedNumber.isNotEmpty() && displayName.isNotEmpty()) {
                        mappedNames.putIfAbsent(normalizedNumber, displayName)
                    }
                }
            }
        return mappedNames
    }

    private fun refreshParticipantLabelsFromCache() {
        _uiState.update { state ->
            val updatedConversationsState =
                when (val conversationsState = state.conversationsState) {
                    is ConversationsUiState.Success -> {
                        ConversationsUiState.Success(
                            conversationsState.conversations.map { conversation ->
                                conversation.copy(
                                    title =
                                        resolveConversationTitle(
                                            participants = conversation.participants,
                                            fallbackTitle = conversation.title,
                                            fallbackConversationId = conversation.id,
                                        ),
                                )
                            }
                        )
                    }
                    else -> conversationsState
                }
            val updatedMessagesByConversation =
                state.messagesByConversation.mapValues { (_, messages) ->
                    messages.map { message ->
                        if (message.outgoing) {
                            message
                        } else {
                            val resolved = resolveParticipantLabel(message.senderId)
                            if (resolved == message.senderName) {
                                message
                            } else {
                                message.copy(senderName = resolved)
                            }
                        }
                    }
                }
            state.copy(
                conversationsState = updatedConversationsState,
                messagesByConversation = updatedMessagesByConversation,
            )
        }
    }

    private fun applyConversationDelta(batch: com.wapp.wearmessage.sync.contract.ConversationDeltaBatch) {
        if (batch.cursor < latestConversationBatchCursor) {
            Log.d(TAG, "Ignoring stale conversations batch cursor=${batch.cursor}")
            return
        }
        hasReceivedSyncPayload = true
        syncTimeoutJob?.cancel()
        latestConversationBatchCursor = batch.cursor
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
                        title =
                            resolveConversationTitle(
                                participants = sync.participants,
                                fallbackTitle = null,
                                fallbackConversationId = sync.id,
                            ),
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
                    .take(MAX_CONVERSATIONS_IN_MEMORY)
            val retainedConversationIds = conversations.map { it.id }.toSet()

            state.copy(
                conversationsState =
                    if (conversations.isEmpty()) {
                        ConversationsUiState.Empty
                    } else {
                        ConversationsUiState.Success(conversations)
                    },
                messagesByConversation =
                    state.messagesByConversation.filterKeys { conversationId ->
                        retainedConversationIds.contains(conversationId)
                    },
                syncStatus = if (state.pendingMutations > 0) SyncStatus.Syncing else SyncStatus.Idle,
            )
        }
    }

    private fun applyMessageDelta(batch: com.wapp.wearmessage.sync.contract.MessageDeltaBatch) {
        if (batch.cursor < latestMessageBatchCursor) {
            Log.d(TAG, "Ignoring stale messages batch cursor=${batch.cursor}")
            return
        }
        hasReceivedSyncPayload = true
        syncTimeoutJob?.cancel()
        latestMessageBatchCursor = batch.cursor
        _uiState.update { state ->
            val deletedMessageIds = batch.deletedMessageIds.toHashSet()
            val groupedMessages = batch.messages.groupBy { it.conversationId }
            val snapshotConversationIds =
                if (batch.conversationIds.isNotEmpty()) {
                    batch.conversationIds.toSet()
                } else {
                    groupedMessages.keys
                }
            val participantsByConversationId =
                (state.conversationsState as? ConversationsUiState.Success)
                    ?.conversations
                    ?.associate { conversation -> conversation.id to conversation.participants }
                    .orEmpty()
            val baseMessagesByConversation =
                state.messagesByConversation
                    .mapValues { (_, existingMessages) ->
                        existingMessages.filterNot { message ->
                            deletedMessageIds.contains(message.id)
                        }
                    }
                    .toMutableMap()
            val conversationIdsToProcess =
                linkedSetOf<String>().apply {
                    addAll(snapshotConversationIds)
                    addAll(groupedMessages.keys)
                }

            conversationIdsToProcess.forEach { conversationId ->
                val syncMessages = groupedMessages[conversationId].orEmpty()
                val mergedById =
                    if (snapshotConversationIds.contains(conversationId)) {
                        LinkedHashMap()
                    } else {
                        LinkedHashMap<String, Message>().apply {
                            baseMessagesByConversation[conversationId]
                                .orEmpty()
                                .forEach { message ->
                                    put(message.id, message)
                                }
                        }
                    }
                syncMessages
                    .sortedBy { it.timestampEpochMillis }
                    .forEach { sync ->
                        if (!deletedMessageIds.contains(sync.id)) {
                            val participants =
                                participantsByConversationId[sync.conversationId].orEmpty()
                            val isOutgoing = inferOutgoing(sync, participants)
                            mergedById[sync.id] =
                                Message(
                                    id = sync.id,
                                    conversationId = sync.conversationId,
                                    senderId = sync.senderId,
                                    senderName =
                                        if (isOutgoing) {
                                            "You"
                                        } else {
                                            resolveParticipantLabel(sync.senderId).ifBlank { "Unknown" }
                                        },
                                    body = sync.body,
                                    timestamp = sync.timestampEpochMillis.toClockLabel(),
                                    timestampEpochMillis = sync.timestampEpochMillis,
                                    status = sync.status.toMessageStatus(),
                                    localVersion = sync.localVersion,
                                    outgoing = isOutgoing,
                                )
                        }
                    }
                baseMessagesByConversation[conversationId] = mergedById.values.toList()
            }

            val mergedMessagesByConversation =
                baseMessagesByConversation
                    .filterValues { messages -> messages.isNotEmpty() }
                    .mapValues { (_, messages) ->
                        if (messages.size <= MAX_MESSAGES_PER_CONVERSATION_IN_MEMORY) {
                            messages
                        } else {
                            messages.takeLast(MAX_MESSAGES_PER_CONVERSATION_IN_MEMORY)
                        }
                }

            state.copy(
                messagesByConversation = mergedMessagesByConversation,
                syncStatus = if (state.pendingMutations > 0) SyncStatus.Syncing else SyncStatus.Idle,
            )
        }
    }

    private fun applyMutationAck(ack: com.wapp.wearmessage.sync.contract.MutationAck) {
        syncTimeoutJob?.cancel()
        mutationAckTimeoutJobs.remove(ack.clientMutationId)?.cancel()
        synchronized(pendingMutationById) {
            pendingMutationById.remove(ack.clientMutationId)
        }
        _uiState.update { state ->
            val wasPending = pendingMutationIds.remove(ack.clientMutationId)
            val conversationId =
                pendingMutationConversationById.remove(ack.clientMutationId)
                    ?: findConversationIdByMessageId(
                        state = state,
                        mutationId = ack.clientMutationId,
                    )
            val updatedMessagesByConversation =
                if (conversationId == null) {
                    state.messagesByConversation
                } else {
                    val updatedMessages =
                        state.messagesByConversation[conversationId]
                            .orEmpty()
                            .map { message ->
                                if (message.id == ack.clientMutationId) {
                                    message.copy(
                                        status = if (ack.accepted) MessageStatus.Sent else MessageStatus.Failed,
                                        localVersion = message.localVersion + 1,
                                    )
                                } else {
                                    message
                                }
                            }
                    state.messagesByConversation + (conversationId to updatedMessages)
                }
            val pendingAfterAck =
                if (wasPending) {
                    pendingMutationIds.size
                } else {
                    pendingMutationIds.size
                }
            state.copy(
                pendingMutations = pendingAfterAck,
                syncStatus =
                    if (!ack.accepted) {
                        SyncStatus.OfflineQueue
                    } else if (pendingAfterAck > 0) {
                        SyncStatus.Syncing
                    } else {
                        SyncStatus.Idle
                    },
                messagesByConversation = updatedMessagesByConversation,
            )
        }
    }

    override fun onCleared() {
        persistCacheJob?.cancel()
        syncTimeoutJob?.cancel()
        outboxRetryJob?.cancel()
        mutationAckTimeoutJobs.values.forEach { it.cancel() }
        mutationAckTimeoutJobs.clear()
        pendingMutationIds.clear()
        pendingMutationConversationById.clear()
        synchronized(pendingMutationById) {
            pendingMutationById.clear()
        }
        latestConversationBatchCursor = Long.MIN_VALUE
        latestMessageBatchCursor = Long.MIN_VALUE
        super.onCleared()
    }

    private fun scheduleSyncTimeout(ticket: Long) {
        syncTimeoutJob?.cancel()
        syncTimeoutJob =
            viewModelScope.launch {
                delay(SYNC_REQUEST_TIMEOUT_MS)
                _uiState.update { state ->
                    if (ticket != syncRequestTicket) {
                        state
                    } else if (state.syncStatus != SyncStatus.Syncing) {
                        state
                    } else {
                        state.copy(syncStatus = SyncStatus.OfflineQueue)
                    }
                }
            }
    }

    private fun findConversationIdByMessageId(
        state: WearMessagingUiState,
        mutationId: String,
    ): String? =
        state.messagesByConversation.entries.firstOrNull { (_, messages) ->
            messages.any { message -> message.id == mutationId }
        }?.key
}

private data class PendingMutationRecord(
    val mutation: WatchMutation,
    val attemptCount: Int,
    val nextRetryAtEpochMillis: Long,
    val awaitingAck: Boolean,
)

private fun CachedPendingMutation.toPendingMutationRecordOrNull(): PendingMutationRecord? {
    val parsedType = runCatching { WatchMutationType.valueOf(type) }.getOrNull() ?: return null
    return PendingMutationRecord(
        mutation =
            WatchMutation(
                clientMutationId = clientMutationId,
                type = parsedType,
                conversationId = conversationId,
                messageBody = messageBody,
                recipientAddresses = recipientAddresses,
                createdAtEpochMillis = createdAtEpochMillis,
            ),
        attemptCount = attemptCount.coerceAtLeast(0),
        nextRetryAtEpochMillis = nextRetryAtEpochMillis.coerceAtLeast(0L),
        awaitingAck = awaitingAck,
    )
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

private fun Conversation.matchesRecipient(
    displayName: String,
    normalizedPhone: String?,
): Boolean {
    if (title.equals(displayName, ignoreCase = true)) {
        return true
    }
    if (participants.any { participant -> participant.equals(displayName, ignoreCase = true) }) {
        return true
    }
    if (normalizedPhone != null) {
        return participants.any { participant ->
            participant.normalizedPhoneNumber() == normalizedPhone
        }
    }
    return false
}

private fun buildDraftConversationId(
    displayName: String,
    normalizedPhone: String?,
): String {
    val idSource =
        normalizedPhone
            ?: displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val normalizedSource = idSource.ifBlank { "contact" }
    return "$DRAFT_CONVERSATION_PREFIX$normalizedSource"
}

private fun String.normalizedPhoneNumber(): String {
    val normalized = trim().filter { character -> character.isDigit() || character == '+' }
    return normalized
}

private fun MessagingViewModel.resolveConversationTitle(
    participants: List<String>,
    fallbackTitle: String?,
    fallbackConversationId: String,
): String {
    val mappedParticipants =
        participants
            .map { participant -> resolveParticipantLabel(participant) }
            .map { participant -> participant.trim() }
            .filter { participant -> participant.isNotEmpty() }
            .distinct()
    if (mappedParticipants.isEmpty()) {
        return fallbackTitle?.trim().orEmpty().ifBlank { "Conversation $fallbackConversationId" }
    }
    if (mappedParticipants.size == 1) {
        return mappedParticipants.first()
    }
    return "${mappedParticipants.first()} +${mappedParticipants.size - 1}"
}

private fun MessagingViewModel.resolveParticipantLabel(rawValue: String): String {
    val value = rawValue.trim()
    if (value.isEmpty()) {
        return ""
    }
    if (!value.looksLikePhoneNumber()) {
        return value
    }
    val normalized = value.normalizedPhoneNumber()
    if (normalized.isEmpty()) {
        return value
    }
    return contactNameByNormalizedNumber[normalized] ?: value
}

private fun String.looksLikePhoneNumber(): Boolean =
    any { character -> character.isDigit() }

private fun inferOutgoing(
    sync: com.wapp.wearmessage.sync.contract.SyncMessage,
    participants: List<String>,
): Boolean {
    if (sync.outgoing != null) {
        return sync.outgoing
    }
    if (sync.senderId.equals("self", ignoreCase = true)) {
        return true
    }

    val senderNormalized = sync.senderId.normalizedPhoneNumber()
    if (senderNormalized.isEmpty()) {
        return when (sync.status) {
            SyncMessageStatus.PENDING,
            SyncMessageStatus.SENT,
            SyncMessageStatus.FAILED -> true

            SyncMessageStatus.DELIVERED,
            SyncMessageStatus.READ -> false
        }
    }
    val participantNumbers =
        participants
            .map { participant -> participant.normalizedPhoneNumber() }
            .filter { participant -> participant.isNotEmpty() }
            .toSet()
    val senderMatchesParticipant =
        senderNormalized.isNotEmpty() && participantNumbers.contains(senderNormalized)

    return when (sync.status) {
        SyncMessageStatus.PENDING,
        SyncMessageStatus.SENT,
        SyncMessageStatus.FAILED -> true

        SyncMessageStatus.DELIVERED,
        SyncMessageStatus.READ -> !senderMatchesParticipant
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

private fun WearMessagingUiState.toCachedSyncSnapshotOrNull(
    pendingMutations: List<CachedPendingMutation>,
): CachedSyncSnapshot? {
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
            .map { conversationMessages ->
                if (conversationMessages.size <= MAX_MESSAGES_PER_CONVERSATION_CACHE) {
                    conversationMessages
                } else {
                    conversationMessages.takeLast(MAX_MESSAGES_PER_CONVERSATION_CACHE)
                }
            }
            .flatten()
            .sortedWith(
                compareBy<Message>({ it.conversationId }, { it.timestampEpochMillis }, { it.id })
            )
            .let { sortedMessages ->
                if (sortedMessages.size <= MAX_MESSAGES_TOTAL_CACHE) {
                    sortedMessages
                } else {
                    sortedMessages.takeLast(MAX_MESSAGES_TOTAL_CACHE)
                }
            }
            .map { message ->
                CachedMessage(
                    id = message.id,
                    conversationId = message.conversationId,
                    senderId = message.senderId,
                    senderName = message.senderName,
                    body = message.body,
                    timestamp = message.timestamp,
                    timestampEpochMillis = message.timestampEpochMillis,
                    status = message.status.name,
                    localVersion = message.localVersion,
                    outgoing = message.outgoing,
                )
            }

    return CachedSyncSnapshot(
        conversations = conversations,
        messages = messages,
        pendingMutations = pendingMutations,
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
    return runCatching {
        Instant.ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .format(CLOCK_FORMATTER)
    }.getOrElse {
        val minutes = (this / 60_000L) % (24 * 60)
        val hour = (minutes / 60).toInt()
        val minute = (minutes % 60).toInt().absoluteValue
        "%02d:%02d".format(hour, minute)
    }
}

private fun retryBackoffMillis(attemptCount: Int): Long {
    val boundedAttempts = attemptCount.coerceIn(1, 6)
    val multiplier = 1L shl (boundedAttempts - 1)
    return (MUTATION_RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(MUTATION_RETRY_MAX_DELAY_MS)
}

private const val CACHE_PERSIST_DEBOUNCE_MS = 500L
private const val SYNC_REQUEST_TIMEOUT_MS = 12_000L
private const val MUTATION_ACK_TIMEOUT_MS = 15_000L
private const val MUTATION_OUTBOX_TICK_MS = 3_000L
private const val MUTATION_RETRY_BASE_DELAY_MS = 2_000L
private const val MUTATION_RETRY_MAX_DELAY_MS = 60_000L
private const val MAX_CONVERSATIONS_IN_MEMORY = 300
private const val MAX_MESSAGES_PER_CONVERSATION_IN_MEMORY = 250
private const val MAX_MESSAGES_PER_CONVERSATION_CACHE = 120
private const val MAX_MESSAGES_TOTAL_CACHE = 1500
private const val DRAFT_CONVERSATION_PREFIX = "draft-"
private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val TAG = "MessagingViewModel"

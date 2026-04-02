package com.wapp.wearmessage.presentation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.material3.AppScaffold
import com.wapp.wearmessage.presentation.theme.WessageTheme
import com.wapp.wearmessage.sync.WearSyncTransport
import com.wapp.wearmessage.sync.contract.WatchMutation
import com.wapp.wearmessage.sync.contract.WatchMutationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun WearMessagingApp(
    viewModel: MessagingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val syncTransport = remember(context) { WearSyncTransport(context) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val layoutSpec = rememberWearLayoutSpec()
    val canSwipeBack = uiState.currentScreen != WearScreen.Conversations
    val syncProfile = uiState.settings.syncProfile
    val initialConversationLimit = syncProfile.initialBootstrapLimit()
    val conversationPageSize = syncProfile.pageSize()
    val periodicSyncIntervalMs = syncProfile.periodicSyncIntervalMs()
    val loadedConversationCount =
        (uiState.conversationsState as? ConversationsUiState.Success)
            ?.conversations
            ?.size ?: 0
    var loadingMoreConversations by remember { mutableStateOf(false) }
    var conversationsExhausted by remember { mutableStateOf(false) }
    var manualSyncInFlight by remember { mutableStateOf(false) }
    var pendingPageOffset by remember { mutableStateOf<Int?>(null) }
    var pendingPageLimit by remember { mutableIntStateOf(0) }
    var contactsPermissionGranted by remember {
        mutableStateOf(checkContactsPermission(context))
    }

    suspend fun requestBootstrapSync(limit: Int, offset: Int): Boolean {
        viewModel.markSyncRequested()
        val sent =
            syncTransport.requestBootstrapSync(
                limit = limit,
                offset = offset,
            )
        if (!sent) {
            viewModel.markSyncRequestFailed()
        }
        return sent
    }

    LaunchedEffect(loadedConversationCount, pendingPageOffset, pendingPageLimit) {
        val offset = pendingPageOffset ?: return@LaunchedEffect
        val newItemsCount = (loadedConversationCount - offset).coerceAtLeast(0)
        conversationsExhausted = newItemsCount < pendingPageLimit
        loadingMoreConversations = false
        pendingPageOffset = null
        pendingPageLimit = 0
    }

    LaunchedEffect(syncProfile) {
        pendingPageOffset = 0
        conversationsExhausted = false
        pendingPageLimit = initialConversationLimit
        requestBootstrapSync(limit = initialConversationLimit, offset = 0)
    }

    LaunchedEffect(syncProfile, periodicSyncIntervalMs, initialConversationLimit) {
        while (isActive) {
            delay(periodicSyncIntervalMs)
            if (manualSyncInFlight) continue
            requestBootstrapSync(limit = initialConversationLimit, offset = 0)
        }
    }

    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsPermissionGranted = granted
        }

    fun launchComposeContactsScreen() {
        performActionHapticIfEnabled(uiState.settings.hapticsEnabled, hapticFeedback)
        contactsPermissionGranted = checkContactsPermission(context)
        viewModel.openCompose()
    }

    fun requestSyncNow() {
        if (manualSyncInFlight) return
        manualSyncInFlight = true
        performActionHapticIfEnabled(uiState.settings.hapticsEnabled, hapticFeedback)
        scope.launch {
            try {
                requestBootstrapSync(limit = initialConversationLimit, offset = 0)
            } finally {
                manualSyncInFlight = false
            }
        }
    }

    WessageTheme(glassBoostEnabled = uiState.settings.glassBoostEnabled) {
        val activeThreadTitle =
            when (val screen = uiState.currentScreen) {
                is WearScreen.Thread ->
                    (uiState.conversationsState as? ConversationsUiState.Success)
                        ?.conversations
                        ?.firstOrNull { it.id == screen.conversationId }
                        ?.title
                else -> null
            }
        AppScaffold(
            timeText = {
                SyncAwareTimeText(
                    syncStatus = uiState.syncStatus,
                    pendingMutations = uiState.pendingMutations,
                    threadTitle = activeThreadTitle,
                    showSyncStatus = uiState.currentScreen == WearScreen.Conversations,
                )
            }
        ) {
            BasicSwipeToDismissBox(
                onDismissed = viewModel::navigateBack,
                userSwipeEnabled = canSwipeBack,
                backgroundKey = WearScreen.Conversations.navKey(),
                contentKey = uiState.currentScreen.navKey(),
            ) { isBackground ->
                val activeScreen =
                    if (isBackground && canSwipeBack) {
                        WearScreen.Conversations
                    } else {
                        uiState.currentScreen
                    }
                when (activeScreen) {
                    WearScreen.Conversations ->
                        ConversationsScreen(
                            uiState = uiState,
                            layoutSpec = layoutSpec,
                            actionsEnabled = !isBackground,
                            canLoadMore = !conversationsExhausted,
                            isLoadingMore = loadingMoreConversations,
                            isManualSyncing = manualSyncInFlight,
                            onLoadMoreConversations = {
                                if (!loadingMoreConversations && !conversationsExhausted) {
                                    val nextOffset = loadedConversationCount
                                    if (nextOffset >= MAX_CONVERSATION_COUNT) {
                                        conversationsExhausted = true
                                        return@ConversationsScreen
                                    }
                                    loadingMoreConversations = true
                                    scope.launch {
                                        pendingPageOffset = nextOffset
                                        pendingPageLimit = conversationPageSize
                                        val sent =
                                            syncTransport.requestBootstrapSync(
                                                limit = conversationPageSize,
                                                offset = nextOffset,
                                            )
                                        if (!sent) {
                                            loadingMoreConversations = false
                                            pendingPageOffset = null
                                            pendingPageLimit = 0
                                        }
                                    }
                                }
                            },
                            onOpenConversation = { conversationId ->
                                performActionHapticIfEnabled(uiState.settings.hapticsEnabled, hapticFeedback)
                                viewModel.openConversation(conversationId)
                            },
                            onComposeConversation = ::launchComposeContactsScreen,
                            onOpenSettings = {
                                performActionHapticIfEnabled(uiState.settings.hapticsEnabled, hapticFeedback)
                                viewModel.openSettings()
                            },
                            onSyncNow = ::requestSyncNow,
                        )
                    WearScreen.Compose ->
                        ComposeContactsScreen(
                            layoutSpec = layoutSpec,
                            actionsEnabled = !isBackground,
                            contactsPermissionGranted = contactsPermissionGranted,
                            onRequestContactsPermission = {
                                if (isBackground) return@ComposeContactsScreen
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                            onSelectContact = { contact ->
                                performActionHapticIfEnabled(uiState.settings.hapticsEnabled, hapticFeedback)
                                viewModel.openOrCreateConversationForContact(
                                    displayName = contact.displayName,
                                    phoneNumber = contact.phoneNumber,
                                )
                            },
                        )
                    WearScreen.Settings ->
                        SettingsScreen(
                            layoutSpec = layoutSpec,
                            settings = uiState.settings,
                            actionsEnabled = !isBackground,
                            onHapticsToggle = viewModel::toggleHaptics,
                            onMarkReadToggle = viewModel::toggleMarkReadOnOpen,
                            onGlassToggle = viewModel::toggleGlassBoost,
                            onSyncProfileSelected = viewModel::setSyncProfile,
                        )
                    is WearScreen.Thread -> {
                        val conversation =
                            (uiState.conversationsState as? ConversationsUiState.Success)
                                ?.conversations
                                ?.firstOrNull { it.id == activeScreen.conversationId }
                        ThreadScreen(
                            layoutSpec = layoutSpec,
                            conversation = conversation,
                            messages = uiState.messagesByConversation[activeScreen.conversationId].orEmpty(),
                            actionsEnabled = !isBackground,
                            hapticsEnabled = uiState.settings.hapticsEnabled,
                            onQuickReply = { text ->
                                viewModel.queueQuickReply(activeScreen.conversationId, text)
                                scope.launch {
                                    syncTransport.sendWatchMutation(
                                        WatchMutation(
                                            clientMutationId = newMutationId(),
                                            type = WatchMutationType.REPLY,
                                            conversationId = activeScreen.conversationId,
                                            messageBody = text,
                                            createdAtEpochMillis = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

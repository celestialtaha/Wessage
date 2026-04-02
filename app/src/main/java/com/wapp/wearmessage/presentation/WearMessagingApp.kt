package com.wapp.wearmessage.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.ButtonGroupDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.ScrollIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.timeTextCurvedText
import com.wapp.wearmessage.presentation.theme.WessageTheme
import com.wapp.wearmessage.sync.WearSyncTransport
import com.wapp.wearmessage.sync.contract.WatchMutation
import com.wapp.wearmessage.sync.contract.WatchMutationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

    fun performActionHaptic() {
        if (uiState.settings.hapticsEnabled) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
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
        viewModel.markSyncRequested()
        val sent =
            syncTransport.requestBootstrapSync(
                limit = initialConversationLimit,
                offset = 0,
            )
        if (!sent) {
            viewModel.markSyncRequestFailed()
        }
    }

    LaunchedEffect(syncProfile, periodicSyncIntervalMs, initialConversationLimit) {
        while (isActive) {
            delay(periodicSyncIntervalMs)
            if (manualSyncInFlight) continue
            viewModel.markSyncRequested()
            val sent =
                syncTransport.requestBootstrapSync(
                    limit = initialConversationLimit,
                    offset = 0,
                )
            if (!sent) {
                viewModel.markSyncRequestFailed()
            }
        }
    }

    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsPermissionGranted = granted
        }

    fun launchComposeContactsScreen() {
        performActionHaptic()
        contactsPermissionGranted = checkContactsPermission(context)
        viewModel.openCompose()
    }

    fun requestSyncNow() {
        if (manualSyncInFlight) return
        manualSyncInFlight = true
        performActionHaptic()
        scope.launch {
            viewModel.markSyncRequested()
            val sent =
                syncTransport.requestBootstrapSync(
                    limit = initialConversationLimit,
                    offset = 0,
                )
            if (!sent) {
                viewModel.markSyncRequestFailed()
            }
            manualSyncInFlight = false
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
                                performActionHaptic()
                                viewModel.openConversation(conversationId)
                            },
                            onComposeConversation = ::launchComposeContactsScreen,
                            onOpenSettings = {
                                performActionHaptic()
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
                                performActionHaptic()
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

@Composable
private fun ConversationsScreen(
    uiState: WearMessagingUiState,
    layoutSpec: WearLayoutSpec,
    actionsEnabled: Boolean,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    isManualSyncing: Boolean,
    onLoadMoreConversations: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onComposeConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val compactScreen = layoutSpec.compact
    val listState = rememberScalingLazyListState()
    val conversationCount =
        (uiState.conversationsState as? ConversationsUiState.Success)
            ?.conversations
            ?.size ?: 0
    val showSyncAction =
        uiState.conversationsState is ConversationsUiState.Error ||
            uiState.syncStatus == SyncStatus.OfflineQueue
    val syncStatusLabel =
        when {
            isManualSyncing -> "Syncing now…"
            uiState.syncStatus == SyncStatus.Syncing -> "Syncing…"
            uiState.syncStatus == SyncStatus.OfflineQueue ->
                if (uiState.pendingMutations > 0) {
                    "${uiState.pendingMutations} pending offline"
                } else {
                    "Offline from phone"
                }
            uiState.pendingMutations > 0 -> "${uiState.pendingMutations} pending"
            else -> null
        }
    val centerEmptyStateContent =
        uiState.conversationsState is ConversationsUiState.Empty &&
            !showSyncAction &&
            syncStatusLabel == null

    LaunchedEffect(listState, conversationCount, canLoadMore, isLoadingMore) {
        if (conversationCount == 0 || !canLoadMore) return@LaunchedEffect
        snapshotFlow { listState.centerItemIndex }
            .distinctUntilChanged()
            .filter { centerIndex -> centerIndex >= (conversationCount + LOAD_MORE_TRIGGER_PREFIX_ITEMS - LOAD_MORE_THRESHOLD_ITEMS) }
            .collect {
                if (!isLoadingMore) {
                    onLoadMoreConversations()
                }
            }
    }

    WearScreenShell(layoutSpec = layoutSpec, listState = listState) {
        if (centerEmptyStateContent) {
            item { Spacer(modifier = Modifier.height(layoutSpec.emptyHomeTopSpacer)) }
        }
        item {
            val composeActionSource = remember { MutableInteractionSource() }
            val settingsActionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(layoutSpec.topActionRowWidthFraction),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    CompactButton(
                        onClick = onComposeConversation,
                        enabled = actionsEnabled,
                        interactionSource = composeActionSource,
                        modifier =
                            Modifier
                                .weight(1f)
                                .minWidth(layoutSpec.actionButtonMinWidth)
                                .animateWidth(composeActionSource),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Create,
                                contentDescription = "Compose",
                            )
                        },
                    )
                    CompactButton(
                        onClick = onOpenSettings,
                        enabled = actionsEnabled,
                        interactionSource = settingsActionSource,
                        modifier =
                            Modifier
                                .weight(1f)
                                .minWidth(layoutSpec.actionButtonMinWidth)
                                .animateWidth(settingsActionSource),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Settings",
                            )
                        },
                    )
                }
            }
        }
        if (centerEmptyStateContent) {
            item { Spacer(modifier = Modifier.height(6.dp)) }
        }
        if (syncStatusLabel != null) {
            item {
                Text(
                    text = syncStatusLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (showSyncAction) {
            item {
                FilledTonalButton(
                    onClick = onSyncNow,
                    enabled = actionsEnabled && !isManualSyncing,
                    modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                ) {
                    Text(
                        if (isManualSyncing) {
                            "Syncing…"
                        } else if (uiState.conversationsState is ConversationsUiState.Error) {
                            "Retry sync"
                        } else {
                            "Sync now"
                        }
                    )
                }
            }
        }
        when (val state = uiState.conversationsState) {
            ConversationsUiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
            ConversationsUiState.Empty -> {
                item {
                    TitleCard(
                        onClick = onComposeConversation,
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = { Text("No conversations yet") },
                        subtitle = { Text("Tap compose to start") },
                        enabled = actionsEnabled,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    )
                }
            }
            is ConversationsUiState.Error -> {
                item {
                    TitleCard(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = { Text("Sync error") },
                        subtitle = {
                            Text(
                                text = state.message,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = false,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    )
                }
            }
            is ConversationsUiState.Success -> {
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        enabled = actionsEnabled,
                        layoutSpec = layoutSpec,
                        onClick = { onOpenConversation(conversation.id) },
                    )
                }
                if (isLoadingMore) {
                    item {
                        Text(
                            text = "Loading more…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun ComposeContactsScreen(
    layoutSpec: WearLayoutSpec,
    actionsEnabled: Boolean,
    contactsPermissionGranted: Boolean,
    onRequestContactsPermission: () -> Unit,
    onSelectContact: (DeviceContact) -> Unit,
) {
    var reloadToken by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val contactsState by
        produceState<ContactsPickerUiState>(
            initialValue =
                if (contactsPermissionGranted) {
                    ContactsPickerUiState.Loading
                } else {
                    ContactsPickerUiState.PermissionRequired
                },
            contactsPermissionGranted,
            reloadToken,
            context,
        ) {
            if (!contactsPermissionGranted) {
                value = ContactsPickerUiState.PermissionRequired
                return@produceState
            }
            value = ContactsPickerUiState.Loading
            value =
                runCatching {
                    withContext(Dispatchers.IO) {
                        queryDeviceContacts(context)
                    }
                }.fold(
                    onSuccess = { contacts ->
                        if (contacts.isEmpty()) {
                            ContactsPickerUiState.Empty
                        } else {
                            ContactsPickerUiState.Success(contacts)
                        }
                    },
                    onFailure = { throwable ->
                        ContactsPickerUiState.Error(
                            throwable.message ?: "Unable to load contacts",
                        )
                    },
                )
        }

    WearScreenShell(layoutSpec = layoutSpec) {
        item { ListHeader { Text("New message") } }
        when (val state = contactsState) {
            ContactsPickerUiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp))
                    }
                }
            }
            ContactsPickerUiState.PermissionRequired -> {
                item {
                    TitleCard(
                        onClick = onRequestContactsPermission,
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = { Text("Contacts permission needed") },
                        subtitle = { Text("Allow access to select a recipient") },
                        enabled = actionsEnabled,
                        colors = glassCardColors(),
                        border = glassBorder(),
                        content = {
                            Spacer(modifier = Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = onRequestContactsPermission,
                                enabled = actionsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Grant permission")
                            }
                        },
                    )
                }
            }
            ContactsPickerUiState.Empty -> {
                item {
                    TitleCard(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = { Text("No contacts found") },
                        subtitle = { Text("Add contacts on phone and sync") },
                        enabled = false,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    )
                }
            }
            is ContactsPickerUiState.Error -> {
                item {
                    TitleCard(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = { Text("Failed to load contacts") },
                        subtitle = {
                            Text(
                                text = state.message,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = false,
                        colors = glassCardColors(),
                        border = glassBorder(),
                        content = {
                            Spacer(modifier = Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = { reloadToken++ },
                                enabled = actionsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Retry")
                            }
                        },
                    )
                }
            }
            is ContactsPickerUiState.Success -> {
                items(state.contacts, key = { it.id }) { contact ->
                    TitleCard(
                        onClick = { onSelectContact(contact) },
                        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                        title = {
                            Text(
                                text = contact.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        subtitle = {
                            Text(
                                text = contact.phoneNumber ?: "No phone number",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = actionsEnabled,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadScreen(
    layoutSpec: WearLayoutSpec,
    conversation: Conversation?,
    messages: List<Message>,
    actionsEnabled: Boolean,
    hapticsEnabled: Boolean,
    onQuickReply: (String) -> Unit,
) {
    val compactScreen = layoutSpec.compact
    val hapticFeedback = LocalHapticFeedback.current
    val expandedMessageIds = remember(conversation?.id) { mutableStateListOf<String>() }
    val listState = rememberScalingLazyListState()
    var keyboardComposerVisible by remember { mutableStateOf(false) }
    var keyboardDraft by remember { mutableStateOf("") }
    var initialPositionedAtLatest by remember(conversation?.id) { mutableStateOf(false) }
    val keyboardActionSource = remember { MutableInteractionSource() }
    val voiceActionSource = remember { MutableInteractionSource() }
    val voiceReplyLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val voiceText =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (voiceText.isNotBlank()) {
                if (hapticsEnabled) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onQuickReply(voiceText)
            }
        }

    fun performActionHaptic() {
        if (hapticsEnabled) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun submitReply(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        performActionHaptic()
        onQuickReply(normalized)
        keyboardDraft = ""
        keyboardComposerVisible = false
    }

    fun toggleExpanded(messageId: String) {
        performActionHaptic()
        if (expandedMessageIds.contains(messageId)) {
            expandedMessageIds.remove(messageId)
        } else {
            expandedMessageIds.add(messageId)
        }
    }

    fun openKeyboardInput() {
        performActionHaptic()
        keyboardComposerVisible = true
    }

    fun openVoiceInput() {
        performActionHaptic()
        val intent = createVoiceReplyIntent(conversation?.title)
        runCatching { voiceReplyLauncher.launch(intent) }
            .onFailure {
                keyboardComposerVisible = true
            }
    }

    LaunchedEffect(conversation?.id, messages.size) {
        if (!initialPositionedAtLatest && messages.isNotEmpty()) {
            listState.scrollToItem(index = messages.lastIndex)
            initialPositionedAtLatest = true
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButtonSpacing = layoutSpec.edgeButtonSpacing,
        edgeButton = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(layoutSpec.edgeActionRowWidthFraction),
                    contentPadding = ButtonGroupDefaults.fullWidthPaddings(),
                ) {
                    CompactButton(
                        onClick = ::openKeyboardInput,
                        enabled = actionsEnabled,
                        interactionSource = keyboardActionSource,
                        modifier =
                            Modifier
                                .weight(1f)
                                .minWidth(layoutSpec.actionButtonMinWidth)
                                .animateWidth(keyboardActionSource),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = "Keyboard input",
                            )
                        },
                    )
                    CompactButton(
                        onClick = ::openVoiceInput,
                        enabled = actionsEnabled,
                        interactionSource = voiceActionSource,
                        modifier =
                            Modifier
                                .weight(1f)
                                .minWidth(layoutSpec.actionButtonMinWidth)
                                .animateWidth(voiceActionSource),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = "Voice input",
                            )
                        },
                    )
                }
            }
        },
        scrollIndicator = {
            ScrollIndicator(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
                colors =
                    ScrollIndicatorDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.36f),
                    ),
                positionAnimationSpec = androidx.compose.animation.core.snap(),
            )
        },
    ) { contentPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(glassBackgroundBrush(compactScreen = compactScreen))
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                autoCentering = null,
                contentPadding = contentPadding,
                verticalArrangement =
                    Arrangement.spacedBy(if (compactScreen) 6.dp else 8.dp, Alignment.Top),
            ) {
                if (messages.isEmpty()) {
                    item {
                        TitleCard(
                            onClick = ::openKeyboardInput,
                            title = { Text("No messages yet") },
                            subtitle = { Text("Use bottom buttons to reply") },
                            enabled = actionsEnabled,
                            colors = glassCardColors(),
                            border = glassBorder(),
                        )
                    }
                } else {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            layoutSpec = layoutSpec,
                            expanded = expandedMessageIds.contains(message.id),
                            onToggleExpanded = { toggleExpanded(message.id) },
                        )
                    }
                }
            }
        }
    }

    KeyboardReplyDialog(
        visible = keyboardComposerVisible,
        conversation = conversation,
        draft = keyboardDraft,
        onDraftChange = { keyboardDraft = it },
        onDismissRequest = { keyboardComposerVisible = false },
        onSend = { submitReply(keyboardDraft) },
    )
}

private fun createVoiceReplyIntent(conversationTitle: String?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Reply to ${conversationTitle ?: "thread"}")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

@Composable
private fun SyncAwareTimeText(
    syncStatus: SyncStatus,
    pendingMutations: Int,
    threadTitle: String?,
    showSyncStatus: Boolean,
) {
    val statusText =
        if (!showSyncStatus) {
            null
        } else {
            when {
                syncStatus == SyncStatus.Syncing -> "Syncing"
                syncStatus == SyncStatus.OfflineQueue && pendingMutations > 0 -> "$pendingMutations pending"
                syncStatus == SyncStatus.OfflineQueue -> "Offline"
                pendingMutations > 0 -> "$pendingMutations pending"
                else -> null
            }
        }
    TimeText { time ->
        val renderedTime =
            listOfNotNull(
                threadTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(22),
                statusText,
                time,
            ).joinToString(separator = " · ")
        timeTextCurvedText(renderedTime)
    }
}

@Composable
private fun SettingsScreen(
    layoutSpec: WearLayoutSpec,
    settings: SettingsUiState,
    actionsEnabled: Boolean,
    onHapticsToggle: (Boolean) -> Unit,
    onMarkReadToggle: (Boolean) -> Unit,
    onGlassToggle: (Boolean) -> Unit,
    onSyncProfileSelected: (SyncProfile) -> Unit,
) {
    val compactScreen = layoutSpec.compact
    val hapticFeedback = LocalHapticFeedback.current

    fun performActionHaptic() {
        if (settings.hapticsEnabled) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    WearScreenShell(layoutSpec = layoutSpec) {
        item { ListHeader { Text("Settings") } }
        item {
            SwitchButton(
                checked = settings.hapticsEnabled,
                onCheckedChange = { enabled ->
                    if (settings.hapticsEnabled || enabled) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    onHapticsToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                label = { Text("Haptics") },
                secondaryLabel = { Text("Tactile feedback for key actions") },
            )
        }
        item {
            SwitchButton(
                checked = settings.markReadOnOpen,
                onCheckedChange = { enabled ->
                    performActionHaptic()
                    onMarkReadToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                label = { Text("Auto mark read") },
                secondaryLabel = { Text("Read when thread opens") },
            )
        }
        item {
            SwitchButton(
                checked = settings.glassBoostEnabled,
                onCheckedChange = { enabled ->
                    performActionHaptic()
                    onGlassToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                label = { Text("Glass boost") },
                secondaryLabel = { Text("Higher contrast for sunlight") },
            )
        }
        item { ListSubHeader { Text("Sync profile") } }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.Balanced,
                onSelect = {
                    performActionHaptic()
                    onSyncProfileSelected(SyncProfile.Balanced)
                },
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                label = { Text("Balanced") },
                secondaryLabel = { Text("Auto sync every 45 seconds") },
            )
        }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.BatterySaver,
                onSelect = {
                    performActionHaptic()
                    onSyncProfileSelected(SyncProfile.BatterySaver)
                },
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
                label = { Text("Battery saver") },
                secondaryLabel = { Text("Auto sync every 3 minutes") },
            )
        }
    }
}

@Composable
private fun WearScreenShell(
    layoutSpec: WearLayoutSpec,
    listState: ScalingLazyListState = rememberScalingLazyListState(),
    content: ScalingLazyListScope.() -> Unit,
) {
    val compactScreen = layoutSpec.compact
    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = {
            ScrollIndicator(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
                colors =
                    ScrollIndicatorDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.36f),
                    ),
                positionAnimationSpec = androidx.compose.animation.core.snap(),
            )
        },
    ) { contentPadding ->
        Box(
            modifier =
                Modifier.fillMaxSize().background(glassBackgroundBrush(compactScreen = compactScreen))
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                autoCentering = null,
                contentPadding = contentPadding,
                verticalArrangement =
                    Arrangement.spacedBy(if (compactScreen) 6.dp else 8.dp, Alignment.Top),
                content = content,
            )
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    layoutSpec: WearLayoutSpec,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val compactScreen = layoutSpec.compact
    val secondaryLabel =
        when {
            conversation.unreadCount > 0 -> "${conversation.unreadCount} new"
            conversation.muted -> "Muted"
            else -> null
        }

    TitleCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(layoutSpec.cardWidthFraction),
        title = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        time = {
            Text(
                text = conversation.lastUpdatedAt,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        subtitle = {
            if (secondaryLabel != null) {
                Text(
                    text = secondaryLabel,
                    color =
                        if (conversation.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        enabled = enabled,
        colors = glassCardColors(),
        border = glassBorder(),
    ) {
        Text(
            text = conversation.lastMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compactScreen) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    layoutSpec: WearLayoutSpec,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val compactScreen = layoutSpec.compact
    val bubbleColors =
        if (message.outgoing) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    val bubbleBorder =
        BorderStroke(
            width = 1.dp,
            color =
                if (message.outgoing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                },
        )
    val metaLabel =
        if (message.outgoing) {
            "${message.timestamp} · ${message.status.name.lowercase()}"
        } else {
            message.timestamp
        }
    val affordanceLabel = if (expanded) "Tap to collapse" else "Tap to expand"

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * layoutSpec.messageBubbleWidthFraction
        Box(modifier = Modifier.fillMaxWidth()) {
            Card(
                onClick = onToggleExpanded,
                modifier =
                    Modifier
                        .padding(horizontal = layoutSpec.messageBubbleHorizontalInset)
                        .align(if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                        .widthIn(max = maxBubbleWidth),
                enabled = true,
                colors = bubbleColors,
                border = bubbleBorder,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(horizontal = if (compactScreen) 10.dp else 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = message.body,
                        maxLines = if (expanded) Int.MAX_VALUE else if (compactScreen) 3 else 4,
                        overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                    )

                    Text(
                        text = metaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = affordanceLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardReplyDialog(
    visible: Boolean,
    conversation: Conversation?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        if (visible) {
            delay(150)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    AlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = { Text("Reply to ${conversation?.title ?: "thread"}") },
        text = { Text("Use keyboard input and send.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    if (draft.isNotBlank()) onSend()
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = onDismissRequest)
        },
        content = {
            item {
                Card(
                    onClick = {},
                    enabled = false,
                    colors = glassCardColors(),
                    border = glassBorder(),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Send,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onSend = { if (draft.isNotBlank()) onSend() },
                            ),
                        maxLines = 5,
                    ) { innerTextField ->
                        if (draft.isBlank()) {
                            Text(
                                text = "Type reply…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
    )
}

@Composable
private fun glassCardColors() =
    CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun glassBorder() =
    BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )

@Composable
private fun glassBackgroundBrush(compactScreen: Boolean): Brush {
    val colors = MaterialTheme.colorScheme
    return remember(colors, compactScreen) {
        Brush.radialGradient(
            colors =
                listOf(
                    colors.primaryContainer.copy(alpha = if (compactScreen) 0.36f else 0.44f),
                    colors.secondaryContainer.copy(alpha = 0.30f),
                    colors.background,
                )
        )
    }
}

private data class WearLayoutSpec(
    val compact: Boolean,
    val round: Boolean,
    val cardWidthFraction: Float,
    val topActionRowWidthFraction: Float,
    val edgeActionRowWidthFraction: Float,
    val messageBubbleWidthFraction: Float,
    val messageBubbleHorizontalInset: Dp,
    val actionButtonMinWidth: Dp,
    val edgeButtonSpacing: Dp,
    val emptyHomeTopSpacer: Dp,
)

@Composable
private fun rememberWearLayoutSpec(): WearLayoutSpec {
    val configuration = LocalConfiguration.current
    val minScreenDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val compact = minScreenDp <= 220
    val round = configuration.isScreenRound
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, round) {
        WearLayoutSpec(
            compact = compact,
            round = round,
            cardWidthFraction =
                when {
                    round && compact -> 0.9f
                    round -> 0.93f
                    else -> 1f
                },
            topActionRowWidthFraction =
                when {
                    round && compact -> 0.88f
                    round -> 0.92f
                    else -> 1f
                },
            edgeActionRowWidthFraction =
                when {
                    round && compact -> 0.9f
                    round -> 0.94f
                    else -> 1f
                },
            messageBubbleWidthFraction =
                when {
                    round && compact -> 0.66f
                    round -> 0.7f
                    compact -> 0.74f
                    else -> 0.78f
                },
            messageBubbleHorizontalInset =
                when {
                    round && compact -> 3.dp
                    round -> 2.dp
                    else -> 0.dp
                },
            actionButtonMinWidth = if (compact) 48.dp else 52.dp,
            edgeButtonSpacing = if (round && compact) 12.dp else 16.dp,
            emptyHomeTopSpacer = if (round && compact) 18.dp else 26.dp,
        )
    }
}

private data class DeviceContact(
    val id: Long,
    val displayName: String,
    val phoneNumber: String?,
)

private sealed interface ContactsPickerUiState {
    data object Loading : ContactsPickerUiState
    data object PermissionRequired : ContactsPickerUiState
    data object Empty : ContactsPickerUiState
    data class Error(val message: String) : ContactsPickerUiState
    data class Success(val contacts: List<DeviceContact>) : ContactsPickerUiState
}

private fun checkContactsPermission(
    context: Context,
): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

private fun queryDeviceContacts(
    context: Context,
): List<DeviceContact> {
    val contactsById = LinkedHashMap<Long, DeviceContact>()

    val contactsProjection =
        arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
    val contactsSortOrder =
        "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, " +
            "${ContactsContract.Contacts._ID} ASC"
    context.contentResolver
        .query(
            ContactsContract.Contacts.CONTENT_URI,
            contactsProjection,
            null,
            null,
            contactsSortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            if (idIndex == -1 || nameIndex == -1) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                if (displayName.isBlank()) {
                    continue
                }
                contactsById[id] = DeviceContact(id = id, displayName = displayName, phoneNumber = null)
            }
        }

    val phoneProjection =
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
    val phoneSortOrder =
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, " +
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
            "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
    context.contentResolver
        .query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            phoneProjection,
            null,
            null,
            phoneSortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val primaryIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
            if (idIndex == -1 || nameIndex == -1 || numberIndex == -1 || primaryIndex == -1) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim()?.ifBlank { null }
                if (displayName.isBlank()) {
                    continue
                }
                val candidate = DeviceContact(id = id, displayName = displayName, phoneNumber = number)
                val existing = contactsById[id]
                val isPrimary = cursor.getInt(primaryIndex) == 1
                if (existing == null || existing.phoneNumber == null || (isPrimary && candidate.phoneNumber != null)) {
                    contactsById[id] = candidate
                }
            }
        }
    return contactsById.values
        .sortedBy { contact ->
            contact.displayName.lowercase()
        }
}

private fun WearScreen.navKey(): String =
    when (this) {
        WearScreen.Conversations -> "screen-conversations"
        WearScreen.Compose -> "screen-compose"
        WearScreen.Settings -> "screen-settings"
        is WearScreen.Thread -> "screen-thread-$conversationId"
    }

private fun SyncProfile.initialBootstrapLimit(): Int =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_INITIAL_LIMIT
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_INITIAL_LIMIT
    }

private fun SyncProfile.pageSize(): Int =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_PAGE_SIZE
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_PAGE_SIZE
    }

private fun SyncProfile.periodicSyncIntervalMs(): Long =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_INTERVAL_MS
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_INTERVAL_MS
    }

private fun newMutationId(): String =
    "watch-${System.currentTimeMillis()}-${(1000..9999).random()}"

private const val MAX_CONVERSATION_COUNT = 300
private const val LOAD_MORE_TRIGGER_PREFIX_ITEMS = 4
private const val LOAD_MORE_THRESHOLD_ITEMS = 4
private const val SYNC_BALANCED_INITIAL_LIMIT = 25
private const val SYNC_BALANCED_PAGE_SIZE = 20
private const val SYNC_BATTERY_SAVER_INITIAL_LIMIT = 12
private const val SYNC_BATTERY_SAVER_PAGE_SIZE = 10
private const val SYNC_BALANCED_INTERVAL_MS = 45_000L
private const val SYNC_BATTERY_SAVER_INTERVAL_MS = 180_000L

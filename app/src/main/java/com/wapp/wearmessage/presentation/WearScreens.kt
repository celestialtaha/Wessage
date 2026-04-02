package com.wapp.wearmessage.presentation

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonGroupDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.ScrollIndicatorDefaults
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.timeTextCurvedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

@Composable
internal fun ConversationsScreen(
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
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ActionIconButtonGroup(
                    modifier = Modifier.fillMaxWidth(layoutSpec.topActionRowWidthFraction),
                    enabled = actionsEnabled,
                    minButtonWidth = layoutSpec.actionButtonMinWidth,
                    leadingIcon = Icons.Rounded.Create,
                    leadingDescription = "Compose",
                    onLeadingClick = onComposeConversation,
                    trailingIcon = Icons.Rounded.Settings,
                    trailingDescription = "Settings",
                    onTrailingClick = onOpenSettings,
                )
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
                    modifier = Modifier.cardWidth(layoutSpec),
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
                    LoadingIndicatorRow(indicatorSize = 32.dp)
                }
            }
            ConversationsUiState.Empty -> {
                item {
                    TitleCard(
                        onClick = onComposeConversation,
                        modifier = Modifier.cardWidth(layoutSpec),
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
                        modifier = Modifier.cardWidth(layoutSpec),
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
internal fun ComposeContactsScreen(
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
                    LoadingIndicatorRow(indicatorSize = 30.dp)
                }
            }
            ContactsPickerUiState.PermissionRequired -> {
                item {
                    TitleCard(
                        onClick = onRequestContactsPermission,
                        modifier = Modifier.cardWidth(layoutSpec),
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
                        modifier = Modifier.cardWidth(layoutSpec),
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
                        modifier = Modifier.cardWidth(layoutSpec),
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
                        modifier = Modifier.cardWidth(layoutSpec),
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
internal fun ThreadScreen(
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
                performActionHapticIfEnabled(hapticsEnabled, hapticFeedback)
                onQuickReply(voiceText)
            }
        }

    fun submitReply(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        performActionHapticIfEnabled(hapticsEnabled, hapticFeedback)
        onQuickReply(normalized)
        keyboardDraft = ""
        keyboardComposerVisible = false
    }

    fun toggleExpanded(messageId: String) {
        performActionHapticIfEnabled(hapticsEnabled, hapticFeedback)
        if (expandedMessageIds.contains(messageId)) {
            expandedMessageIds.remove(messageId)
        } else {
            expandedMessageIds.add(messageId)
        }
    }

    fun openKeyboardInput() {
        performActionHapticIfEnabled(hapticsEnabled, hapticFeedback)
        keyboardComposerVisible = true
    }

    fun openVoiceInput() {
        performActionHapticIfEnabled(hapticsEnabled, hapticFeedback)
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
                ActionIconButtonGroup(
                    modifier = Modifier.fillMaxWidth(layoutSpec.edgeActionRowWidthFraction),
                    enabled = actionsEnabled,
                    minButtonWidth = layoutSpec.actionButtonMinWidth,
                    leadingIcon = Icons.Rounded.Keyboard,
                    leadingDescription = "Keyboard input",
                    onLeadingClick = ::openKeyboardInput,
                    trailingIcon = Icons.Rounded.Mic,
                    trailingDescription = "Voice input",
                    onTrailingClick = ::openVoiceInput,
                    contentPadding = ButtonGroupDefaults.fullWidthPaddings(),
                )
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

internal fun createVoiceReplyIntent(conversationTitle: String?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Reply to ${conversationTitle ?: "thread"}")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

@Composable
internal fun SyncAwareTimeText(
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
internal fun SettingsScreen(
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

    WearScreenShell(layoutSpec = layoutSpec) {
        item { ListHeader { Text("Settings") } }
        item {
            SwitchButton(
                checked = settings.hapticsEnabled,
                onCheckedChange = { enabled ->
                    performActionHapticIfEnabled(settings.hapticsEnabled || enabled, hapticFeedback)
                    onHapticsToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.cardWidth(layoutSpec),
                label = { Text("Haptics") },
                secondaryLabel = { Text("Tactile feedback for key actions") },
            )
        }
        item {
            SwitchButton(
                checked = settings.markReadOnOpen,
                onCheckedChange = { enabled ->
                    performActionHapticIfEnabled(settings.hapticsEnabled, hapticFeedback)
                    onMarkReadToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.cardWidth(layoutSpec),
                label = { Text("Auto mark read") },
                secondaryLabel = { Text("Read when thread opens") },
            )
        }
        item {
            SwitchButton(
                checked = settings.glassBoostEnabled,
                onCheckedChange = { enabled ->
                    performActionHapticIfEnabled(settings.hapticsEnabled, hapticFeedback)
                    onGlassToggle(enabled)
                },
                enabled = actionsEnabled,
                modifier = Modifier.cardWidth(layoutSpec),
                label = { Text("Glass boost") },
                secondaryLabel = { Text("Higher contrast for sunlight") },
            )
        }
        item { ListSubHeader { Text("Sync profile") } }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.Balanced,
                onSelect = {
                    performActionHapticIfEnabled(settings.hapticsEnabled, hapticFeedback)
                    onSyncProfileSelected(SyncProfile.Balanced)
                },
                enabled = actionsEnabled,
                modifier = Modifier.cardWidth(layoutSpec),
                label = { Text("Balanced") },
                secondaryLabel = { Text("Auto sync every 45 seconds") },
            )
        }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.BatterySaver,
                onSelect = {
                    performActionHapticIfEnabled(settings.hapticsEnabled, hapticFeedback)
                    onSyncProfileSelected(SyncProfile.BatterySaver)
                },
                enabled = actionsEnabled,
                modifier = Modifier.cardWidth(layoutSpec),
                label = { Text("Battery saver") },
                secondaryLabel = { Text("Auto sync every 3 minutes") },
            )
        }
    }
}

@Composable
internal fun WearScreenShell(
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
internal fun ConversationCard(
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
        modifier = Modifier.cardWidth(layoutSpec),
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
internal fun MessageBubble(
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
internal fun KeyboardReplyDialog(
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
internal fun glassCardColors() =
    CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
internal fun glassBorder() =
    BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )

@Composable
internal fun glassBackgroundBrush(compactScreen: Boolean): Brush {
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

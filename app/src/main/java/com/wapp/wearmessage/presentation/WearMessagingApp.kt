package com.wapp.wearmessage.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text

@Composable
fun WearMessagingApp(
    viewModel: MessagingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val compactScreen = isCompactRoundScreen()
    val canSwipeBack = uiState.currentScreen != WearScreen.Conversations

    AppScaffold {
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
                        compactScreen = compactScreen,
                        actionsEnabled = !isBackground,
                        onOpenConversation = viewModel::openConversation,
                        onOpenContacts = viewModel::openContacts,
                        onOpenSettings = viewModel::openSettings,
                    )
                WearScreen.Contacts ->
                    ContactsScreen(
                        compactScreen = compactScreen,
                        onOpenConversation = viewModel::openConversation,
                    )
                WearScreen.Settings ->
                    SettingsScreen(
                        compactScreen = compactScreen,
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
                        compactScreen = compactScreen,
                        conversation = conversation,
                        messages = uiState.messagesByConversation[activeScreen.conversationId].orEmpty(),
                        actionsEnabled = !isBackground,
                        onQuickReply = { text ->
                            viewModel.queueQuickReply(activeScreen.conversationId, text)
                        },
                        onMarkRead = { viewModel.markConversationRead(activeScreen.conversationId) },
                        onMute = { viewModel.toggleConversationMute(activeScreen.conversationId) },
                        onArchive = { viewModel.archiveConversation(activeScreen.conversationId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationsScreen(
    uiState: WearMessagingUiState,
    compactScreen: Boolean,
    actionsEnabled: Boolean,
    onOpenConversation: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    WearScreenShell(compactScreen = compactScreen) {
        item {
            ListHeader { Text("Wessage") }
        }
        item {
            Text(
                text =
                    "Sync ${uiState.syncStatus.label()}  ${uiState.pendingMutations} pending",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
                    Card(
                        onClick = onOpenContacts,
                        enabled = actionsEnabled,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    ) {
                        Text("No conversations yet")
                        Text(
                            text = "Start from contacts",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is ConversationsUiState.Error -> {
                item {
                    Card(
                        onClick = {},
                        enabled = false,
                        colors = glassCardColors(),
                        border = glassBorder(),
                    ) {
                        Text("Sync error")
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            is ConversationsUiState.Success -> {
                item { ListSubHeader { Text("Conversations") } }
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        enabled = actionsEnabled,
                        compactScreen = compactScreen,
                        onClick = { onOpenConversation(conversation.id) },
                    )
                }
            }
        }

        item { ListSubHeader { Text("Navigate") } }
        item {
            FilledTonalButton(onClick = onOpenContacts, enabled = actionsEnabled) {
                Text("Contacts")
            }
        }
        item {
            OutlinedButton(onClick = onOpenSettings, enabled = actionsEnabled) {
                Text("Settings")
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    compactScreen: Boolean,
    onOpenConversation: (String) -> Unit,
) {
    val contacts =
        remember {
            listOf(
                "Product Team" to "team",
                "Samir" to "sam",
                "Ops Alerts" to "ops",
            )
        }
    WearScreenShell(compactScreen = compactScreen) {
        item { ListHeader { Text("Contacts") } }
        items(contacts, key = { it.first }) { (name, id) ->
            Card(
                onClick = { onOpenConversation(id) },
                colors = glassCardColors(),
                border = glassBorder(),
            ) {
                Text(name)
                Text(
                    text = "Open thread",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThreadScreen(
    compactScreen: Boolean,
    conversation: Conversation?,
    messages: List<Message>,
    actionsEnabled: Boolean,
    onQuickReply: (String) -> Unit,
    onMarkRead: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
) {
    val quickReplies = remember { listOf("On my way", "Seen", "Call later") }
    WearScreenShell(compactScreen = compactScreen) {
        item {
            ListHeader { Text(conversation?.title ?: "Conversation") }
        }
        item { ListSubHeader { Text("Quick actions") } }
        items(quickReplies, key = { it }) { quickReply ->
            FilledTonalButton(
                onClick = { onQuickReply(quickReply) },
                enabled = actionsEnabled,
            ) {
                Text(quickReply)
            }
        }
        item {
            Button(onClick = onMarkRead, enabled = actionsEnabled) { Text("Mark read") }
        }
        item {
            OutlinedButton(onClick = onMute, enabled = actionsEnabled) {
                Text(if (conversation?.muted == true) "Unmute" else "Mute")
            }
        }
        item {
            OutlinedButton(onClick = onArchive, enabled = actionsEnabled) {
                Text("Archive")
            }
        }
        item { ListSubHeader { Text("Messages") } }
        if (messages.isEmpty()) {
            item {
                Text(
                    text = "No messages cached yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message, compactScreen = compactScreen)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    compactScreen: Boolean,
    settings: SettingsUiState,
    actionsEnabled: Boolean,
    onHapticsToggle: (Boolean) -> Unit,
    onMarkReadToggle: (Boolean) -> Unit,
    onGlassToggle: (Boolean) -> Unit,
    onSyncProfileSelected: (SyncProfile) -> Unit,
) {
    WearScreenShell(compactScreen = compactScreen) {
        item { ListHeader { Text("Settings") } }
        item {
            SwitchButton(
                checked = settings.hapticsEnabled,
                onCheckedChange = onHapticsToggle,
                enabled = actionsEnabled,
                label = { Text("Haptics") },
                secondaryLabel = { Text("Tactile feedback for key actions") },
            )
        }
        item {
            SwitchButton(
                checked = settings.markReadOnOpen,
                onCheckedChange = onMarkReadToggle,
                enabled = actionsEnabled,
                label = { Text("Auto mark read") },
                secondaryLabel = { Text("Read when thread opens") },
            )
        }
        item {
            SwitchButton(
                checked = settings.glassBoostEnabled,
                onCheckedChange = onGlassToggle,
                enabled = actionsEnabled,
                label = { Text("Glass boost") },
                secondaryLabel = { Text("Higher contrast for sunlight") },
            )
        }
        item { ListSubHeader { Text("Sync profile") } }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.Balanced,
                onSelect = { onSyncProfileSelected(SyncProfile.Balanced) },
                enabled = actionsEnabled,
                label = { Text("Balanced") },
                secondaryLabel = { Text("Low latency updates") },
            )
        }
        item {
            RadioButton(
                selected = settings.syncProfile == SyncProfile.BatterySaver,
                onSelect = { onSyncProfileSelected(SyncProfile.BatterySaver) },
                enabled = actionsEnabled,
                label = { Text("Battery saver") },
                secondaryLabel = { Text("Batch sync every few minutes") },
            )
        }
    }
}

@Composable
private fun WearScreenShell(
    compactScreen: Boolean,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
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
    compactScreen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = glassCardColors(),
        border = glassBorder(),
    ) {
        Text(
            text = conversation.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(if (compactScreen) 2.dp else 4.dp))
        Text(
            text = conversation.lastMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compactScreen) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = conversation.lastUpdatedAt,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (conversation.unreadCount > 0) {
                Text(
                    text = "${conversation.unreadCount} new",
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (conversation.muted) {
                Text(text = "Muted", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    compactScreen: Boolean,
) {
    Card(
        onClick = {},
        enabled = false,
        colors = glassCardColors(),
        border = glassBorder(),
    ) {
        Text(
            text = if (message.outgoing) "You" else message.senderName,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(if (compactScreen) 1.dp else 3.dp))
        Text(text = message.body, maxLines = if (compactScreen) 2 else 3, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${message.timestamp}  ${message.status.name.lowercase()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

@Composable
private fun isCompactRoundScreen(): Boolean {
    val configuration = LocalConfiguration.current
    val minScreenDp =
        remember(configuration) {
            minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        }
    return minScreenDp <= 220
}

private fun WearScreen.navKey(): String =
    when (this) {
        WearScreen.Conversations -> "screen-conversations"
        WearScreen.Contacts -> "screen-contacts"
        WearScreen.Settings -> "screen-settings"
        is WearScreen.Thread -> "screen-thread-$conversationId"
    }

private fun SyncStatus.label(): String =
    when (this) {
        SyncStatus.Idle -> "idle"
        SyncStatus.Syncing -> "syncing"
        SyncStatus.OfflineQueue -> "queued"
    }

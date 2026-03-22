package com.wapp.wearmessage.sync.contract

import com.wapp.wearmessage.presentation.Conversation
import com.wapp.wearmessage.presentation.Message
import com.wapp.wearmessage.presentation.MessageStatus

fun Conversation.toSyncConversation(
    nowEpochMillis: Long,
): SyncConversation =
    SyncConversation(
        id = id,
        participants = participants,
        lastMessage = lastMessage,
        // TODO: replace with a real timestamp once source data uses epoch time.
        lastUpdatedAtEpochMillis = nowEpochMillis,
        unreadCount = unreadCount,
        muted = muted,
    )

fun Message.toSyncMessage(
    nowEpochMillis: Long,
): SyncMessage =
    SyncMessage(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        // TODO: replace with a real timestamp once source data uses epoch time.
        timestampEpochMillis = nowEpochMillis,
        status = status.toSyncStatus(),
        localVersion = localVersion,
    )

fun MessageStatus.toSyncStatus(): SyncMessageStatus =
    when (this) {
        MessageStatus.Pending -> SyncMessageStatus.PENDING
        MessageStatus.Sent -> SyncMessageStatus.SENT
        MessageStatus.Delivered -> SyncMessageStatus.DELIVERED
        MessageStatus.Failed -> SyncMessageStatus.FAILED
        MessageStatus.Read -> SyncMessageStatus.READ
    }

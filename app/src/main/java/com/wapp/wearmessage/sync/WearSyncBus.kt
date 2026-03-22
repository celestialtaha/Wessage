package com.wapp.wearmessage.sync

import com.wapp.wearmessage.sync.contract.ConversationDeltaBatch
import com.wapp.wearmessage.sync.contract.MessageDeltaBatch
import com.wapp.wearmessage.sync.contract.MutationAck
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SyncInboundEvent {
    data class Conversations(val payload: ConversationDeltaBatch) : SyncInboundEvent
    data class Messages(val payload: MessageDeltaBatch) : SyncInboundEvent
    data class Ack(val payload: MutationAck) : SyncInboundEvent
}

object WearSyncBus {
    private val _events = MutableSharedFlow<SyncInboundEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emit(event: SyncInboundEvent) {
        _events.tryEmit(event)
    }
}

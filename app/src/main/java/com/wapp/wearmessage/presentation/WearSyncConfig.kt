package com.wapp.wearmessage.presentation

internal fun WearScreen.navKey(): String =
    when (this) {
        WearScreen.Conversations -> "screen-conversations"
        WearScreen.Compose -> "screen-compose"
        WearScreen.Settings -> "screen-settings"
        is WearScreen.Thread -> "screen-thread-$conversationId"
    }

internal fun SyncProfile.initialBootstrapLimit(): Int =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_INITIAL_LIMIT
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_INITIAL_LIMIT
    }

internal fun SyncProfile.pageSize(): Int =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_PAGE_SIZE
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_PAGE_SIZE
    }

internal fun SyncProfile.periodicSyncIntervalMs(): Long =
    when (this) {
        SyncProfile.Balanced -> SYNC_BALANCED_INTERVAL_MS
        SyncProfile.BatterySaver -> SYNC_BATTERY_SAVER_INTERVAL_MS
    }

internal fun newMutationId(): String =
    "watch-${System.currentTimeMillis()}-${(1000..9999).random()}"

internal const val MAX_CONVERSATION_COUNT = 300
internal const val LOAD_MORE_TRIGGER_PREFIX_ITEMS = 4
internal const val LOAD_MORE_THRESHOLD_ITEMS = 4
internal const val SYNC_BALANCED_INITIAL_LIMIT = 25
internal const val SYNC_BALANCED_PAGE_SIZE = 20
internal const val SYNC_BATTERY_SAVER_INITIAL_LIMIT = 12
internal const val SYNC_BATTERY_SAVER_PAGE_SIZE = 10
internal const val SYNC_BALANCED_INTERVAL_MS = 45_000L
internal const val SYNC_BATTERY_SAVER_INTERVAL_MS = 180_000L

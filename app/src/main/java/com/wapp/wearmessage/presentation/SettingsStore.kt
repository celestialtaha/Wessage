package com.wapp.wearmessage.presentation

import android.content.Context

class SettingsStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): SettingsUiState =
        SettingsUiState(
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS_ENABLED, true),
            markReadOnOpen = prefs.getBoolean(KEY_MARK_READ_ON_OPEN, true),
            glassBoostEnabled = prefs.getBoolean(KEY_GLASS_BOOST_ENABLED, false),
            syncProfile =
                SyncProfile.entries.firstOrNull { profile ->
                    profile.name == prefs.getString(KEY_SYNC_PROFILE, SyncProfile.Balanced.name)
                } ?: SyncProfile.Balanced,
        )

    fun save(settings: SettingsUiState) {
        prefs.edit()
            .putBoolean(KEY_HAPTICS_ENABLED, settings.hapticsEnabled)
            .putBoolean(KEY_MARK_READ_ON_OPEN, settings.markReadOnOpen)
            .putBoolean(KEY_GLASS_BOOST_ENABLED, settings.glassBoostEnabled)
            .putString(KEY_SYNC_PROFILE, settings.syncProfile.name)
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "wessage_settings"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_MARK_READ_ON_OPEN = "mark_read_on_open"
        private const val KEY_GLASS_BOOST_ENABLED = "glass_boost_enabled"
        private const val KEY_SYNC_PROFILE = "sync_profile"
    }
}

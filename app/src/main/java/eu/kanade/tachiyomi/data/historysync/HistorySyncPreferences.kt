package eu.kanade.tachiyomi.data.historysync

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class HistorySyncPreferences(store: PreferenceStore) {
    // A restored backup must not silently start network work on another device.
    val enabled = store.getBoolean(Preference.appStateKey("history_sync_enabled"), false)
}

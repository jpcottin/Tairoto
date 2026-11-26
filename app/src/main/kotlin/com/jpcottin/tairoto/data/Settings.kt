package com.jpcottin.tairoto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val quality: QualityMode = QualityMode.Auto,
    val showStats: Boolean = false,
)

/** Where settings live; DataStore on a device, memory in tests. */
interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun setQuality(mode: QualityMode)
    suspend fun setShowStats(show: Boolean)
}

/** "auto", "low", "high", "ultra": how a quality mode is written down. */
object QualityModeCodec {
    fun encode(mode: QualityMode): String = when (mode) {
        QualityMode.Auto -> "auto"
        is QualityMode.Fixed -> mode.tier.name.lowercase()
    }

    fun decode(text: String?): QualityMode {
        if (text == null || text == "auto") return QualityMode.Auto
        val tier = QualityTier.ladder.firstOrNull { it.name.equals(text, ignoreCase = true) }
        return tier?.let { QualityMode.Fixed(it) } ?: QualityMode.Auto
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettings(context: Context) : SettingsStore {
    private val store = context.applicationContext.dataStore
    private val qualityKey = stringPreferencesKey("quality")
    private val statsKey = booleanPreferencesKey("show_stats")

    override val settings: Flow<AppSettings> = store.data.map {
        AppSettings(quality = QualityModeCodec.decode(it[qualityKey]), showStats = it[statsKey] ?: false)
    }

    override suspend fun setQuality(mode: QualityMode) {
        store.edit { it[qualityKey] = QualityModeCodec.encode(mode) }
    }

    override suspend fun setShowStats(show: Boolean) {
        store.edit { it[statsKey] = show }
    }
}

class InMemorySettings(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun setQuality(mode: QualityMode) {
        state.value = state.value.copy(quality = mode)
    }
    override suspend fun setShowStats(show: Boolean) {
        state.value = state.value.copy(showStats = show)
    }
}

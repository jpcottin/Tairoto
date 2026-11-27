package com.jpcottin.tairoto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.TimeOfDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val quality: QualityMode = QualityMode.Auto,
    val showStats: Boolean = false,
    /** The lagoon's sound: the water, the reef, and what happens on the glass. */
    val sound: Boolean = true,
    /** 0..1, under the device's own media volume. */
    val soundVolume: Float = DEFAULT_SOUND_VOLUME,
    /** The lagoon's light: the local time of day, or always day, or always night. */
    val timeOfDay: TimeOfDay = TimeOfDay.LOCAL,
) {
    companion object {
        const val DEFAULT_SOUND_VOLUME = 0.7f
    }
}

/** Where settings live; DataStore on a device, memory in tests. */
interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun setQuality(mode: QualityMode)
    suspend fun setShowStats(show: Boolean)
    suspend fun setSound(on: Boolean)
    suspend fun setSoundVolume(volume: Float)
    suspend fun setTimeOfDay(time: TimeOfDay)
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
    private val soundKey = booleanPreferencesKey("sound")
    private val volumeKey = floatPreferencesKey("sound_volume")
    private val timeKey = stringPreferencesKey("time_of_day")

    override val settings: Flow<AppSettings> = store.data.map {
        AppSettings(
            quality = QualityModeCodec.decode(it[qualityKey]),
            showStats = it[statsKey] ?: false,
            sound = it[soundKey] ?: true,
            soundVolume = (it[volumeKey] ?: AppSettings.DEFAULT_SOUND_VOLUME).coerceIn(0f, 1f),
            timeOfDay = TimeOfDay.parse(it[timeKey]) ?: TimeOfDay.LOCAL,
        )
    }

    override suspend fun setTimeOfDay(time: TimeOfDay) {
        store.edit { it[timeKey] = time.name.lowercase() }
    }

    override suspend fun setSound(on: Boolean) {
        store.edit { it[soundKey] = on }
    }

    override suspend fun setSoundVolume(volume: Float) {
        store.edit { it[volumeKey] = volume.coerceIn(0f, 1f) }
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
    override suspend fun setSound(on: Boolean) {
        state.value = state.value.copy(sound = on)
    }
    override suspend fun setSoundVolume(volume: Float) {
        state.value = state.value.copy(soundVolume = volume.coerceIn(0f, 1f))
    }
    override suspend fun setTimeOfDay(time: TimeOfDay) {
        state.value = state.value.copy(timeOfDay = time)
    }
}

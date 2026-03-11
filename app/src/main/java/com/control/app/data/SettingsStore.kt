package com.control.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.control.app.BuildConfig

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val apiEndpoint: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-5.2",
    val maxRounds: Int = 50,
    val screenshotScale: Float = 0.5f,
    val voiceLanguage: String = "zh-CN",
    val adbPaired: Boolean = false,
    val adbLastConnectPort: Int = 0,
    val showExecutionOverlay: Boolean = true,
    val relayUrl: String = BuildConfig.DEFAULT_RELAY_URL
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val API_ENDPOINT = stringPreferencesKey("api_endpoint")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val MAX_ROUNDS = intPreferencesKey("max_rounds")
        val SCREENSHOT_SCALE = floatPreferencesKey("screenshot_scale")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val ADB_PAIRED = booleanPreferencesKey("adb_paired")
        val ADB_LAST_CONNECT_PORT = intPreferencesKey("adb_last_connect_port")
        val SHOW_EXECUTION_OVERLAY = booleanPreferencesKey("show_execution_overlay")
        val RELAY_URL = stringPreferencesKey("relay_url")
    }

    private val defaults = AppSettings()

    val apiEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.API_ENDPOINT] ?: defaults.apiEndpoint
    }

    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.API_KEY] ?: defaults.apiKey
    }

    val modelName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODEL_NAME] ?: defaults.modelName
    }

    val maxRounds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAX_ROUNDS] ?: defaults.maxRounds
    }

    val screenshotScale: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCREENSHOT_SCALE] ?: defaults.screenshotScale
    }

    val voiceLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_LANGUAGE] ?: defaults.voiceLanguage
    }

    val adbPaired: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ADB_PAIRED] ?: defaults.adbPaired
    }

    val adbLastConnectPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.ADB_LAST_CONNECT_PORT] ?: defaults.adbLastConnectPort
    }

    val showExecutionOverlay: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_EXECUTION_OVERLAY] ?: defaults.showExecutionOverlay
    }

    val relayUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.RELAY_URL] ?: defaults.relayUrl
    }

    val settings: Flow<AppSettings> = combine(
        combine(apiEndpoint, apiKey, modelName) { endpoint, key, model ->
            Triple(endpoint, key, model)
        },
        combine(maxRounds, screenshotScale, voiceLanguage) { rounds, scale, lang ->
            Triple(rounds, scale, lang)
        },
        combine(adbPaired, adbLastConnectPort, showExecutionOverlay) { paired, port, overlay ->
            Triple(paired, port, overlay)
        },
        relayUrl
    ) { apiGroup, agentGroup, adbGroup, relay ->
        val (endpoint, key, model) = apiGroup
        val (rounds, scale, lang) = agentGroup
        val (paired, port, overlay) = adbGroup
        AppSettings(
            apiEndpoint = endpoint,
            apiKey = key,
            modelName = model,
            maxRounds = rounds,
            screenshotScale = scale,
            voiceLanguage = lang,
            adbPaired = paired,
            adbLastConnectPort = port,
            showExecutionOverlay = overlay,
            relayUrl = relay
        )
    }

    suspend fun updateApiEndpoint(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.API_ENDPOINT] = value
        }
    }

    suspend fun updateApiKey(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.API_KEY] = value
        }
    }

    suspend fun updateModelName(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MODEL_NAME] = value
        }
    }

    suspend fun updateMaxRounds(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MAX_ROUNDS] = value
        }
    }

    suspend fun updateScreenshotScale(value: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SCREENSHOT_SCALE] = value
        }
    }

    suspend fun updateVoiceLanguage(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_LANGUAGE] = value
        }
    }

    suspend fun updateAdbPaired(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADB_PAIRED] = value
        }
    }

    suspend fun updateAdbLastConnectPort(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADB_LAST_CONNECT_PORT] = value
        }
    }

    suspend fun updateShowExecutionOverlay(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOW_EXECUTION_OVERLAY] = value
        }
    }

    suspend fun updateRelayUrl(value: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RELAY_URL] = value
        }
    }
}

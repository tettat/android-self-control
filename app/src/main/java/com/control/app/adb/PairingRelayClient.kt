package com.control.app.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Polls a remote web server for pairing info submitted via browser.
 * This solves the problem where switching away from the Android wireless
 * debugging settings screen causes the pairing code to expire.
 *
 * Flow:
 * 1. App generates a channelId and starts polling GET /api/channel/{channelId}/pairing
 * 2. User opens http://<server>/ch/{channelId} in any browser
 * 3. User enters pairing code + ports from the Android settings pairing dialog
 * 4. App picks up the data and auto-pairs + auto-connects
 */
class PairingRelayClient(
    private val relayUrl: String,
    private val channelId: String
) {
    companion object {
        private const val TAG = "PairingRelay"
        private const val POLL_INTERVAL_MS = 2000L
        private val CHANNEL_CHARS = ('a'..'z') + ('0'..'9')

        fun generateChannelId(): String {
            return (1..6).map { CHANNEL_CHARS.random() }.joinToString("")
        }
    }

    @Serializable
    data class PairingInfo(
        val code: String = "",
        val pairingPort: String = "",
        val connectPort: String = "",
        val timestamp: Long = 0,
        val consumed: Boolean = false
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _pairingInfo = MutableStateFlow<PairingInfo?>(null)
    val pairingInfo: StateFlow<PairingInfo?> = _pairingInfo.asStateFlow()

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Returns the URL that users should open in a browser to submit pairing info.
     */
    fun getChannelUrl(): String = "$relayUrl/ch/$channelId"

    /**
     * Poll the relay server until pairing info is available.
     * Returns the PairingInfo once found, or null if cancelled.
     */
    suspend fun pollForPairingInfo(): PairingInfo? = withContext(Dispatchers.IO) {
        _isPolling.value = true
        _lastError.value = null
        Log.d(TAG, "Started polling $relayUrl/api/channel/$channelId/pairing")

        try {
            while (isActive) {
                try {
                    val request = Request.Builder()
                        .url("$relayUrl/api/channel/$channelId/pairing")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: "{}"
                    response.close()

                    if (body.isNotBlank() && body != "{}") {
                        val info = json.decodeFromString<PairingInfo>(body)
                        if (info.code.isNotBlank() && info.pairingPort.isNotBlank() && !info.consumed) {
                            Log.d(TAG, "Got pairing info: code=${info.code} port=${info.pairingPort}")
                            _pairingInfo.value = info
                            // Mark as consumed on the server
                            markConsumed()
                            return@withContext info
                        }
                    }
                    _lastError.value = null
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                    _lastError.value = "连接中转服务器失败: ${e.message}"
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            _isPolling.value = false
        }
        null
    }

    private fun markConsumed() {
        try {
            val request = Request.Builder()
                .url("$relayUrl/api/channel/$channelId/pairing/consume")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark consumed: ${e.message}")
        }
    }

    fun stopPolling() {
        _isPolling.value = false
    }

    fun clearInfo() {
        _pairingInfo.value = null
    }
}

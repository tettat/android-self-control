package com.control.app.log

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.control.app.agent.AgentEngine
import com.control.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RelayLogSyncManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val agentEngine: AgentEngine,
    private val executionLogHttpServer: ExecutionLogHttpServer
) {

    companion object {
        private const val TAG = "RelayLogSync"
        private const val PUSH_DEBOUNCE_MS = 1_000L
        private const val MAX_PUSH_ENTRIES = 200
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val deviceId: String = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "unknown-device"

    fun start() {
        scope.launch {
            combine(
                settingsStore.relayUrl,
                agentEngine.debugLog,
                agentEngine.agentState
            ) { relayUrl, _, _ ->
                relayUrl.trim().trimEnd('/')
            }.collectLatest { relayUrl ->
                if (relayUrl.isBlank()) {
                    Log.d(TAG, "Relay sync skipped: relayUrl is blank")
                    return@collectLatest
                }
                delay(PUSH_DEBOUNCE_MS)
                pushSnapshot(relayUrl)
            }
        }
    }

    fun stop() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun pushSnapshot(relayUrl: String) {
        try {
            val logServerStatus = executionLogHttpServer.status.value
            val syncedAt = System.currentTimeMillis()
            val payload = ExecutionLogFormatter.buildExportJson(
                context = appContext,
                agentState = agentEngine.agentState.value,
                entries = agentEngine.debugLog.value,
                sessions = agentEngine.sessionManager.sessions.value,
                serverPort = logServerStatus.port.takeIf { it > 0 },
                accessUrls = logServerStatus.accessUrls,
                requestedLimit = MAX_PUSH_ENTRIES
            ).apply {
                put("deviceId", deviceId)
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("relaySyncedAt", syncedAt)
                put("relaySyncedAtFormatted", ExecutionLogFormatter.formatTimestamp(syncedAt))
            }

            val request = Request.Builder()
                .url("$relayUrl/api/device-logs/$deviceId")
                .post(payload.toString().toRequestBody(mediaType))
                .build()

            Log.d(TAG, "Pushing log snapshot to $relayUrl for $deviceId")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Relay sync failed: HTTP ${response.code}")
                } else {
                    Log.d(TAG, "Relay sync success: ${agentEngine.debugLog.value.size} entries")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay sync failed: ${e.message}")
        }
    }
}

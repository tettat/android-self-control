package com.control.app

import android.app.Application
import android.provider.Settings
import com.control.app.adb.AdbExecutor
import com.control.app.agent.AgentEngine
import com.control.app.agent.SessionManager
import com.control.app.agent.SkillStore
import com.control.app.ai.OpenAIClient
import com.control.app.data.SettingsStore
import com.control.app.log.ExecutionLogHttpServer
import com.control.app.log.RelayLogSyncManager
import com.control.app.prompt.PromptManager
import com.control.app.service.FloatingBubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ControlApp : Application() {

    data class AdbStartupState(
        val isReady: Boolean = false,
        val isConnecting: Boolean = false,
        val needsPairing: Boolean = false,
        val message: String? = null
    )

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var promptManager: PromptManager
        private set
    lateinit var adbExecutor: AdbExecutor
        private set
    lateinit var openAIClient: OpenAIClient
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var skillStore: SkillStore
        private set
    lateinit var agentEngine: AgentEngine
        private set
    lateinit var executionLogHttpServer: ExecutionLogHttpServer
        private set
    lateinit var relayLogSyncManager: RelayLogSyncManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _adbStartupState = MutableStateFlow(AdbStartupState())
    val adbStartupState: StateFlow<AdbStartupState> = _adbStartupState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        promptManager = PromptManager(this)
        adbExecutor = AdbExecutor(this)
        openAIClient = OpenAIClient()
        sessionManager = SessionManager()
        skillStore = SkillStore(this)
        agentEngine = AgentEngine(this, adbExecutor, openAIClient, promptManager, settingsStore, sessionManager, skillStore)
        executionLogHttpServer = ExecutionLogHttpServer(this, agentEngine)
        executionLogHttpServer.start()
        relayLogSyncManager = RelayLogSyncManager(this, settingsStore, agentEngine, executionLogHttpServer)
        relayLogSyncManager.start()
        adbExecutor.mdnsDiscovery.startDiscovery()
        startFloatingBubbleByDefault()

        appScope.launch(Dispatchers.IO) {
            settingsStore.removeEmbeddedApiDefaultsIfPresent()
        }

        // Restore last connected port so auto-reconnect works across app restarts
        appScope.launch(Dispatchers.IO) {
            val port = settingsStore.adbLastConnectPort.first()
            if (port > 0) {
                adbExecutor.lastConnectedPort = port
            }
            ensureAdbReady()
        }
    }

    fun ensureAdbReady() {
        appScope.launch(Dispatchers.IO) {
            if (adbExecutor.isConnected()) {
                _adbStartupState.value = AdbStartupState(
                    isReady = true,
                    message = "ADB 已连接"
                )
                return@launch
            }

            val currentSettings = settingsStore.settings.first()
            if (!currentSettings.adbPaired) {
                _adbStartupState.value = AdbStartupState(
                    needsPairing = true,
                    message = "未检测到配对信息，请前往设置完成首次配对"
                )
                return@launch
            }

            _adbStartupState.value = AdbStartupState(
                isConnecting = true,
                message = "正在自动连接 ADB..."
            )

            kotlinx.coroutines.delay(2000)

            val mdnsPort = adbExecutor.mdnsDiscovery.connectService.value?.port
            val lastPort = currentSettings.adbLastConnectPort.takeIf { it > 0 }
            val candidatePorts = listOfNotNull(mdnsPort, lastPort).distinct()

            if (candidatePorts.isEmpty()) {
                _adbStartupState.value = AdbStartupState(
                    needsPairing = true,
                    message = "未发现可用的无线调试端口，请前往设置重新配对"
                )
                return@launch
            }

            for (port in candidatePorts) {
                val result = adbExecutor.connect(port)
                if (result.isSuccess) {
                    settingsStore.updateAdbLastConnectPort(port)
                    _adbStartupState.value = AdbStartupState(
                        isReady = true,
                        message = "ADB 已自动连接"
                    )
                    return@launch
                }
            }

            _adbStartupState.value = AdbStartupState(
                needsPairing = true,
                message = "自动连接失败，请前往设置重新输入配对信息"
            )
        }
    }

    private fun startFloatingBubbleByDefault() {
        if (Settings.canDrawOverlays(this) && !FloatingBubbleService.isRunning.value) {
            FloatingBubbleService.start(this)
        }
    }

    override fun onTerminate() {
        relayLogSyncManager.stop()
        executionLogHttpServer.stop()
        adbExecutor.mdnsDiscovery.stopDiscovery()
        openAIClient.shutdown()
        super.onTerminate()
    }
}

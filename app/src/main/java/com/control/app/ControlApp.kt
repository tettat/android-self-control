package com.control.app

import android.app.Application
import com.control.app.adb.AdbExecutor
import com.control.app.agent.AgentEngine
import com.control.app.agent.SessionManager
import com.control.app.agent.SkillStore
import com.control.app.ai.OpenAIClient
import com.control.app.data.SettingsStore
import com.control.app.prompt.PromptManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ControlApp : Application() {

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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        promptManager = PromptManager(this)
        adbExecutor = AdbExecutor(this)
        openAIClient = OpenAIClient()
        sessionManager = SessionManager()
        skillStore = SkillStore(this)
        agentEngine = AgentEngine(adbExecutor, openAIClient, promptManager, settingsStore, sessionManager, skillStore)

        // Restore last connected port so auto-reconnect works across app restarts
        appScope.launch(Dispatchers.IO) {
            val port = settingsStore.adbLastConnectPort.first()
            if (port > 0) {
                adbExecutor.lastConnectedPort = port
            }
        }
    }
}

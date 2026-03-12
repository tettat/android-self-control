package com.control.app.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.control.app.ControlApp
import com.control.app.adb.PairingRelayClient
import com.control.app.agent.AppSkill
import com.control.app.data.AppSettings
import com.control.app.log.ExecutionLogServerStatus
import com.control.app.ui.navigation.Routes
import com.control.app.ui.theme.StatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun PersistedTextField(
    value: String,
    onCommit: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var localValue by remember(value) { mutableStateOf(value) }
    var isEditing by remember { mutableStateOf(false) }

    if (!isEditing && localValue != value) {
        localValue = value
    }

    fun commitIfChanged() {
        isEditing = false
        if (localValue != value) {
            onCommit(localValue)
        }
    }

    OutlinedTextField(
        value = localValue,
        onValueChange = {
            isEditing = true
            localValue = it
        },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitIfChanged() }),
        modifier = modifier.onFocusChanged { focusState ->
            if (focusState.isFocused) {
                isEditing = true
            } else if (isEditing) {
                commitIfChanged()
            }
        }
    )
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ControlApp

    val settings = app.settingsStore.settings

    private val _skills = MutableStateFlow<List<AppSkill>>(emptyList())
    val skills: StateFlow<List<AppSkill>> = _skills.asStateFlow()

    fun loadSkills() {
        _skills.value = app.skillStore.getAllSkills()
    }

    fun deleteSkill(packageName: String) {
        app.skillStore.deleteSkill(packageName)
        loadSkills()
    }

    fun clearAllSkills() {
        app.skillStore.clearAllSkills()
        loadSkills()
    }

    private val _adbStatus = MutableStateFlow(AdbStatus())
    val adbStatus: StateFlow<AdbStatus> = _adbStatus.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    val connectService = app.adbExecutor.mdnsDiscovery.connectService
    val executionLogServerStatus = app.executionLogHttpServer.status

    // Remote pairing relay (created dynamically per session)
    private var relayClient: PairingRelayClient? = null

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private val _relayError = MutableStateFlow<String?>(null)
    val relayError: StateFlow<String?> = _relayError.asStateFlow()

    private val _currentChannelUrl = MutableStateFlow<String?>(null)
    val currentChannelUrl: StateFlow<String?> = _currentChannelUrl.asStateFlow()

    private var pollingJob: Job? = null

    init {
        refreshAdbStatus()
        app.ensureAdbReady()
        loadSkills()
    }

    override fun onCleared() {
        super.onCleared()
        stopRemotePairing()
    }

    fun refreshAdbStatus() {
        _adbStatus.value = AdbStatus(isConnected = app.adbExecutor.isConnected())
        app.executionLogHttpServer.refreshStatus()
    }

    /**
     * If already paired, try to auto-connect using mDNS or last known port.
     */
    private fun tryAutoConnect() {
        viewModelScope.launch {
            if (app.adbExecutor.isConnected()) return@launch
            val currentSettings = app.settingsStore.settings.first()
            if (!currentSettings.adbPaired) return@launch

            _testResult.value = "已配对，正在自动连接..."

            // Wait briefly for mDNS to discover the connect port
            kotlinx.coroutines.delay(2000)

            val mdnsPort = connectService.value?.port
            val lastPort = currentSettings.adbLastConnectPort.takeIf { it > 0 }
            val port = mdnsPort ?: lastPort

            if (port != null) {
                try {
                    val result = app.adbExecutor.connect(port)
                    result.onSuccess {
                        _testResult.value = "自动连接成功 (端口 $port)"
                        app.settingsStore.updateAdbLastConnectPort(port)
                    }.onFailure { e ->
                        // mDNS port failed, try the other one
                        val fallback = if (mdnsPort != null && lastPort != null && mdnsPort != lastPort) lastPort else null
                        if (fallback != null) {
                            val r2 = app.adbExecutor.connect(fallback)
                            r2.onSuccess {
                                _testResult.value = "自动连接成功 (端口 $fallback)"
                                app.settingsStore.updateAdbLastConnectPort(fallback)
                            }.onFailure {
                                _testResult.value = "自动连接失败，请手动输入连接端口"
                            }
                        } else {
                            _testResult.value = "自动连接失败: ${e.message}\n端口可能已变更，请手动输入"
                        }
                    }
                } catch (e: Exception) {
                    _testResult.value = "自动连接失败: ${e.message}"
                }
            } else {
                _testResult.value = "已配对，但未发现连接端口，请手动输入"
            }
            refreshAdbStatus()
        }
    }

    /**
     * Mark pairing as successful and save connect port.
     */
    private suspend fun savePairingSuccess(connectPort: Int) {
        app.settingsStore.updateAdbPaired(true)
        if (connectPort > 0) {
            app.settingsStore.updateAdbLastConnectPort(connectPort)
        }
    }

    /**
     * Start polling the remote relay server for pairing info.
     */
    fun startRemotePairing() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            val currentSettings = app.settingsStore.settings.first()
            val relayUrl = currentSettings.relayUrl.trimEnd('/')
            if (relayUrl.isBlank()) {
                _testResult.value = "请先在设置中填写中转服务器地址"
                return@launch
            }

            val channelId = PairingRelayClient.generateChannelId()
            val client = PairingRelayClient(relayUrl, channelId)
            relayClient = client

            val channelUrl = client.getChannelUrl()
            _currentChannelUrl.value = channelUrl
            _testResult.value = "等待远程配对信息...\n请在浏览器打开: $channelUrl"

            // Forward polling/error state from the active client
            launch {
                client.isPolling.collect { _isPolling.value = it }
            }
            launch {
                client.lastError.collect { _relayError.value = it }
            }

            val info = client.pollForPairingInfo()
            if (info != null) {
                _testResult.value = "收到配对信息，正在配对..."
                _isOperating.value = true
                try {
                    val pairingPort = info.pairingPort.toIntOrNull()
                    if (pairingPort == null) {
                        _testResult.value = "配对端口无效: ${info.pairingPort}"
                        return@launch
                    }
                    val result = app.adbExecutor.pair("127.0.0.1", pairingPort, info.code)
                    result.onSuccess {
                        _testResult.value = "配对成功！正在连接..."
                        val connectPort = info.connectPort.toIntOrNull()
                            ?: connectService.value?.port
                        if (connectPort != null) {
                            connectAndSave(connectPort)
                        } else {
                            savePairingSuccess(0)
                            _testResult.value = "配对成功！请手动输入连接端口"
                        }
                    }.onFailure { e ->
                        _testResult.value = "配对失败: ${e.message}"
                    }
                } catch (e: Exception) {
                    _testResult.value = "配对失败: ${e.message}"
                } finally {
                    _isOperating.value = false
                    refreshAdbStatus()
                    client.clearInfo()
                    _currentChannelUrl.value = null
                }
            }
        }
    }

    fun stopRemotePairing() {
        pollingJob?.cancel()
        pollingJob = null
        relayClient?.stopPolling()
        relayClient = null
        _isPolling.value = false
        _currentChannelUrl.value = null
    }

    fun pair(host: String, port: Int, pairingCode: String) {
        viewModelScope.launch {
            _isOperating.value = true
            _testResult.value = "正在配对..."
            try {
                val result = app.adbExecutor.pair(host, port, pairingCode)
                result.onSuccess {
                    _testResult.value = "配对成功！正在自动连接..."
                    val connectPort = connectService.value?.port
                    if (connectPort != null) {
                        connectAndSave(connectPort)
                    } else {
                        savePairingSuccess(0)
                        _testResult.value = "配对成功！请输入连接端口"
                    }
                }.onFailure { e ->
                    _testResult.value = "配对失败: ${e.message}"
                }
            } catch (e: Exception) {
                _testResult.value = "配对失败: ${e.message}"
            } finally {
                _isOperating.value = false
                refreshAdbStatus()
            }
        }
    }

    private suspend fun connectAndSave(port: Int) {
        try {
            val result = app.adbExecutor.connect(port)
            result.onSuccess {
                savePairingSuccess(port)
                _testResult.value = "配对并连接成功"
            }.onFailure { e ->
                savePairingSuccess(0)
                _testResult.value = "配对成功，但连接失败: ${e.message}\n请手动输入连接端口"
            }
        } catch (e: Exception) {
            savePairingSuccess(0)
            _testResult.value = "配对成功，但连接失败: ${e.message}"
        }
        refreshAdbStatus()
    }

    fun connect(port: Int) {
        viewModelScope.launch {
            _isOperating.value = true
            _testResult.value = "正在连接..."
            try {
                val result = app.adbExecutor.connect(port)
                result.onSuccess {
                    _testResult.value = "连接成功"
                    app.settingsStore.updateAdbLastConnectPort(port)
                }.onFailure { e ->
                    _testResult.value = "连接失败: ${e.message}"
                }
            } catch (e: Exception) {
                _testResult.value = "连接失败: ${e.message}"
            } finally {
                _isOperating.value = false
                refreshAdbStatus()
            }
        }
    }

    fun disconnect() {
        app.adbExecutor.disconnect()
        _testResult.value = "已断开连接"
        refreshAdbStatus()
    }

    fun testConnection() {
        viewModelScope.launch {
            _isOperating.value = true
            try {
                val result = app.adbExecutor.executeCommand("echo hello")
                result.onSuccess { output ->
                    _testResult.value = if (output.contains("hello")) {
                        "连接正常"
                    } else {
                        "连接异常: $output"
                    }
                }.onFailure { e ->
                    _testResult.value = "连接失败: ${e.message}"
                }
            } catch (e: Exception) {
                _testResult.value = "连接失败: ${e.message}"
            } finally {
                _isOperating.value = false
                refreshAdbStatus()
            }
        }
    }

    fun resetPairing() {
        viewModelScope.launch {
            app.settingsStore.updateAdbPaired(false)
            app.settingsStore.updateAdbLastConnectPort(0)
            _testResult.value = "已重置配对状态"
        }
    }

    fun refreshLogServerStatus() {
        app.executionLogHttpServer.refreshStatus()
    }

    suspend fun updateApiEndpoint(value: String) = app.settingsStore.updateApiEndpoint(value)
    suspend fun updateApiKey(value: String) = app.settingsStore.updateApiKey(value)
    suspend fun updateModelName(value: String) = app.settingsStore.updateModelName(value)
    suspend fun updateMaxRounds(value: Int) = app.settingsStore.updateMaxRounds(value)
    suspend fun updateScreenshotScale(value: Float) = app.settingsStore.updateScreenshotScale(value)
    suspend fun updateVoiceLanguage(value: String) = app.settingsStore.updateVoiceLanguage(value)
    suspend fun updateShowExecutionOverlay(value: Boolean) = app.settingsStore.updateShowExecutionOverlay(value)
    suspend fun updateRelayUrl(value: String) = app.settingsStore.updateRelayUrl(value)
}

data class AdbStatus(
    val isConnected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val adbStatus by viewModel.adbStatus.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isOperating by viewModel.isOperating.collectAsStateWithLifecycle()
    val isPolling by viewModel.isPolling.collectAsStateWithLifecycle()
    val relayError by viewModel.relayError.collectAsStateWithLifecycle()
    val currentChannelUrl by viewModel.currentChannelUrl.collectAsStateWithLifecycle()
    val executionLogServerStatus by viewModel.executionLogServerStatus.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadSkills() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.PROMPTS) }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "提示词管理",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Configuration
            SectionHeader(title = "API 配置")
            SettingsCard {
                PersistedTextField(
                    value = settings.apiEndpoint,
                    onCommit = { scope.launch { viewModel.updateApiEndpoint(it) } },
                    label = { Text("API 端点") },
                    placeholder = { Text("https://coding.dashscope.aliyuncs.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                var showApiKey by remember { mutableStateOf(false) }
                PersistedTextField(
                    value = settings.apiKey,
                    onCommit = { scope.launch { viewModel.updateApiKey(it) } },
                    label = { Text("API 密钥") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (showApiKey) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = settings.modelName,
                    onValueChange = { scope.launch { viewModel.updateModelName(it) } },
                    label = { Text("模型名称") },
                    placeholder = { Text("gpt-4o") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Agent Configuration
            SectionHeader(title = "智能体配置")
            SettingsCard {
                // Max rounds
                Text(
                    text = "最大执行轮次: ${settings.maxRounds}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = settings.maxRounds.toFloat(),
                    onValueChange = { scope.launch { viewModel.updateMaxRounds(it.toInt()) } },
                    valueRange = 1f..50f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Screenshot scale
                Text(
                    text = "截图缩放比例: ${"%.0f%%".format(settings.screenshotScale * 100)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = settings.screenshotScale,
                    onValueChange = {
                        val rounded = (it * 20).toInt() / 20f
                        scope.launch { viewModel.updateScreenshotScale(rounded) }
                    },
                    valueRange = 0.25f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Execution overlay toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "执行过程提示",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "自动操作时显示状态条和屏幕边缘光晕",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.showExecutionOverlay,
                        onCheckedChange = { scope.launch { viewModel.updateShowExecutionOverlay(it) } }
                    )
                }
            }

            // Accumulated skills (persisted by SkillStore)
            SectionHeader(title = "应用技巧")
            AccumulatedSkillsCard(
                skills = skills,
                onDeleteSkill = { viewModel.deleteSkill(it) },
                onClearAll = { viewModel.clearAllSkills() }
            )

            // Voice Configuration
            SectionHeader(title = "语音配置")
            SettingsCard {
                val languages = listOf(
                    "zh-CN" to "中文 (简体)",
                    "en-US" to "English (US)",
                    "ja-JP" to "日本語"
                )
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = languages.find { it.first == settings.voiceLanguage }?.second
                            ?: settings.voiceLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("语音识别语言") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch { viewModel.updateVoiceLanguage(code) }
                                    expanded = false
                                },
                                trailingIcon = if (code == settings.voiceLanguage) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // ADB Connection
            SectionHeader(title = "ADB 连接")
            AdbConnectionCard(
                adbStatus = adbStatus,
                adbPaired = settings.adbPaired,
                relayUrl = settings.relayUrl,
                currentChannelUrl = currentChannelUrl,
                testResult = testResult,
                isOperating = isOperating,
                isPolling = isPolling,
                relayError = relayError,
                onRelayUrlChange = { scope.launch { viewModel.updateRelayUrl(it) } },
                onStartRemotePairing = { viewModel.startRemotePairing() },
                onStopRemotePairing = { viewModel.stopRemotePairing() },
                onPair = { host, port, code -> viewModel.pair(host, port, code) },
                onConnect = { port -> viewModel.connect(port) },
                onDisconnect = { viewModel.disconnect() },
                onTestConnection = { viewModel.testConnection() },
                onRefresh = { viewModel.refreshAdbStatus() },
                onResetPairing = { viewModel.resetPairing() }
            )

            SectionHeader(title = "日志访问")
            ExecutionLogAccessCard(
                serverStatus = executionLogServerStatus,
                onRefresh = { viewModel.refreshLogServerStatus() }
            )

            // Xiaomi/MIUI Tips
            SectionHeader(title = "使用提示")
            UsageTipsCard()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AccumulatedSkillsCard(
    skills: List<AppSkill>,
    onDeleteSkill: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }

    SettingsCard {
        Text(
            text = "智能体执行任务时积累的应用操作技巧，已持久化保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (skills.isEmpty()) {
            Text(
                text = "暂无积累的技巧，完成任务并反思后会在此显示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SettingsCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            skills.forEach { skill ->
                var expanded by remember(skill.packageName) { mutableStateOf(false) }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.appName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${skill.packageName} · ${skill.tips.size} 条技巧",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dateFormat.format(java.util.Date(skill.lastUpdated)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { onDeleteSkill(skill.packageName) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "删除该应用技巧"
                                )
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (expanded) "收起" else "展开"
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            skill.tips.forEachIndexed { i, tip ->
                                Text(
                                    text = "${i + 1}. $tip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空全部技巧")
            }
        }
    }
}

@Composable
private fun AdbConnectionCard(
    adbStatus: AdbStatus,
    adbPaired: Boolean,
    relayUrl: String,
    currentChannelUrl: String?,
    testResult: String?,
    isOperating: Boolean,
    isPolling: Boolean,
    relayError: String?,
    onRelayUrlChange: (String) -> Unit,
    onStartRemotePairing: () -> Unit,
    onStopRemotePairing: () -> Unit,
    onPair: (String, Int, String) -> Unit,
    onConnect: (Int) -> Unit,
    onDisconnect: () -> Unit,
    onTestConnection: () -> Unit,
    onRefresh: () -> Unit,
    onResetPairing: () -> Unit
) {
    var showManualMode by remember { mutableStateOf(false) }
    var showPairingFlow by remember { mutableStateOf(false) }
    var manualPairingPort by remember { mutableStateOf("") }
    var manualPairingCode by remember { mutableStateOf("") }
    var manualConnectPort by remember { mutableStateOf("") }

    SettingsCard {
        // Connection status
        StatusRow(
            label = "连接状态",
            isOk = adbStatus.isConnected
        )

        if (adbPaired && !adbStatus.isConnected) {
            // === Already paired, just need to connect ===
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = StatusColors.Success.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = StatusColors.Success,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "设备已配对，只需连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Success
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual connect port
            OutlinedTextField(
                value = manualConnectPort,
                onValueChange = { manualConnectPort = it.filter { c -> c.isDigit() } },
                label = { Text("连接端口") },
                placeholder = { Text("无线调试页面显示的端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val port = manualConnectPort.toIntOrNull()
                    if (port != null) onConnect(port)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isOperating && manualConnectPort.isNotBlank()
            ) {
                Text("连接")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Option to re-pair
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPairingFlow = !showPairingFlow }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showPairingFlow) "收起重新配对" else "需要重新配对？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (showPairingFlow) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = showPairingFlow,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onResetPairing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重置配对状态")
                    }
                }
            }
        }

        if (!adbPaired && !adbStatus.isConnected) {
            // === First time: need to pair ===
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "远程配对 (推荐)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Relay URL input
            OutlinedTextField(
                value = relayUrl,
                onValueChange = onRelayUrlChange,
                label = { Text("中转服务器地址") },
                placeholder = { Text("https://your-relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isPolling && currentChannelUrl != null) {
                // Show channel URL prominently
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "在任意设备浏览器中打开:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentChannelUrl.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = StatusColors.Processing,
                                shape = RoundedCornerShape(5.dp)
                            )
                    )
                    Text(
                        text = "正在等待远程输入配对信息...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onStopRemotePairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("停止等待")
                }
            } else {
                Button(
                    onClick = onStartRemotePairing,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isOperating
                ) {
                    Text("开始远程配对")
                }
            }

            relayError?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showManualMode = !showManualMode }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showManualMode) "收起手动配对" else "手动配对",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (showManualMode) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = showManualMode,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualPairingCode,
                            onValueChange = { manualPairingCode = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("配对码") },
                            placeholder = { Text("6位数字") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = manualPairingPort,
                            onValueChange = { manualPairingPort = it.filter { c -> c.isDigit() } },
                            label = { Text("配对端口") },
                            placeholder = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val port = manualPairingPort.toIntOrNull()
                            if (port != null && manualPairingCode.isNotBlank()) {
                                onPair("127.0.0.1", port, manualPairingCode)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOperating &&
                                manualPairingCode.length == 6 &&
                                manualPairingPort.isNotBlank()
                    ) {
                        Text("配对")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = manualConnectPort,
                        onValueChange = { manualConnectPort = it.filter { c -> c.isDigit() } },
                        label = { Text("连接端口") },
                        placeholder = { Text("无线调试页面端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val port = manualConnectPort.toIntOrNull()
                            if (port != null) onConnect(port)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOperating && manualConnectPort.isNotBlank()
                    ) {
                        Text("连接")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
                enabled = adbStatus.isConnected && !isOperating
            ) {
                Text("断开连接")
            }

            Button(
                onClick = onTestConnection,
                modifier = Modifier.weight(1f),
                enabled = adbStatus.isConnected && !isOperating
            ) {
                Text("测试连接")
            }
        }

        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("刷新状态")
        }

        // Test result
        testResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    result.contains("成功") || result.contains("正常") -> StatusColors.Success
                    result.contains("正在") || result.contains("等待") || result.contains("已配对") -> StatusColors.Processing
                    else -> StatusColors.Error
                }
            )
        }

        // Instructions
        if (!adbPaired) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "首次配对步骤:\n" +
                        "1. 填写中转服务器地址（或使用他人提供的地址）\n" +
                        "2. 点击「开始远程配对」，会生成一个配对链接\n" +
                        "3. 在任意设备浏览器中打开该链接\n" +
                        "4. 手机进入 设置 > 开发者选项 > 无线调试 > 使用配对码配对设备\n" +
                        "5. 将配对码和端口填入网页并提交\n" +
                        "6. App 会自动完成配对，以后自动连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExecutionLogAccessCard(
    serverStatus: ExecutionLogServerStatus,
    onRefresh: () -> Unit
) {
    SettingsCard {
        StatusRow(
            label = "日志服务",
            isOk = serverStatus.isRunning,
            okText = "已启动",
            badText = "未启动"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "同一局域网内优先使用第一个地址访问，即可读取当前手机上的执行日志。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                serverStatus.accessUrls.forEach { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "浏览器页面: /\nJSON 日志: /api/logs\n会话列表: /api/sessions\n健康检查: /health",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "说明: 这是只读接口，返回当前 App 进程内的执行日志；App 在运行时即可访问。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        serverStatus.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Error
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("刷新日志地址")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    isOk: Boolean,
    okText: String = "已连接",
    badText: String = "未连接"
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (isOk) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
            contentDescription = null,
            tint = if (isOk) StatusColors.Success else StatusColors.Error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (isOk) okText else badText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isOk) StatusColors.Success else StatusColors.Error
        )
    }
}

@Composable
private fun UsageTipsCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MIUI/HyperOS 兼容设置",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TipItem(
                        number = 1,
                        title = "开启开发者选项",
                        content = "设置 > 我的设备 > 全部参数与信息 > 连续点击 MIUI 版本 7 次"
                    )
                    TipItem(
                        number = 2,
                        title = "开启无线调试",
                        content = "设置 > 更多设置 > 开发者选项 > 无线调试 > 开启\n" +
                                "首次需要通过「使用配对码配对设备」进行配对"
                    )
                    TipItem(
                        number = 3,
                        title = "开启自启动",
                        content = "设置 > 应用设置 > 应用管理 > Control > 自启动 > 开启"
                    )
                    TipItem(
                        number = 4,
                        title = "关闭电池优化",
                        content = "设置 > 电池 > 省电优化 > 应用智能省电 > Control > 无限制"
                    )
                    TipItem(
                        number = 5,
                        title = "后台弹出界面权限",
                        content = "设置 > 应用设置 > 应用管理 > Control > 其他权限 > 后台弹出界面 > 允许"
                    )
                    TipItem(
                        number = 6,
                        title = "锁定后台",
                        content = "在最近任务中下拉 Control 卡片锁定，防止被系统清理"
                    )
                }
            }
        }
    }
}

@Composable
private fun TipItem(number: Int, title: String, content: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, top = 4.dp)
        )
    }
}

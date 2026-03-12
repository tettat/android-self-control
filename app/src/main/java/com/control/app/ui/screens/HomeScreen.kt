package com.control.app.ui.screens

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.core.content.FileProvider
import com.control.app.ControlApp
import com.control.app.prompt.DefaultPrompts
import com.control.app.agent.AgentState
import com.control.app.agent.DebugLogEntry
import com.control.app.agent.DebugLogType
import com.control.app.agent.StepTiming
import com.control.app.log.ExecutionLogFormatter
import com.control.app.service.FloatingBubbleService
import com.control.app.ui.navigation.Routes
import com.control.app.ui.theme.GradientColors
import com.control.app.ui.theme.StatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ControlApp
    val agentState = app.agentEngine.agentState
    val debugLog = app.agentEngine.debugLog
    val adbStartupState = app.adbStartupState

    private val _voiceText = MutableStateFlow("")
    val voiceText: StateFlow<String> = _voiceText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening() {
        mainHandler.post {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _partialText.value = ""
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                        else -> "识别错误 ($error)"
                    }
                    _partialText.value = errorMessage
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        _voiceText.value = text
                        _partialText.value = ""
                        executeCommand(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        _partialText.value = text
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            viewModelScope.launch {
                val language = app.settingsStore.voiceLanguage.first()
                mainHandler.post {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    try {
                        speechRecognizer?.startListening(intent)
                    } catch (e: Exception) {
                        _isListening.value = false
                        _partialText.value = "无法启动语音识别: ${e.message}"
                    }
                }
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
            _isListening.value = false
        }
    }

    fun executeCommand(command: String) {
        val job = viewModelScope.launch {
            app.agentEngine.executeCommand(command)
        }
        app.agentEngine.setCurrentJob(job)
    }

    fun cancelExecution() {
        app.agentEngine.cancel()
    }

    fun ensureAdbReady() {
        app.ensureAdbReady()
    }

    /**
     * Export all debug log entries to a JSON file and return its content URI for sharing.
     */
    fun exportLogs(): Uri? {
        val entries = debugLog.value
        if (entries.isEmpty()) return null

        try {
            val logServerStatus = app.executionLogHttpServer.status.value
            val root = ExecutionLogFormatter.buildExportJson(
                context = app,
                agentState = app.agentEngine.agentState.value,
                entries = entries,
                sessions = app.sessionManager.sessions.value,
                serverPort = logServerStatus.port.takeIf { it > 0 },
                accessUrls = logServerStatus.accessUrls
            )

            val debugDir = app.getExternalFilesDir("debug") ?: return null
            if (!debugDir.exists()) debugDir.mkdirs()

            val fileTimestamp = ExecutionLogFormatter.buildExportTime()
                .replace(":", "")
                .replace("-", "")
                .replace("T", "_")
            val file = File(debugDir, "control_debug_$fileTimestamp.json")
            file.writeText(root.toString(2))

            return FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to export logs", e)
            return null
        }
    }

    override fun onCleared() {
        super.onCleared()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = viewModel()
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val debugLog by viewModel.debugLog.collectAsStateWithLifecycle()
    val adbStartupState by viewModel.adbStartupState.collectAsStateWithLifecycle()
    val voiceText by viewModel.voiceText.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val partialText by viewModel.partialText.collectAsStateWithLifecycle()
    val nowMs by produceState(initialValue = System.currentTimeMillis(), key1 = agentState.isRunning) {
        value = System.currentTimeMillis()
        while (agentState.isRunning) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }

    val context = LocalContext.current
    var hasAudioPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    // Bubble service state
    val bubbleRunning by FloatingBubbleService.isRunning.collectAsStateWithLifecycle()
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Text input state
    var textInput by remember { mutableStateOf(DefaultPrompts.DEFAULT_INSTRUCTION) }

    LaunchedEffect(Unit) {
        viewModel.ensureAdbReady()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Control",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            val uri = viewModel.exportLogs()
                            if (uri != null) {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "导出调试日志")
                                )
                            } else {
                                Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "导出日志",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigate(Routes.DEBUG) }) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = "调试日志",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Mic button
                MicButton(
                    isListening = isListening,
                    isRunning = agentState.isRunning,
                    hasPermission = hasAudioPermission,
                    onClick = {
                        if (!hasAudioPermission) {
                            hasAudioPermission = context.checkSelfPermission(
                                Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            return@MicButton
                        }
                        if (isListening) {
                            viewModel.stopListening()
                        } else if (!agentState.isRunning) {
                            viewModel.startListening()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Floating bubble toggle
                TextButton(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            return@TextButton
                        }
                        if (bubbleRunning) {
                            FloatingBubbleService.stop(context)
                        } else {
                            FloatingBubbleService.start(context)
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (bubbleRunning) StatusColors.Success
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (bubbleRunning) "悬浮球运行中" else "启动悬浮球",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bubbleRunning) StatusColors.Success
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                AdbStartupCard(
                    state = adbStartupState,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )

                if (adbStartupState.message != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Status text
                StatusIndicator(
                    agentState = agentState,
                    isListening = isListening,
                    partialText = partialText,
                    nowMs = nowMs
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Voice text / command
                if (voiceText.isNotBlank()) {
                    Text(
                        text = "\"$voiceText\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }

                // Partial recognition text
                if (isListening && partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Round progress
                if (agentState.isRunning) {
                    RoundProgressIndicator(
                        currentRound = agentState.currentRound,
                        maxRounds = agentState.maxRounds
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Action feed
                Text(
                    text = "执行记录",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                ActionFeed(
                    entries = debugLog.takeLast(20).reversed(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // Text input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "输入指令...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (textInput.isNotBlank() && !agentState.isRunning && !adbStartupState.isConnecting) {
                                    viewModel.executeCommand(textInput.trim())
                                    textInput = ""
                                }
                            }
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank() && !agentState.isRunning && !adbStartupState.isConnecting) {
                                viewModel.executeCommand(textInput.trim())
                                textInput = ""
                            }
                        },
                        enabled = textInput.isNotBlank() && !agentState.isRunning && !adbStartupState.isConnecting
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "发送",
                            tint = if (textInput.isNotBlank() && !agentState.isRunning && !adbStartupState.isConnecting)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Bottom spacing for FAB
                Spacer(modifier = Modifier.height(if (agentState.isRunning) 80.dp else 8.dp))
            }

            // Stop FAB
            AnimatedVisibility(
                visible = agentState.isRunning,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(200)) + fadeOut(tween(200))
            ) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.cancelExecution() },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = null
                        )
                    },
                    text = { Text("停止执行") },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}

@Composable
private fun AdbStartupCard(
    state: ControlApp.AdbStartupState,
    onOpenSettings: () -> Unit
) {
    if (state.isReady || state.message.isNullOrBlank()) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.needsPairing) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.needsPairing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            StatusColors.Processing
                        }
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.needsPairing) "ADB 未配对" else "ADB 连接中",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.needsPairing) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state.needsPairing) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (state.needsPairing) {
                TextButton(onClick = onOpenSettings) {
                    Text("去设置")
                }
            }
        }
    }
}

@Composable
private fun MicButton(
    isListening: Boolean,
    isRunning: Boolean,
    hasPermission: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_alpha"
    )

    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_glow"
    )

    val scale = if (isListening) pulseScale else 1f
    val alpha = if (isListening) pulseAlpha else 1f

    val gradient = if (isListening) {
        Brush.radialGradient(
            colors = GradientColors.MicButtonListening
        )
    } else {
        Brush.radialGradient(
            colors = GradientColors.MicButtonIdle
        )
    }

    val iconTint = if (!hasPermission) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else {
        Color.White
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
    ) {
        // Glow effect when listening
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(glowScale)
                    .graphicsLayer { this.alpha = 0.3f * pulseAlpha }
                    .clip(CircleShape)
                    .background(GradientColors.MicButtonGlow)
            )
        }

        // Main button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .scale(scale)
                .graphicsLayer { this.alpha = alpha }
                .shadow(
                    elevation = if (isListening) 16.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = if (isListening) StatusColors.Listening else MaterialTheme.colorScheme.primary,
                    spotColor = if (isListening) StatusColors.Listening else MaterialTheme.colorScheme.primary
                )
                .clip(CircleShape)
                .background(gradient)
                .then(
                    if (isRunning && !isListening) {
                        Modifier.graphicsLayer { this.alpha = 0.5f }
                    } else {
                        Modifier
                    }
                )
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(100.dp),
                enabled = !isRunning || isListening
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = if (isListening) "停止录音" else "开始录音",
                    tint = iconTint,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    agentState: AgentState,
    isListening: Boolean,
    partialText: String,
    nowMs: Long
) {
    val statusText: String
    val statusColor: Color

    when {
        isListening -> {
            statusText = "正在听..."
            statusColor = StatusColors.Listening
        }
        agentState.isRunning -> {
            statusText = agentState.statusMessage
            statusColor = StatusColors.Executing
        }
        else -> {
            statusText = agentState.statusMessage
            statusColor = StatusColors.Idle
        }
    }

    val totalSeconds = if (agentState.taskStartedAtMs > 0L) {
        ((nowMs - agentState.taskStartedAtMs).coerceAtLeast(0L)) / 1000
    } else 0L
    val stepSeconds = if (agentState.phaseStartedAtMs > 0L) {
        ((nowMs - agentState.phaseStartedAtMs).coerceAtLeast(0L)) / 1000
    } else 0L
    val idleSeconds = if (agentState.lastProgressAtMs > 0L) {
        ((nowMs - agentState.lastProgressAtMs).coerceAtLeast(0L)) / 1000
    } else 0L

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor
            )
        }

        if (agentState.isRunning) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("总耗时 ${formatDuration(totalSeconds)}")
                    append(" | 当前步骤耗时 ${formatDuration(stepSeconds)}")
                    if (idleSeconds >= 5) {
                        append(" | 无新进展 ${formatDuration(idleSeconds)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (agentState.activeTool.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前工具: ${agentState.activeTool}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (agentState.lastAction.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "最近进展: ${agentState.lastAction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (agentState.stepTimings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "总耗时 ${formatDuration(totalSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            StepTimingSummary(stepTimings = agentState.stepTimings)
        }
    }
}

@Composable
private fun StepTimingSummary(stepTimings: List<StepTiming>) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "步骤耗时",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        stepTimings.forEachIndexed { index, step ->
            val presentation = ExecutionLogFormatter.describeStepTiming(step)
            val breakdownSummary = ExecutionLogFormatter.formatTimingBreakdown(step.breakdown)
            Text(
                text = "${index + 1}. [${presentation.category}] ${presentation.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "耗时 ${ExecutionLogFormatter.formatDurationMs(step.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (breakdownSummary.isNotBlank()) {
                Text(
                    text = breakdownSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (presentation.subtitle.isNotBlank()) {
                Text(
                    text = presentation.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (step.toolArguments.isNotBlank()) {
                Text(
                    text = "参数 ${step.toolArguments}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (step.intent.isNotBlank()) {
                Text(
                    text = "意图 ${step.intent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index != stepTimings.lastIndex) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
}

@Composable
private fun RoundProgressIndicator(
    currentRound: Int,
    maxRounds: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "执行轮次",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$currentRound / $maxRounds",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ActionFeed(
    entries: List<DebugLogEntry>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.firstOrNull()?.id) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    if (entries.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击麦克风按钮开始语音指令",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = entries,
                key = { it.id }
            ) { entry ->
                ActionFeedItem(entry = entry)
            }
        }
    }
}

@Composable
private fun ActionFeedItem(entry: DebugLogEntry) {
    val (icon, badgeColor) = remember(entry.type) {
        when (entry.type) {
            DebugLogType.VOICE_INPUT -> Icons.Filled.Mic to StatusColors.VoiceBadge
            DebugLogType.SCREENSHOT -> Icons.Outlined.Info to StatusColors.ScreenshotBadge
            DebugLogType.API_REQUEST -> Icons.Outlined.PlayArrow to StatusColors.ApiBadge
            DebugLogType.API_RESPONSE -> Icons.Outlined.PlayArrow to StatusColors.ApiBadge
            DebugLogType.ACTION_EXECUTED -> Icons.Outlined.TouchApp to StatusColors.ActionBadge
            DebugLogType.ERROR -> Icons.Outlined.Error to StatusColors.ErrorBadge
            DebugLogType.INFO -> Icons.Outlined.CheckCircle to StatusColors.InfoBadge
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeText = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.content.isNotBlank()) {
                    Text(
                        text = entry.content.take(100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

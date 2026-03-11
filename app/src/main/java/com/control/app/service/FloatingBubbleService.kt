package com.control.app.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.control.app.ControlApp
import com.control.app.MainActivity
import com.control.app.agent.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"
        private const val CHANNEL_ID = "control_bubble_channel"
        private const val NOTIFICATION_ID = 2
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        private const val BUBBLE_SIZE_DP = 52
        private const val ACTION_BTN_SIZE_DP = 44
        private const val MARGIN_DP = 8

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        // Color constants
        private val COLOR_IDLE = 0xFF1E88E5.toInt()
        private val COLOR_LISTENING = 0xFF00BFA5.toInt()
        private val COLOR_RUNNING = 0xFFFFA726.toInt()
        private val COLOR_MIC = 0xFF00BFA5.toInt()
        private val COLOR_APP = 0xFF7E57C2.toInt()
        private val COLOR_CLOSE = 0xFFEF5350.toInt()
        private val COLOR_STOP = 0xFFFF5722.toInt()
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var menuContainer: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private var overlayPanel: TextView? = null
    private var overlayPanelParams: WindowManager.LayoutParams? = null
    private var showOverlaySetting = true

    private var isMenuExpanded = false
    private var isListening = false
    private var isAgentRunning = false
    private var pulseAnimator: ObjectAnimator? = null
    private var overlayTickerJob: Job? = null
    private var latestAgentState: AgentState = AgentState()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keepaliveJob: Job? = null

    private val app: ControlApp by lazy { application as ControlApp }

    // ======================== Lifecycle ========================

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        createBubble()
        createMenu()
        createOverlayPanel()
        startKeepalive()
        observeAgentState()
        observeOverlayVisibility()
        observeOverlaySetting()
        startOverlayTicker()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        stopPulse()
        overlayTickerJob?.cancel()
        serviceScope.cancel()
        destroySpeechRecognizer()
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        try { windowManager.removeView(menuContainer) } catch (_: Exception) {}
        try { overlayPanel?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ======================== UI Helpers ========================

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // ======================== Bubble ========================

    private fun createBubble() {
        val size = dp(BUBBLE_SIZE_DP)

        bubbleView = FrameLayout(this).apply {
            background = circleDrawable(COLOR_IDLE)
            elevation = dp(8).toFloat()
        }

        val label = TextView(this).apply {
            text = "C"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        bubbleView.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = dp(300)
        }

        setupBubbleTouch()
        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupBubbleTouch() {
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var dragging = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = bubbleParams.x; initY = bubbleParams.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (abs(dx) > 8 || abs(dy) > 8) dragging = true
                    if (dragging) {
                        bubbleParams.x = (initX + dx).toInt()
                        bubbleParams.y = (initY + dy).toInt()
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        if (isMenuExpanded) syncMenuPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleMenu() else snapToEdge(); true
                }
                else -> false
            }
        }
    }

    // ======================== Menu ========================

    private fun createMenu() {
        val btnSize = dp(ACTION_BTN_SIZE_DP)
        val gap = dp(10)

        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        // 1. Stop agent (hidden when not running)
        stopBtn = makeBtn("⏹", COLOR_STOP, btnSize)
        stopBtn.setOnClickListener { app.agentEngine.cancel() }
        stopBtn.visibility = View.GONE
        menuContainer.addView(stopBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            bottomMargin = gap
        })

        // 2. Mic - tap to start/stop voice
        micBtn = makeBtn("🎤", COLOR_MIC, btnSize)
        micBtn.setOnClickListener { toggleVoice() }
        menuContainer.addView(micBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            bottomMargin = gap
        })

        // 3. Open app
        val appBtn = makeBtn("📱", COLOR_APP, btnSize)
        appBtn.setOnClickListener { openMainApp() }
        menuContainer.addView(appBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            bottomMargin = gap
        })

        // 4. Close overlay
        val closeBtn = makeBtn("✕", COLOR_CLOSE, btnSize)
        closeBtn.setOnClickListener { stopSelf() }
        menuContainer.addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        menuParams = WindowManager.LayoutParams(
            btnSize, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        syncMenuPosition()
        windowManager.addView(menuContainer, menuParams)
    }

    private fun makeBtn(icon: String, color: Int, size: Int): TextView = TextView(this).apply {
        text = icon
        textSize = if (icon == "✕") 18f else 20f
        gravity = Gravity.CENTER
        background = circleDrawable(color)
        elevation = dp(4).toFloat()
        isClickable = true; isFocusable = true
    }

    // ======================== Overlay Panel ========================

    private fun createOverlayPanel() {
        overlayPanel = TextView(this).apply {
            setBackgroundColor(0xCC1A1D24.toInt())
            setTextColor(0xCCE0E0E0.toInt())
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 5
            gravity = Gravity.START
            visibility = View.GONE
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        overlayPanelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }

        windowManager.addView(overlayPanel, overlayPanelParams)
    }

    // ======================== Menu Animation ========================

    private fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
        if (isMenuExpanded) {
            syncMenuPosition()
            menuContainer.visibility = View.VISIBLE
            menuContainer.alpha = 0f
            menuContainer.translationY = dp(20).toFloat()
            menuContainer.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            menuContainer.animate().alpha(0f).translationY(dp(20).toFloat())
                .setDuration(150)
                .withEndAction { menuContainer.visibility = View.GONE }
                .start()
        }
    }

    private fun syncMenuPosition() {
        val bubbleSize = dp(BUBBLE_SIZE_DP)
        val btnSize = dp(ACTION_BTN_SIZE_DP)
        val gap = dp(10)
        // Count visible children for height calc
        val visibleCount = (0 until menuContainer.childCount).count {
            menuContainer.getChildAt(it).visibility != View.GONE
        }
        val menuH = btnSize * visibleCount + gap * (visibleCount - 1).coerceAtLeast(0)
        menuParams.x = bubbleParams.x + (bubbleSize - btnSize) / 2
        menuParams.y = bubbleParams.y - menuH - dp(8)
        try { windowManager.updateViewLayout(menuContainer, menuParams) } catch (_: Exception) {}
    }

    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        val bubbleSize = dp(BUBBLE_SIZE_DP)
        val targetX = if (bubbleParams.x + bubbleSize / 2 < screenW / 2)
            dp(MARGIN_DP) else screenW - bubbleSize - dp(MARGIN_DP)

        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 200
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                try {
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    if (isMenuExpanded) syncMenuPosition()
                } catch (_: Exception) {}
            }
            start()
        }
    }

    // ======================== Voice Recognition ========================

    private fun toggleVoice() {
        if (isListening) stopVoice() else startVoice()
    }

    private fun startVoice() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                isListening = true
                updateMicVisual(true)
                updateBubbleColor(COLOR_LISTENING)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(err: Int) {
                isListening = false
                updateMicVisual(false)
                updateBubbleColor(COLOR_IDLE)
                Log.w(TAG, "Speech error: $err")
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                updateMicVisual(false)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    executeVoiceCommand(text)
                } else {
                    updateBubbleColor(COLOR_IDLE)
                }
            }
            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(type: Int, params: Bundle?) {}
        })

        serviceScope.launch {
            val lang = app.settingsStore.voiceLanguage.first()
            mainHandler.post {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                try {
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Start speech failed: ${e.message}")
                    isListening = false
                    updateMicVisual(false)
                }
            }
        }
    }

    private fun stopVoice() {
        // stopListening triggers onResults with current recognition
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
    }

    private fun updateMicVisual(listening: Boolean) {
        mainHandler.post {
            micBtn.background = circleDrawable(if (listening) COLOR_CLOSE else COLOR_MIC)
        }
    }

    private fun destroySpeechRecognizer() {
        mainHandler.post {
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    private fun executeVoiceCommand(command: String) {
        Log.d(TAG, "Voice command: $command")
        serviceScope.launch {
            val job = launch { app.agentEngine.executeCommand(command) }
            app.agentEngine.setCurrentJob(job)
        }
    }

    // ======================== Agent State Observer ========================

    private fun observeAgentState() {
        serviceScope.launch {
            app.agentEngine.agentState.collect { state ->
                latestAgentState = state
                mainHandler.post {
                    val wasRunning = isAgentRunning
                    isAgentRunning = state.isRunning

                    if (!isListening) {
                        updateBubbleColor(if (state.isRunning) COLOR_RUNNING else COLOR_IDLE)
                    }

                    // Show/hide stop button
                    if (state.isRunning && !wasRunning) {
                        stopBtn.visibility = View.VISIBLE
                        startPulse()
                        if (isMenuExpanded) syncMenuPosition()
                    } else if (!state.isRunning && wasRunning) {
                        stopBtn.visibility = View.GONE
                        stopPulse()
                        if (isMenuExpanded) syncMenuPosition()
                    }

                    // Update overlay panel
                    if (state.isRunning && showOverlaySetting) {
                        overlayPanel?.text = buildOverlayText(state)
                        overlayPanel?.visibility = View.VISIBLE
                    } else {
                        overlayPanel?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeOverlayVisibility() {
        serviceScope.launch {
            app.agentEngine.overlayVisible.collect { visible ->
                mainHandler.post {
                    if (!visible) {
                        overlayPanel?.visibility = View.GONE
                        bubbleView.visibility = View.GONE
                        menuContainer.visibility = View.GONE
                    } else {
                        bubbleView.visibility = View.VISIBLE
                        // Only show overlay panel if agent is running and setting is enabled
                        if (isAgentRunning && showOverlaySetting) {
                            overlayPanel?.visibility = View.VISIBLE
                        }
                        // Don't restore menu - let it stay collapsed
                    }
                }
            }
        }
    }

    private fun observeOverlaySetting() {
        serviceScope.launch {
            app.settingsStore.showExecutionOverlay.collect { show ->
                showOverlaySetting = show
                mainHandler.post {
                    if (!show) overlayPanel?.visibility = View.GONE
                }
            }
        }
    }

    private fun startOverlayTicker() {
        overlayTickerJob?.cancel()
        overlayTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                mainHandler.post {
                    if (isAgentRunning && showOverlaySetting) {
                        overlayPanel?.text = buildOverlayText(latestAgentState)
                    }
                }
            }
        }
    }

    private fun buildOverlayText(state: AgentState): String {
        val now = System.currentTimeMillis()
        val totalSeconds = ((now - state.taskStartedAtMs).coerceAtLeast(0L)) / 1000
        val phaseSeconds = ((now - state.phaseStartedAtMs).coerceAtLeast(0L)) / 1000
        val idleSeconds = ((now - state.lastProgressAtMs).coerceAtLeast(0L)) / 1000

        return buildString {
            append("第${state.currentRound}/${state.maxRounds}轮 | ${state.statusMessage}")
            if (state.activeTool.isNotBlank()) {
                append("\n工具: ${state.activeTool}")
            }
            if (state.lastAction.isNotBlank()) {
                append("\n进展: ${state.lastAction.take(120)}")
            }
            append("\n总耗时 ${formatDuration(totalSeconds)} | 当前阶段 ${formatDuration(phaseSeconds)}")
            if (idleSeconds >= 5) {
                append(" | 无新进展 ${formatDuration(idleSeconds)}")
            }
            if (state.lastThinking.isNotBlank()) {
                append("\n思路: ${state.lastThinking.take(120)}")
            }
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
    }

    private fun updateBubbleColor(color: Int) {
        bubbleView.background = circleDrawable(color)
    }

    private fun startPulse() {
        stopPulse()
        pulseAnimator = ObjectAnimator.ofFloat(bubbleView, "alpha", 1f, 0.4f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleView.alpha = 1f
    }

    // ======================== Open App ========================

    private fun openMainApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        if (isMenuExpanded) toggleMenu()
    }

    // ======================== ADB Keepalive ========================

    private fun startKeepalive() {
        keepaliveJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    if (app.adbExecutor.isConnected()) {
                        app.adbExecutor.executeCommand("echo 1")
                        Log.d(TAG, "Keepalive OK")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive failed: ${e.message}")
                }
            }
        }
    }

    // ======================== Notification ========================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Control 悬浮球", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Control 悬浮球运行中")
            .setContentText("点击打开应用")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

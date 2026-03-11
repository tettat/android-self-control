package com.control.app.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.control.app.ControlApp
import com.control.app.agent.DebugLogEntry
import com.control.app.agent.DebugLogType
import com.control.app.ui.theme.MonospaceBodySmall
import com.control.app.ui.theme.StatusColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ControlApp
    val debugLog = app.agentEngine.debugLog

    fun clearLog() {
        app.agentEngine.clearLogs()
    }

    /**
     * Export all debug log entries to a JSON file and return its content URI for sharing.
     * Image base64 data is replaced with size metadata to keep the export manageable.
     */
    fun exportLogs(): Uri? {
        val entries = debugLog.value
        if (entries.isEmpty()) return null

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

            val root = JSONObject().apply {
                put("exportTime", dateFormat.format(Date()))
                put("deviceInfo", buildDeviceInfo())
                put("appVersion", getAppVersion())
                put("entryCount", entries.size)

                val jsonEntries = JSONArray()
                for (entry in entries) {
                    val obj = JSONObject().apply {
                        put("id", entry.id)
                        put("timestamp", entry.timestamp)
                        put("timestampFormatted", timestampFormat.format(Date(entry.timestamp)))
                        put("type", entry.type.name)
                        put("title", entry.title)
                        put("content", entry.content)
                        put("hasImage", entry.imageBase64 != null)
                        if (entry.imageBase64 != null) {
                            // Store approximate decoded size instead of full base64
                            val sizeKB = entry.imageBase64.length * 3 / 4 / 1024
                            put("imageSizeKB", sizeKB)
                        }
                    }
                    jsonEntries.put(obj)
                }
                put("entries", jsonEntries)
            }

            // Write to external files directory
            val debugDir = app.getExternalFilesDir("debug")
                ?: return null
            if (!debugDir.exists()) debugDir.mkdirs()

            val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(debugDir, "control_debug_$fileTimestamp.json")
            file.writeText(root.toString(2))

            // Return a FileProvider URI for sharing
            return FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("DebugViewModel", "Failed to export logs", e)
            return null
        }
    }

    private fun buildDeviceInfo(): String {
        return buildString {
            append(Build.MANUFACTURER)
            append(" ")
            append(Build.MODEL)
            append(" (Android ")
            append(Build.VERSION.RELEASE)
            append(", SDK ")
            append(Build.VERSION.SDK_INT)
            append(")")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    navController: NavController,
    viewModel: DebugViewModel = viewModel()
) {
    val debugLog by viewModel.debugLog.collectAsStateWithLifecycle()
    val entries = remember(debugLog) { debugLog.reversed() }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(entries.firstOrNull()?.id) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("调试日志")
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                text = "${entries.size}",
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
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
                        },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "导出日志",
                            tint = if (entries.isNotEmpty())
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(onClick = { viewModel.clearLog() }) {
                        Icon(
                            imageVector = Icons.Filled.ClearAll,
                            contentDescription = "清除日志",
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
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "暂无调试日志",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "执行语音指令后日志将显示在此处",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = entries,
                    key = { it.id }
                ) { entry ->
                    DebugLogCard(entry = entry)
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DebugLogCard(entry: DebugLogEntry) {
    var expanded by remember { mutableStateOf(false) }

    val (icon, badgeColor, typeName) = remember(entry.type) {
        when (entry.type) {
            DebugLogType.VOICE_INPUT -> Triple(
                Icons.Filled.Mic,
                StatusColors.VoiceBadge,
                "语音"
            )
            DebugLogType.SCREENSHOT -> Triple(
                Icons.Filled.Image,
                StatusColors.ScreenshotBadge,
                "截图"
            )
            DebugLogType.API_REQUEST -> Triple(
                Icons.Filled.Api,
                StatusColors.ApiBadge,
                "请求"
            )
            DebugLogType.API_RESPONSE -> Triple(
                Icons.Filled.Api,
                StatusColors.ApiBadge,
                "响应"
            )
            DebugLogType.ACTION_EXECUTED -> Triple(
                Icons.Filled.TouchApp,
                StatusColors.ActionBadge,
                "操作"
            )
            DebugLogType.ERROR -> Triple(
                Icons.Filled.Error,
                StatusColors.ErrorBadge,
                "错误"
            )
            DebugLogType.INFO -> Triple(
                Icons.Filled.Info,
                StatusColors.InfoBadge,
                "信息"
            )
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeText = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Badge(
                            containerColor = badgeColor.copy(alpha = 0.2f),
                            contentColor = badgeColor
                        ) {
                            Text(
                                text = typeName,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }

            // Preview (when collapsed, show first line of content)
            if (!expanded && entry.content.isNotBlank()) {
                Text(
                    text = entry.content.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 48.dp)
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    // Content text
                    if (entry.content.isNotBlank()) {
                        val formattedContent = remember(entry.content) {
                            formatContent(entry.content)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = formattedContent,
                                style = MonospaceBodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            )
                        }
                    }

                    // Screenshot thumbnail
                    if (entry.imageBase64 != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ScreenshotThumbnail(base64 = entry.imageBase64)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(base64: String) {
    var fullSize by remember { mutableStateOf(false) }

    val bitmap = remember(base64) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        val widthFraction = if (fullSize) 1f else 0.5f
        val shape = RoundedCornerShape(8.dp)

        Image(
            bitmap = bitmap,
            contentDescription = "截图",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .clip(shape)
                .clickable { fullSize = !fullSize }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Attempts to pretty-print JSON content; otherwise returns as-is.
 */
private fun formatContent(content: String): String {
    val trimmed = content.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            val json = kotlinx.serialization.json.Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
            return json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
        } catch (_: Exception) {
            // Not valid JSON, return as-is
        }
    }
    return content
}
